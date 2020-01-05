---
layout: docs
title: Custom runners
---

# Customizing the runner

```scala mdoc:invisible
import cats.effect._
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

case class DockerImage(name: String)

class DockerizedProcessRunner[F[_]](processRunner: ProcessRunner[F],
                                    mountedDirectory: Path,
                                    workingDirectory: Path,
                                    image: DockerImage)
                                   (implicit override val concurrent: Concurrent[F],
                                    contextShift: ContextShift[F])
  extends ProcessRunner[F] {

  override def startProcess[O, E](process: Process[F, O, E], blocker: Blocker): F[RunningProcess[F, O, E]] = {
    processRunner.startProcess(wrapInDocker(process), blocker)
  }

  override def startProcessGroup[O, E](processGroup: ProcessGroup[F, O, E], blocker: Blocker): F[RunningProcessGroup[F, O, E]] = {
    processRunner.startProcessGroup(
        processGroup.map(new ProcessGroup.Mapper[F, O, E] {
             def mapFirst[P <: Process[F, fs2.Stream[F, Byte], E]](process: P): P = wrapInDocker(process).asInstanceOf[P]
             def mapInner[P <: Process.UnboundIProcess[F, fs2.Stream[F, Byte], E]](process: P): P = wrapInDocker(process).asInstanceOf[P]
             def mapLast[P <: Process.UnboundIProcess[F, O, E]](process: P): P = wrapInDocker(process).asInstanceOf[P]
        }), 
        blocker
    )
  }

  private def wrapInDocker[O, E](process: Process[F, O, E]): Process[F, O, E] = {
    val envVars = process.environmentVariables.flatMap { case (key, value) => List("-e", s"$key=$value") }.toList
    process.withCommand("docker").withArguments(
      "run" :: 
        "-v" :: mountedDirectory.toString :: 
        "-w" :: workingDirectory.toString :: 
        envVars ::: 
        List(image.name, process.command) ::: 
        process.arguments
    )
  }
}
```
