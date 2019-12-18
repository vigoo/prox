package io.github.vigoo.prox

import java.lang.{Process => JvmProcess}
import java.nio.file.Path

import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect._
import cats.syntax.flatMap._
import io.github.vigoo.prox.TypedRedirection._
import fs2._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import path._

object TypedRedirection {

  // Let's define a process type and a process runner abstraction

  trait Process {
    val command: String
    val arguments: List[String]
    val workingDirectory: Option[Path]
    val environmentVariables: Map[String, String]
    val removedEnvironmentVariables: Set[String]
  }

  // Redirection is an extra capability
  trait RedirectableOutput[+P] {
    def >[O](pipe: Pipe[IO, Byte, O]): P
  }

  trait RedirectableInput[+P] {
    def <(stream: Stream[IO, Byte]): P
  }

  trait ProcessConfiguration[+P <: Process] {
    this: Process =>

    protected def selfCopy(command: String,
                           arguments: List[String],
                           workingDirectory: Option[Path],
                           environmentVariables: Map[String, String],
                           removedEnvironmentVariables: Set[String]): P

    def in(workingDirectory: Path): P =
      selfCopy(command, arguments, workingDirectory = Some(workingDirectory), environmentVariables, removedEnvironmentVariables)

    def `with`(nameValuePair: (String, String)): P =
      selfCopy(command, arguments, workingDirectory, environmentVariables = environmentVariables + nameValuePair, removedEnvironmentVariables)

    def without(name: String): P =
      selfCopy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables = removedEnvironmentVariables + name)
  }

  object Process {

    case class ProcessImplIO(override val command: String,
                             override val arguments: List[String],
                             override val workingDirectory: Option[Path],
                             override val environmentVariables: Map[String, String],
                             override val removedEnvironmentVariables: Set[String],
                             outputRedirection: Option[Pipe[IO, Byte, _]],
                             inputRedirection: Option[Stream[IO, Byte]])
      extends Process with ProcessConfiguration[ProcessImplIO] {

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIO =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplO(command: String,
                            arguments: List[String],
                            workingDirectory: Option[Path],
                            environmentVariables: Map[String, String],
                            removedEnvironmentVariables: Set[String],
                            outputRedirection: Option[Pipe[IO, Byte, _]])
      extends Process with RedirectableInput[ProcessImplIO] with ProcessConfiguration[ProcessImplO] {

      override def <(stream: Stream[IO, Byte]): ProcessImplIO =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          Some(stream),
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplO =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplI(override val command: String,
                            override val arguments: List[String],
                            override val workingDirectory: Option[Path],
                            override val environmentVariables: Map[String, String],
                            override val removedEnvironmentVariables: Set[String],
                            inputRedirection: Option[Stream[IO, Byte]])
      extends Process with RedirectableOutput[ProcessImplIO] with ProcessConfiguration[ProcessImplI] {

      override def >[O](pipe: Pipe[IO, Byte, O]): ProcessImplIO =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          Some(pipe),
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplI =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImpl(override val command: String,
                           override val arguments: List[String],
                           override val workingDirectory: Option[Path],
                           override val environmentVariables: Map[String, String],
                           override val removedEnvironmentVariables: Set[String])
      extends Process with RedirectableOutput[ProcessImplO] with RedirectableInput[ProcessImplI] with ProcessConfiguration[ProcessImpl] {

      override def >[O](pipe: Pipe[IO, Byte, O]): ProcessImplO =
        ProcessImplO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          Some(pipe)
        )

      override def <(stream: Stream[IO, Byte]): ProcessImplI =
        ProcessImplI(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          Some(stream)
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImpl =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    def apply(command: String, arguments: List[String]): ProcessImpl =
      ProcessImpl(
        command,
        arguments,
        workingDirectory = None,
        environmentVariables = Map.empty,
        removedEnvironmentVariables = Set.empty
      )
  }

  trait ProcessResult {
    val exitCode: ExitCode
  }

  // And a process runner

  trait ProcessRunner {
    def start(process: Process): Resource[IO, Fiber[IO, ProcessResult]]
  }

  // Simple JVM implementation

  case class SimpleProcessResult(override val exitCode: ExitCode)
    extends ProcessResult

  class JVMRunningProcess(val nativeProcess: JvmProcess) {
    def isAlive: IO[Boolean] =
      IO.delay(nativeProcess.isAlive)

    def kill(): IO[ProcessResult] =
      debugLog(s"kill ${nativeProcess.toString}") >> IO.delay(nativeProcess.destroyForcibly()) >> waitForExit()

    def terminate(): IO[ProcessResult] =
      debugLog(s"terminate ${nativeProcess.toString}") >> IO.delay(nativeProcess.destroy()) >> waitForExit()

    def waitForExit(): IO[ProcessResult] =
      for {
        exitCode <- IO.delay(nativeProcess.waitFor())
      } yield SimpleProcessResult(ExitCode(exitCode))

    private def debugLog(line: String): IO[Unit] =
      IO.delay(println(line))
  }

  class JVMProcessRunner(implicit contextShift: ContextShift[IO]) extends ProcessRunner {

    import JVMProcessRunner._

    override def start(process: Process): Resource[IO, Fiber[IO, ProcessResult]] = {
      val builder = withEnvironmentVariables(process,
        withWorkingDirectory(process,
          new ProcessBuilder((process.command :: process.arguments).asJava)))

      val start = IO.delay(new JVMRunningProcess(builder.start())).bracketCase { runningProcess =>
        runningProcess.waitForExit()
      } {
        case (_, Completed) =>
          IO.delay(println("completed"))
        case (_, Error(reason)) =>
          IO.delay(println(s"error: $reason"))
        case (runningProcess, Canceled) =>
          IO.delay(println("cancelling")) >> runningProcess.terminate() >> IO.unit
      }.start

      Resource.make(start)(_.cancel)
    }
  }

  object JVMProcessRunner {
    def withWorkingDirectory(process: Process, builder: ProcessBuilder): ProcessBuilder =
      process.workingDirectory match {
        case Some(directory) => builder.directory(directory.toFile)
        case None => builder
      }

    def withEnvironmentVariables(process: Process, builder: ProcessBuilder): ProcessBuilder = {
      process.environmentVariables.foreach { case (name, value) =>
        builder.environment().put(name, value)
      }
      process.removedEnvironmentVariables.foreach { name =>
        builder.environment().remove(name)
      }
      builder
    }
  }

  // Syntax helpers

  implicit class ProcessOps(private val process: Process) extends AnyVal {
    def start(implicit runner: ProcessRunner): Resource[IO, Fiber[IO, ProcessResult]] =
      runner.start(process)
  }

}

// Trying out things

object Playground extends App {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val runner: ProcessRunner = new JVMProcessRunner
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  println("--1--")
  val input = Stream("This is a test string").through(text.utf8Encode)
  val output = text.utf8Decode[IO].andThen(text.lines[IO])
  val process1 = (((Process("echo", List("Hello", " ", "world")) in home) > output) < input) without "TEMP"

  val program1 = for {
    result <- process1.start.use(_.join)
  } yield result.exitCode

  val result1 = program1.unsafeRunSync()
  println(result1)

  println("--2--")
  val process2 = Process("sh", List("-c", "sleep 500"))
  val program2 = for {
    result <- process2.start.use { runningProcess =>
      runningProcess.join.timeoutTo(2.second, IO.pure(SimpleProcessResult(ExitCode(100))))
    }
  } yield result.exitCode
  val result2 = program2.unsafeRunSync()

  println(result2)

  println("--3--")
  val process3 = Process("sh", List("-c", "sleep 500"))
  val program3 = for {
    result <- process3.start.use { runningProcess =>
      runningProcess.join
    }.timeoutTo(2.second, IO.pure(SimpleProcessResult(ExitCode(100))))
  } yield result.exitCode
  val result3 = program3.unsafeRunSync()

  println(result3)

  println("--4--")

  def withInput[P <: Process with ProcessConfiguration[P]](s: String)(process: Process with RedirectableInput[P]): P = {
    val input = Stream("This is a test string").through(text.utf8Encode)
    process < input `with` ("hello" -> "world")
  }

  val process4 = withInput("Test string")(Process("echo", List("Hello", " ", "world")))

  val program4 = for {
    result <- process4.start.use(_.join)
  } yield result.exitCode

  val result4 = program1.unsafeRunSync()
  println(result4)
}
