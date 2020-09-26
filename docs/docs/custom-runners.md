---
layout: docs
title: Custom runners
---

# Customizing the runner

```scala mdoc:invisible
import cats.effect._
import cats.Traverse
import cats.instances.vector._
import cats.syntax.functor._
import cats.syntax.flatMap._
import scala.concurrent.ExecutionContext
import io.github.vigoo.prox._
import io.github.vigoo.prox.syntax._

implicit val contextShift = IO.contextShift(ExecutionContext.global)
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

class DockerizedProcessRunner[F[_] : Sync, Info](processRunner: ProcessRunner[F, Info],
                                                 mountedDirectory: Path,
                                                 workingDirectory: Path,
                                                 image: DockerImage)
                                                 (implicit override val concurrent: Concurrent[F],
                                                  contextShift: ContextShift[F])
  extends ProcessRunner[F, DockerProcessInfo[Info]] {

  override def startProcess[O, E](process: Process[F, O, E], blocker: Blocker): F[RunningProcess[F, O, E, DockerProcessInfo[Info]]] = {
    for { 
      container <- generateContainerName
      runningProcess <- processRunner
        .startProcess(wrapInDocker(process, container), blocker)
    } yield runningProcess.mapInfo(info => DockerProcessInfo(container, info))
  }

  override def startProcessGroup[O, E](processGroup: ProcessGroup[F, O, E], blocker: Blocker): F[RunningProcessGroup[F, O, E, DockerProcessInfo[Info]]] = {
    Traverse[Vector].sequence(processGroup.originalProcesses.toVector.map(key => generateContainerName.map(c => key -> c))).flatMap { keyAndNames =>
      val nameMap = keyAndNames.toMap 
      val names = keyAndNames.map(_._2)
      val modifiedProcessGroup = processGroup.map(new ProcessGroup.Mapper[F, O, E] {
        def mapFirst[P <: Process[F, fs2.Stream[F, Byte], E]](process: P): P = wrapInDocker(process, names.head).asInstanceOf[P]
        def mapInnerWithIdx[P <: Process.UnboundIProcess[F, fs2.Stream[F, Byte], E]](process: P, idx: Int): P = 
          wrapInDocker(process, names(idx)).asInstanceOf[P]
        def mapLast[P <: Process.UnboundIProcess[F, O, E]](process: P): P = wrapInDocker(process, names.last).asInstanceOf[P]
      })
      processRunner.startProcessGroup(modifiedProcessGroup, blocker)
          .map(_.mapInfo { case (key, info) => DockerProcessInfo(nameMap(key), info) })
    }
  }

  private def generateContainerName: F[DockerContainer] =
    Sync[F].delay(DockerContainer(UUID.randomUUID().toString))

  private def wrapInDocker[O, E](process: Process[F, O, E], container: DockerContainer): Process[F, O, E] = {
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
