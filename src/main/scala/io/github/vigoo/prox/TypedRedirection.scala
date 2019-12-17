package io.github.vigoo.prox

import java.lang.{Process => JvmProcess}
import java.nio.file.Path

import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect._
import cats.syntax.flatMap._
import io.github.vigoo.prox.TypedRedirection.{JVMProcessRunner, Process, ProcessRunner, SimpleProcessResult}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object TypedRedirection {

  // Let's define a process type and a process runner abstraction

  trait Process {
    val command: String
    val arguments: List[String]
    val workingDirectory: Option[Path]
    val environmentVariables: Map[String, String]
    val removedEnvironmentVariables: Set[String]
  }

  object Process {
    case class ProcessImpl(override val command: String,
                           override val arguments: List[String],
                           override val workingDirectory: Option[Path],
                           override val environmentVariables: Map[String, String],
                           override val removedEnvironmentVariables: Set[String])
      extends Process {
    }

    def apply(command: String, arguments: List[String]): Process =
      ProcessImpl(command, arguments, None, Map.empty, Set.empty)
  }

  trait ProcessResult {
    val exitCode: ExitCode
  }

  trait RunningProcess {
    def isAlive: IO[Boolean]
    def kill(): IO[ProcessResult]
    def terminate(): IO[ProcessResult]
    def waitForExit(): IO[ProcessResult]
  }

  trait ProcessRunner {
    def start(process: Process): Resource[IO, Fiber[IO, ProcessResult]]
  }

  // Simple JVM implementation

  case class SimpleProcessResult(override val exitCode: ExitCode)
    extends ProcessResult

  class JVMRunningProcess(val nativeProcess: JvmProcess) extends RunningProcess {
    override def isAlive: IO[Boolean] =
      IO.delay(nativeProcess.isAlive)

    override def kill(): IO[ProcessResult] =
      debugLog(s"kill ${nativeProcess.toString}") >> IO.delay(nativeProcess.destroyForcibly()) >> waitForExit()

    override def terminate(): IO[ProcessResult] =
      debugLog(s"terminate ${nativeProcess.toString}") >> IO.delay(nativeProcess.destroy()) >> waitForExit()

    override def waitForExit(): IO[ProcessResult] =
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
  val process1 = Process("echo", List("Hello", " ", "world"))

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
}
