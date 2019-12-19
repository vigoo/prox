package io.github.vigoo.prox

import java.lang.{Process => JvmProcess}
import java.nio.file.Path

import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect._
import cats.kernel.Monoid
import cats.syntax.flatMap._
import io.github.vigoo.prox.TypedRedirection._
import fs2._
import _root_.io.github.vigoo.prox.path._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, blocking}
import scala.jdk.CollectionConverters._
import scala.language.higherKinds

object TypedRedirection {

  // Let's define a process type and a process runner abstraction

  trait Process[O] {
    val command: String
    val arguments: List[String]
    val workingDirectory: Option[Path]
    val environmentVariables: Map[String, String]
    val removedEnvironmentVariables: Set[String]

    val outputRedirection: OutputRedirection
    val runOutputStream: (JvmProcess, Blocker, ContextShift[IO]) => IO[O]
    val inputRedirection: InputRedirection
  }

  // Redirection is an extra capability
  trait RedirectableOutput[+P[_] <: Process[_]] {
    def connectOutput[R <: OutputRedirection, O](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, O]): P[O]

    def >(sink: Pipe[IO, Byte, Unit]): P[Unit] = toSink(sink)
    def toSink(sink: Pipe[IO, Byte, Unit]): P[Unit] =
      connectOutput(OutputStream(sink, (s: Stream[IO, Unit]) => s.compile.drain))

    // Note: these operators can be merged with > with further type classes and implicit prioritization
    def >#[O : Monoid](pipe: Pipe[IO, Byte, O]): P[O] =
      toFoldMonoid(pipe)
    def toFoldMonoid[O : Monoid](pipe: Pipe[IO, Byte, O]): P[O] =
      connectOutput(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.foldMonoid))

    def >?[O](pipe: Pipe[IO, Byte, O]): P[Vector[O]] =
      toVector(pipe)
    def toVector[O](pipe: Pipe[IO, Byte, O]): P[Vector[O]] =
      connectOutput(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.toVector))

    def drainOutput[O](pipe: Pipe[IO, Byte, O]): P[Unit] =
      connectOutput(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.drain))

    def foldOutput[O, R](pipe: Pipe[IO, Byte, O], init: R, fn: (R, O) => R): P[R] =
      connectOutput(OutputStream(pipe, (s: Stream[IO, O]) => s.compile.fold(init)(fn)))

    def >(path: Path): P[Unit] =
      toFile(path)
    def toFile(path: Path): P[Unit] =
      connectOutput(OutputFile(path, append = false))

    def >>(path: Path): P[Unit] =
      appendToFile(path)
    def appendToFile(path: Path): P[Unit] =
      connectOutput(OutputFile(path, append = true))
  }

  trait RedirectableInput[+P] {
    def connectInput(source: InputRedirection): P

    def <(path: Path): P =
      fromFile(path)
    def fromFile(path: Path): P =
      connectInput(InputFile(path))

    def <(stream: Stream[IO, Byte]): P =
      fromStream(stream, flushChunks = false)
    def !<(stream: Stream[IO, Byte]): P =
      fromStream(stream, flushChunks = true)
    def fromStream(stream: Stream[IO, Byte], flushChunks: Boolean): P =
      connectInput(InputStream(stream, flushChunks))
  }

  trait ProcessConfiguration[+P <: Process[_]] {
    this: Process[_] =>

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

    case class ProcessImplIO[O](override val command: String,
                                override val arguments: List[String],
                                override val workingDirectory: Option[Path],
                                override val environmentVariables: Map[String, String],
                                override val removedEnvironmentVariables: Set[String],
                                override val outputRedirection: OutputRedirection,
                                override val runOutputStream: (JvmProcess, Blocker, ContextShift[IO]) => IO[O],
                                override val inputRedirection: InputRedirection)
      extends Process[O] with ProcessConfiguration[ProcessImplIO[O]] {

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIO[O] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplO[O](override val command: String,
                               override val arguments: List[String],
                               override val workingDirectory: Option[Path],
                               override val environmentVariables: Map[String, String],
                               override val removedEnvironmentVariables: Set[String],
                               override val outputRedirection: OutputRedirection,
                               override val runOutputStream: (JvmProcess, Blocker, ContextShift[IO]) => IO[O],
                               override val inputRedirection: InputRedirection)
      extends Process[O] with RedirectableInput[ProcessImplIO[O]] with ProcessConfiguration[ProcessImplO[O]] {

      override def connectInput(source: InputRedirection): ProcessImplIO[O] =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          source
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplO[O] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplI[O](override val command: String,
                               override val arguments: List[String],
                               override val workingDirectory: Option[Path],
                               override val environmentVariables: Map[String, String],
                               override val removedEnvironmentVariables: Set[String],
                               override val outputRedirection: OutputRedirection,
                               override val runOutputStream: (JvmProcess, Blocker, ContextShift[IO]) => IO[O],
                               override val inputRedirection: InputRedirection)
      extends Process[O] with RedirectableOutput[ProcessImplIO] with ProcessConfiguration[ProcessImplI[O]] {

      def connectOutput[R <: OutputRedirection, RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RO]): ProcessImplIO[RO] =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplI[O] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImpl[O](override val command: String,
                              override val arguments: List[String],
                              override val workingDirectory: Option[Path],
                              override val environmentVariables: Map[String, String],
                              override val removedEnvironmentVariables: Set[String],
                              override val outputRedirection: OutputRedirection,
                              override val runOutputStream: (JvmProcess, Blocker, ContextShift[IO]) => IO[O],
                              override val inputRedirection: InputRedirection)
      extends Process[O] with RedirectableOutput[ProcessImplO] with RedirectableInput[ProcessImplI[O]] with ProcessConfiguration[ProcessImpl[_]] {

      def connectOutput[R <: OutputRedirection, RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RO]): ProcessImplO[RO] =
        ProcessImplO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override def connectInput(source: InputRedirection): ProcessImplI[O] =
        ProcessImplI(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          source
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImpl[O] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    def apply(command: String, arguments: List[String]) =
      ProcessImpl(
        command,
        arguments,
        workingDirectory = None,
        environmentVariables = Map.empty,
        removedEnvironmentVariables = Set.empty,

        outputRedirection = StdOut,
        runOutputStream = implicitly[OutputRedirectionType[StdOut.type]].runner(StdOut),
        inputRedirection = StdIn
      )
  }

  trait ProcessResult[+O] {
    val exitCode: ExitCode
    val output: O
  }

  // And a process runner

  trait ProcessRunner {
    def start[O](process: Process[O], blocker: Blocker): Resource[IO, Fiber[IO, ProcessResult[O]]]
  }

  // Simple JVM implementation

  case class SimpleProcessResult[+O](override val exitCode: ExitCode,
                                     override val output: O)
    extends ProcessResult[O]

  class JVMRunningProcess[O](val nativeProcess: JvmProcess,
                             runningInput: Fiber[IO, Unit],
                             runningOutput: Fiber[IO, O]) {
    def isAlive: IO[Boolean] =
      IO.delay(nativeProcess.isAlive)

    def kill(): IO[ProcessResult[O]] =
      debugLog(s"kill ${nativeProcess.toString}") >> IO.delay(nativeProcess.destroyForcibly()) >> waitForExit()

    def terminate(): IO[ProcessResult[O]] =
      debugLog(s"terminate ${nativeProcess.toString}") >> IO.delay(nativeProcess.destroy()) >> waitForExit()

    def waitForExit(): IO[ProcessResult[O]] =
      for {
        _ <- debugLog(s"waitforexit ${nativeProcess.toString}")
        exitCode <- IO.delay(nativeProcess.waitFor())
        _ <- runningInput.join
        output <- runningOutput.join
      } yield SimpleProcessResult(ExitCode(exitCode), output)

    private def debugLog(line: String): IO[Unit] =
      IO.delay(println(line))
  }

  class JVMProcessRunner(implicit contextShift: ContextShift[IO]) extends ProcessRunner {

    import JVMProcessRunner._

    // TODO: make run the default and start just a +fiber convenience stuff?
    override def start[O](process: Process[O], blocker: Blocker): Resource[IO, Fiber[IO, ProcessResult[O]]] = {
      val builder = withEnvironmentVariables(process,
        withWorkingDirectory(process,
          new ProcessBuilder((process.command :: process.arguments).asJava)))

      val outputRedirect = process.outputRedirection match {
        case StdOut => ProcessBuilder.Redirect.INHERIT
        case OutputFile(path, false) => ProcessBuilder.Redirect.to(path.toFile)
        case OutputFile(path, true) => ProcessBuilder.Redirect.appendTo(path.toFile)
        case OutputStream(_, _, _) => ProcessBuilder.Redirect.PIPE
      }
      builder.redirectOutput(outputRedirect)

      val inputRedirection = process.inputRedirection match {
        case StdIn => ProcessBuilder.Redirect.INHERIT
        case InputFile(path) => ProcessBuilder.Redirect.from(path.toFile)
        case InputStream(_, _) => ProcessBuilder.Redirect.PIPE
      }
      builder.redirectInput(inputRedirection)

      val startProcess = for {
        nativeProcess <- IO.delay(builder.start())
        inputStream = createInputStream(process, nativeProcess, blocker) >> IO(println("input stream closed"))
        runningInput <- inputStream.start
        runningOutput <- process.runOutputStream(nativeProcess, blocker, contextShift).start
      } yield new JVMRunningProcess(nativeProcess, runningInput, runningOutput)

      val run = startProcess.bracketCase { runningProcess =>
        runningProcess.waitForExit()
      } {
        case (_, Completed) =>
          IO.delay(println("completed"))
        case (_, Error(reason)) =>
          IO.delay(println(s"error: $reason"))
        case (runningProcess, Canceled) =>
          IO.delay(println("cancelling")) >> runningProcess.terminate() >> IO.unit
      }.start

      Resource.make(run)(_.cancel)
    }

    private def createInputStream[O](process: Process[O], nativeProcess: JvmProcess, blocker: Blocker): IO[Unit] = {
      process.inputRedirection match {
        case StdIn => IO.unit
        case InputFile(path) => IO.unit
        case InputStream(stream, false) =>
          stream
            .observe(
              io.writeOutputStream[IO](
                IO.delay(nativeProcess.getOutputStream),
                closeAfterUse = true,
                blocker = blocker))
            .compile
            .drain
        case InputStream(stream, true) =>
          stream
          .observe(writeAndFlushOutputStream(nativeProcess.getOutputStream, blocker))
          .compile
          .drain
      }
    }
  }

  object JVMProcessRunner {
    def withWorkingDirectory[O](process: Process[O], builder: ProcessBuilder): ProcessBuilder =
      process.workingDirectory match {
        case Some(directory) => builder.directory(directory.toFile)
        case None => builder
      }

    def withEnvironmentVariables[O](process: Process[O], builder: ProcessBuilder): ProcessBuilder = {
      process.environmentVariables.foreach { case (name, value) =>
        builder.environment().put(name, value)
      }
      process.removedEnvironmentVariables.foreach { name =>
        builder.environment().remove(name)
      }
      builder
    }

    def writeAndFlushOutputStream(stream: java.io.OutputStream,
                                  blocker: Blocker)
                                 (implicit contextShift: ContextShift[IO]): Pipe[IO, Byte, Unit] =
      s => {
        Stream
          .bracket(IO.pure(stream))(os => IO.delay(os.close()))
          .flatMap { os =>
            s.chunks.evalMap { chunk =>
              blocker.blockOn {
                IO.delay {
                  blocking {
                    os.write(chunk.toArray)
                    os.flush()
                  }
                }
              }
            }
          }
      }
  }

  // Output Redirection

  // made streaming first-class.
  // Fixed set of types:
  // - stdout
  // - file
  // - fs2 pipe

  // Extension methods could be defined to convert arbitrary types to these

  // Some dependent typing necessary because we have to run the connected stream somehow, and
  // we want to specify the redirections purely.
  // => inject stream runner function, provide predefined (drain, tovector, etc) and propagate the output type

  sealed trait OutputRedirection

  case object StdOut extends OutputRedirection
  case class OutputFile(path: Path, append: Boolean) extends OutputRedirection
  case class OutputStream[O, +OR](pipe: Pipe[IO, Byte, O], runner: Stream[IO, O] => IO[OR], chunkSize: Int = 8192) extends OutputRedirection

  sealed trait InputRedirection
  case object StdIn extends InputRedirection
  case class InputFile(path: Path) extends InputRedirection
  case class InputStream(stream: Stream[IO, Byte], flushChunks: Boolean) extends InputRedirection

  // Dependent typing helper
  trait OutputRedirectionType[R] {
    type Out

    def runner(of: R)(nativeProcess: JvmProcess, blocker: Blocker, contextShift: ContextShift[IO]): IO[Out]
  }

  object OutputRedirectionType {
    type Aux[R, O] = OutputRedirectionType[R] {
      type Out = O
    }

    implicit val outputRedirectionTypeOfStdOut: Aux[StdOut.type, Unit] = new OutputRedirectionType[StdOut.type] {
      override type Out = Unit

      override def runner(of: StdOut.type)(nativeProcess: JvmProcess, blocker: Blocker, contextShift: ContextShift[IO]): IO[Unit] = IO.unit
    }

    implicit val outputRedirectionTypeOfFile: Aux[OutputFile, Unit] = new OutputRedirectionType[OutputFile] {
      override type Out = Unit

      override def runner(of: OutputFile)(nativeProcess: JvmProcess, blocker: Blocker, contextShift: ContextShift[IO]): IO[Unit] = IO.unit
    }

    implicit def outputRedirectionTypeOfStream[O, OR]: Aux[OutputStream[O, OR], OR] = new OutputRedirectionType[OutputStream[O, OR]] {
      override type Out = OR

      override def runner(of: OutputStream[O, OR])(nativeProcess: JvmProcess, blocker: Blocker, contextShift: ContextShift[IO]): IO[OR] = {
        implicit val cs: ContextShift[IO] = contextShift
        of.runner(
          io.readInputStream[IO](
            IO.delay(nativeProcess.getInputStream),
            of.chunkSize,
            closeAfterUse = true,
            blocker = blocker)
            .through(of.pipe))
      }
    }
  }

  // Syntax helpers

  // TODO: merge this into Process?
  implicit class ProcessOps[O](private val process: Process[O]) extends AnyVal {
    def start(blocker: Blocker)(implicit runner: ProcessRunner): Resource[IO, Fiber[IO, ProcessResult[O]]] =
      runner.start(process, blocker)
  }
}

// Trying out things

object Playground extends App {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val runner: ProcessRunner = new JVMProcessRunner
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  println("--1--")
  val input = Stream("This is a test string").through(text.utf8Encode)
  val output = text
      .utf8Decode[IO]
      .andThen(text.lines[IO])
      .andThen(_.evalMap(s => IO(println(s))))
  val process1 = (((Process("cat", List.empty) in home) > output) < input) without "TEMP"

  val program1 = Blocker[IO].use { blocker =>
    for {
      result <- process1.start(blocker).use(_.join)
    } yield result.exitCode
  }

  val result1 = program1.unsafeRunSync()
  println(result1)

  println("--2--")
  val process2 = Process("sh", List("-c", "sleep 500"))
  val program2 = Blocker[IO].use { blocker =>
    for {
      result <- process2.start(blocker).use { runningProcess =>
        runningProcess.join.timeoutTo(2.second, IO.pure(SimpleProcessResult(ExitCode(100), ())))
      }
    } yield result.exitCode
  }
  val result2 = program2.unsafeRunSync()

  println(result2)

  println("--3--")
  val process3 = Process("sh", List("-c", "sleep 500"))
  val program3 = Blocker[IO].use { blocker =>
    for {
      result <- process3.start(blocker).use { runningProcess =>
        runningProcess.join
      }.timeoutTo(2.second, IO.pure(SimpleProcessResult(ExitCode(100), ())))
    } yield result.exitCode
  }
  val result3 = program3.unsafeRunSync()

  println(result3)

  println("--4--")

  def withInput[O, P <: Process[O] with ProcessConfiguration[P]](s: String)(process: Process[O] with RedirectableInput[P]): P = {
    val input = Stream("This is a test string").through(text.utf8Encode)
    process < input `with` ("hello" -> "world")
  }

  val process4 = withInput("Test string")(Process("cat", List.empty))

  val program4 = Blocker[IO].use { blocker =>
    for {
      result <- process4.start(blocker).use(_.join)
    } yield result.exitCode
  }

  val result4 = program4.unsafeRunSync()
  println(result4)

  println("--5--")
  val output5 = text
    .utf8Decode[IO]
    .andThen(text.lines[IO])

  val process5 = (Process("echo", List("Hello", "\n", "world")) in home) >? output5

  val program5 = Blocker[IO].use { blocker =>
    for {
      result <- process5.start(blocker).use(_.join)
    } yield result.output
  }
  val result5 = program5.unsafeRunSync()
  println(result5)
}
