---
layout: docs
title: Custom runners
---

# Customizing the runner

```scala mdoc:invisible
import zio._
import zio.blocking.Blocking
import zio.stream._
import io.github.vigoo.prox._
import io.github.vigoo.prox.zstream._
```

The _runner_ is responsible for stating the native processes and wiring all the redirections together. The default
implementation is called `JVMProcessRunner`.

There are use cases when providing a custom runner makes sense. One such use case could be to launch external processes
within a docker container in case of running on a development machine (for example from tests), while running them directly
in production, when the whole service is running within the container.

We can implement this scenario by using `JVMProcessRunner` in production and a custom `DockerizedProcessRunner` in tests,
where we define the latter as follows:

```scala mdoc
import java.nio.file.Path
import java.util.UUID

case class DockerImage(name: String)

case class DockerContainer(name: String)

case class DockerProcessInfo[DockerProcessInfo](container: DockerContainer, dockerProcessInfo: DockerProcessInfo)

class DockerizedProcessRunner[Info](processRunner: ProcessRunner[Info],
                                    mountedDirectory: Path,
                                    workingDirectory: Path,
                                    image: DockerImage)
  extends ProcessRunner[DockerProcessInfo[Info]] {

  override def startProcess[O, E](process: Process[O, E]): ZIO[Blocking, ProxError, RunningProcess[O, E, DockerProcessInfo[Info]]] = {
    for { 
      container <- generateContainerName
      runningProcess <- processRunner
        .startProcess(wrapInDocker(process, container))
    } yield runningProcess.mapInfo(info => DockerProcessInfo(container, info))
  }

  override def startProcessGroup[O, E](processGroup: ProcessGroup[O, E]): ZIO[Blocking, ProxError, RunningProcessGroup[O, E, DockerProcessInfo[Info]]] = {
    ZIO.foreach(processGroup.originalProcesses.toVector)(key => generateContainerName.map(c => key -> c)).flatMap { keyAndNames =>
      val nameMap = keyAndNames.toMap 
      val names = keyAndNames.map(_._2)
      val modifiedProcessGroup = processGroup.map(new ProcessGroup.Mapper[O, E] {
        def mapFirst[P <: Process[ZStream[Blocking, ProxError, Byte], E]](process: P): P = wrapInDocker(process, names.head).asInstanceOf[P]
        def mapInnerWithIdx[P <: Process.UnboundIProcess[ZStream[Blocking, ProxError, Byte], E]](process: P, idx: Int): P = 
          wrapInDocker(process, names(idx)).asInstanceOf[P]
        def mapLast[P <: Process.UnboundIProcess[O, E]](process: P): P = wrapInDocker(process, names.last).asInstanceOf[P]
      })
      processRunner.startProcessGroup(modifiedProcessGroup)
          .map(_.mapInfo { case (key, info) => DockerProcessInfo(nameMap(key), info) })
    }
  }

  private def generateContainerName: ZIO[Blocking, ProxError, DockerContainer] =
    ZIO.effect(DockerContainer(UUID.randomUUID().toString)).mapError(UnknownProxError)

  private def wrapInDocker[O, E](process: Process[O, E], container: DockerContainer): Process[O, E] = {
    val envVars = process.environmentVariables.flatMap { case (key, value) => List("-e", s"$key=$value") }.toList
    process.withCommand("docker").withArguments(
      "run" :: 
        "--name" :: container.name ::
        "-v" :: mountedDirectory.toString :: 
        "-w" :: workingDirectory.toString :: 
        envVars ::: 
        List(image.name, process.command) ::: 
        process.arguments
    )
  }
}
```
