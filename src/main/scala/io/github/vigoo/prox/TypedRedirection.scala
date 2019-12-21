package io.github.vigoo.prox

import java.lang.{Process => JvmProcess}
import java.nio.file.Path

import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect._
import cats.effect.syntax.all._
import cats.implicits._
//import cats.instances.list._
//import cats.syntax.foldable._
//import cats.syntax.traverse._
import cats.kernel.Monoid
import io.github.vigoo.prox.TypedRedirection._
import fs2._
import _root_.io.github.vigoo.prox.path._
import cats.{Applicative, Monad}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, blocking}
import scala.jdk.CollectionConverters._
import scala.language.higherKinds

object TypedRedirection {

  // Let's define a process type and a process runner abstraction

  trait Process[F[_], O, E] {
    implicit val sync: Sync[F]
    implicit val concurrent: Concurrent[F]

    val command: String
    val arguments: List[String]
    val workingDirectory: Option[Path]
    val environmentVariables: Map[String, String]
    val removedEnvironmentVariables: Set[String]

    val outputRedirection: OutputRedirection[F]
    val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O]
    val errorRedirection: OutputRedirection[F]
    val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E]
    val inputRedirection: InputRedirection[F]

    def start(blocker: Blocker)(implicit runner: ProcessRunner[F]): Resource[F, Fiber[F, ProcessResult[O, E]]] =
      runner.start(this, blocker)
  }

  // Redirection is an extra capability
  trait RedirectableOutput[F[_], +P[_] <: Process[F, _, _]] {
    implicit val sync: Sync[F]

    def connectOutput[R <: OutputRedirection[F], O](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, O]): P[O]

    def >(sink: Pipe[F, Byte, Unit]): P[Unit] =
      toSink(sink)

    def toSink(sink: Pipe[F, Byte, Unit]): P[Unit] =
      connectOutput(OutputStream(sink, (s: Stream[F, Unit]) => s.compile.drain))

    // Note: these operators can be merged with > with further type classes and implicit prioritization
    def >#[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
      toFoldMonoid(pipe)

    def toFoldMonoid[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
      connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.foldMonoid))

    def >?[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
      toVector(pipe)

    def toVector[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
      connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.toVector))

    def drainOutput[O](pipe: Pipe[F, Byte, O]): P[Unit] =
      connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.drain))

    def foldOutput[O, R](pipe: Pipe[F, Byte, O], init: R, fn: (R, O) => R): P[R] =
      connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.fold(init)(fn)))

    def >(path: Path): P[Unit] =
      toFile(path)

    def toFile(path: Path): P[Unit] =
      connectOutput(OutputFile[F](path, append = false))

    def >>(path: Path): P[Unit] =
      appendToFile(path)

    def appendToFile(path: Path): P[Unit] =
      connectOutput(OutputFile[F](path, append = true))
  }

  trait RedirectableError[F[_], +P[_] <: Process[F, _, _]] {
    implicit val sync: Sync[F]

    def connectError[R <: OutputRedirection[F], E](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, E]): P[E]

    def !>(sink: Pipe[F, Byte, Unit]): P[Unit] =
      errorToSink(sink)

    def errorToSink(sink: Pipe[F, Byte, Unit]): P[Unit] =
      connectError(OutputStream(sink, (s: Stream[F, Unit]) => s.compile.drain))

    // Note: these operators can be merged with > with further type classes and implicit prioritization
    def !>#[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
      errorToFoldMonoid(pipe)

    def errorToFoldMonoid[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
      connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.foldMonoid))

    def !>?[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
      errorToVector(pipe)

    def errorToVector[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
      connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.toVector))

    def drainError[O](pipe: Pipe[F, Byte, O]): P[Unit] =
      connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.drain))

    def foldError[O, R](pipe: Pipe[F, Byte, O], init: R, fn: (R, O) => R): P[R] =
      connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.fold(init)(fn)))

    def !>(path: Path): P[Unit] =
      errorToFile(path)

    def errorToFile(path: Path): P[Unit] =
      connectError(OutputFile[F](path, append = false))

    def !>>(path: Path): P[Unit] =
      appendErrorToFile(path)

    def appendErrorToFile(path: Path): P[Unit] =
      connectError(OutputFile[F](path, append = true))
  }

  trait RedirectableInput[F[_], +P] {
    def connectInput(source: InputRedirection[F]): P

    def <(path: Path): P =
      fromFile(path)

    def fromFile(path: Path): P =
      connectInput(InputFile(path))

    def <(stream: Stream[F, Byte]): P =
      fromStream(stream, flushChunks = false)

    def !<(stream: Stream[F, Byte]): P =
      fromStream(stream, flushChunks = true)

    def fromStream(stream: Stream[F, Byte], flushChunks: Boolean): P =
      connectInput(InputStream(stream, flushChunks))
  }

  trait ProcessConfiguration[F[_], +P <: Process[F, _, _]] {
    this: Process[F, _, _] =>

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

    case class ProcessImplIOE[F[_], O, E](override val command: String,
                                          override val arguments: List[String],
                                          override val workingDirectory: Option[Path],
                                          override val environmentVariables: Map[String, String],
                                          override val removedEnvironmentVariables: Set[String],
                                          override val outputRedirection: OutputRedirection[F],
                                          override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                          override val errorRedirection: OutputRedirection[F],
                                          override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                          override val inputRedirection: InputRedirection[F])
                                         (override implicit val sync: Sync[F],
                                          override implicit val concurrent: Concurrent[F])
      extends Process[F, O, E] with ProcessConfiguration[F, ProcessImplIOE[F, O, E]] {

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIOE[F, O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplIO[F[_], O, E](override val command: String,
                                         override val arguments: List[String],
                                         override val workingDirectory: Option[Path],
                                         override val environmentVariables: Map[String, String],
                                         override val removedEnvironmentVariables: Set[String],
                                         override val outputRedirection: OutputRedirection[F],
                                         override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                         override val errorRedirection: OutputRedirection[F],
                                         override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                         override val inputRedirection: InputRedirection[F])
                                        (override implicit val sync: Sync[F],
                                         override implicit val concurrent: Concurrent[F])
      extends Process[F, O, E]
        with RedirectableError[F, ProcessImplIOE[F, O, *]]
        with ProcessConfiguration[F, ProcessImplIO[F, O, E]] {

      override def connectError[R <: OutputRedirection[F], RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RE]): ProcessImplIOE[F, O, RE] =
        ProcessImplIOE[F, O, RE](
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIO[F, O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplIE[F[_], O, E](override val command: String,
                                         override val arguments: List[String],
                                         override val workingDirectory: Option[Path],
                                         override val environmentVariables: Map[String, String],
                                         override val removedEnvironmentVariables: Set[String],
                                         override val outputRedirection: OutputRedirection[F],
                                         override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                         override val errorRedirection: OutputRedirection[F],
                                         override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                         override val inputRedirection: InputRedirection[F])
                                        (override implicit val sync: Sync[F],
                                         override implicit val concurrent: Concurrent[F])
      extends Process[F, O, E]
        with RedirectableOutput[F, ProcessImplIOE[F, *, E]]
        with ProcessConfiguration[F, ProcessImplIE[F, O, E]] {

      override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessImplIOE[F, RO, E] =
        ProcessImplIOE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIE[F, O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplOE[F[_], O, E](override val command: String,
                                         override val arguments: List[String],
                                         override val workingDirectory: Option[Path],
                                         override val environmentVariables: Map[String, String],
                                         override val removedEnvironmentVariables: Set[String],
                                         override val outputRedirection: OutputRedirection[F],
                                         override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                         override val errorRedirection: OutputRedirection[F],
                                         override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                         override val inputRedirection: InputRedirection[F])
                                        (override implicit val sync: Sync[F],
                                         override implicit val concurrent: Concurrent[F])
      extends Process[F, O, E]
        with RedirectableInput[F, ProcessImplIOE[F, O, E]]
        with ProcessConfiguration[F, ProcessImplOE[F, O, E]] {

      override def connectInput(source: InputRedirection[F]): ProcessImplIOE[F, O, E] =
        ProcessImplIOE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplOE[F, O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplO[F[_], O, E](override val command: String,
                                        override val arguments: List[String],
                                        override val workingDirectory: Option[Path],
                                        override val environmentVariables: Map[String, String],
                                        override val removedEnvironmentVariables: Set[String],
                                        override val outputRedirection: OutputRedirection[F],
                                        override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                        override val errorRedirection: OutputRedirection[F],
                                        override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                        override val inputRedirection: InputRedirection[F])
                                       (override implicit val sync: Sync[F],
                                        override implicit val concurrent: Concurrent[F])
      extends Process[F, O, E]
        with RedirectableError[F, ProcessImplOE[F, O, *]]
        with RedirectableInput[F, ProcessImplIO[F, O, E]]
        with ProcessConfiguration[F, ProcessImplO[F, O, E]] {

      override def connectInput(source: InputRedirection[F]): ProcessImplIO[F, O, E] =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override def connectError[R <: OutputRedirection[F], RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RE]): ProcessImplOE[F, O, RE] =
        ProcessImplOE[F, O, RE](
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplO[F, O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplE[F[_], O, E](override val command: String,
                                        override val arguments: List[String],
                                        override val workingDirectory: Option[Path],
                                        override val environmentVariables: Map[String, String],
                                        override val removedEnvironmentVariables: Set[String],
                                        override val outputRedirection: OutputRedirection[F],
                                        override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                        override val errorRedirection: OutputRedirection[F],
                                        override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                        override val inputRedirection: InputRedirection[F])
                                       (override implicit val sync: Sync[F],
                                        override implicit val concurrent: Concurrent[F])
      extends Process[F, O, E]
        with RedirectableInput[F, ProcessImplIO[F, O, E]]
        with RedirectableOutput[F, ProcessImplOE[F, *, E]]
        with ProcessConfiguration[F, ProcessImplE[F, O, E]] {

      override def connectInput(source: InputRedirection[F]): ProcessImplIO[F, O, E] =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessImplOE[F, RO, E] =
        ProcessImplOE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplE[F, O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImplI[F[_], O, E](override val command: String,
                                        override val arguments: List[String],
                                        override val workingDirectory: Option[Path],
                                        override val environmentVariables: Map[String, String],
                                        override val removedEnvironmentVariables: Set[String],
                                        override val outputRedirection: OutputRedirection[F],
                                        override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                        override val errorRedirection: OutputRedirection[F],
                                        override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                        override val inputRedirection: InputRedirection[F])
                                       (override implicit val sync: Sync[F],
                                        override implicit val concurrent: Concurrent[F])
      extends Process[F, O, E]
        with RedirectableOutput[F, ProcessImplIO[F, *, E]]
        with RedirectableError[F, ProcessImplIE[F, O, *]]
        with ProcessConfiguration[F, ProcessImplI[F, O, E]] {

      def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessImplIO[F, RO, E] =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override def connectError[R <: OutputRedirection[F], RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RE]): ProcessImplIE[F, O, RE] =
        ProcessImplIE[F, O, RE](
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplI[F, O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    case class ProcessImpl[F[_], O, E](override val command: String,
                                       override val arguments: List[String],
                                       override val workingDirectory: Option[Path],
                                       override val environmentVariables: Map[String, String],
                                       override val removedEnvironmentVariables: Set[String],
                                       override val outputRedirection: OutputRedirection[F],
                                       override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                       override val errorRedirection: OutputRedirection[F],
                                       override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                       override val inputRedirection: InputRedirection[F])
                                      (override implicit val sync: Sync[F],
                                       override implicit val concurrent: Concurrent[F])
      extends Process[F, O, E]
        with RedirectableOutput[F, ProcessImplO[F, *, E]]
        with RedirectableError[F, ProcessImplE[F, O, *]]
        with RedirectableInput[F, ProcessImplI[F, O, E]]
        with ProcessConfiguration[F, ProcessImpl[F, O, E]] {

      def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessImplO[F, RO, E] =
        ProcessImplO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override def connectError[R <: OutputRedirection[F], RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RE]): ProcessImplE[F, O, RE] =
        ProcessImplE[F, O, RE](
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override def connectInput(source: InputRedirection[F]): ProcessImplI[F, O, E] =
        ProcessImplI(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImpl[F, O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    def apply[F[_] : Sync : Concurrent](command: String, arguments: List[String]): ProcessImpl[F, Unit, Unit] =
      ProcessImpl[F, Unit, Unit](
        command,
        arguments,
        workingDirectory = None,
        environmentVariables = Map.empty,
        removedEnvironmentVariables = Set.empty,

        outputRedirection = StdOut[F](),
        runOutputStream = (_, _, _) => Applicative[F].unit,
        errorRedirection = StdOut[F](),
        runErrorStream = (_, _, _) => Applicative[F].unit,
        inputRedirection = StdIn[F]()
      )
  }

  trait ProcessResult[+O, +E] {
    val exitCode: ExitCode
    val output: O
    val error: E
  }

  // And a process runner

  trait ProcessRunner[F[_]] {
    def start[O, E](process: Process[F, O, E], blocker: Blocker): Resource[F, Fiber[F, ProcessResult[O, E]]]

    def start[O](processGroup: ProcessGroup[F, O], blocker: Blocker): Resource[F, Fiber[F, ProcessResult[O, Unit]]]
  }

  // Simple JVM implementation

  case class SimpleProcessResult[+O, +E](override val exitCode: ExitCode,
                                         override val output: O,
                                         override val error: E)
    extends ProcessResult[O, E]

  class JVMRunningProcess[F[_] : Sync, O, E](val nativeProcess: JvmProcess,
                                             val runningInput: Fiber[F, Unit],
                                             val runningOutput: Fiber[F, O],
                                             val runningError: Fiber[F, E]) {
    def isAlive: F[Boolean] =
      Sync[F].delay(nativeProcess.isAlive)

    def kill(): F[ProcessResult[O, E]] =
      Sync[F].delay(nativeProcess.destroyForcibly()) *> waitForExit()

    def terminate(): F[ProcessResult[O, E]] =
      Sync[F].delay(nativeProcess.destroy()) *> waitForExit()

    def waitForExit(): F[ProcessResult[O, E]] = {
      for {
        exitCode <- Sync[F].delay(nativeProcess.waitFor())
        _ <- runningInput.join
        output <- runningOutput.join
        error <- runningError.join
      } yield SimpleProcessResult(ExitCode(exitCode), output, error)
    }
  }

  class JVMRunningProcessGroup[F[_] : Sync, O](runningProcesses: List[JVMRunningProcess[F, _, _]],
                                               runningOutput: Fiber[F, O]) {

    def kill(): F[ProcessResult[O, Unit]] =
      runningProcesses.traverse(_.kill() *> Sync[F].unit) >> waitForExit()

    def terminate(): F[ProcessResult[O, Unit]] =
      runningProcesses.traverse(_.terminate() *> Sync[F].unit) >> waitForExit()

    def waitForExit(): F[ProcessResult[O, Unit]] =
      for {
        results <- runningProcesses.traverse(_.waitForExit().map(_.exitCode))
        lastOutput <- runningOutput.join
        lastExitCode = results.last
      } yield SimpleProcessResult(lastExitCode, lastOutput, ()) // TODO: combined result

  }

  class JVMProcessRunner[F[_] : Concurrent : ContextShift] extends ProcessRunner[F] {

    import JVMProcessRunner._

    // TODO: make run the default and start just a +fiber convenience stuff?
    override def start[O, E](process: Process[F, O, E], blocker: Blocker): Resource[F, Fiber[F, ProcessResult[O, E]]] = {
      val run = Concurrent[F].start(
        Sync[F].bracketCase(startProcess(process, blocker)) { runningProcess =>
          runningProcess.waitForExit()
        } {
          case (_, Completed) =>
            Applicative[F].unit
          case (_, Error(reason)) =>
            Sync[F].raiseError(reason)
          case (runningProcess, Canceled) =>
            runningProcess.terminate() >> Applicative[F].unit
        })

      Resource.make(run)(_.cancel)
    }

    private def startProcess[O, E](process: Process[F, O, E], blocker: Blocker): F[JVMRunningProcess[F, O, E]] = {
      val builder = withEnvironmentVariables(process,
        withWorkingDirectory(process,
          new ProcessBuilder((process.command :: process.arguments).asJava)))

      builder.redirectOutput(ouptutRedirectionToNative(process.outputRedirection))
      builder.redirectError(ouptutRedirectionToNative(process.errorRedirection))
      builder.redirectInput(inputRedirectionToNative(process.inputRedirection))

      for {
        nativeProcess <- Sync[F].delay(builder.start())
        nativeOutputStream <- Sync[F].delay(nativeProcess.getInputStream)
        nativeErrorStream <- Sync[F].delay(nativeProcess.getErrorStream)

        inputStream = runInputStream(process, nativeProcess, blocker)
        runningInput <- Concurrent[F].start(inputStream)
        runningOutput <- Concurrent[F].start(process.runOutputStream(nativeOutputStream, blocker, implicitly[ContextShift[F]]))
        runningError <- Concurrent[F].start(process.runErrorStream(nativeErrorStream, blocker, implicitly[ContextShift[F]]))
      } yield new JVMRunningProcess(nativeProcess, runningInput, runningOutput, runningError)
    }

    private def connectAndStartProcesses(firstProcess: Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]],
                                         previousOutput: Stream[F, Byte],
                                         remainingProcesses: List[Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]]],
                                         blocker: Blocker,
                                         startedProcesses: List[JVMRunningProcess[F, _, _]]): F[(List[JVMRunningProcess[F, _, _]], Stream[F, Byte])] = {
      startProcess(firstProcess.connectInput(InputStream(previousOutput, flushChunks = false)), blocker).flatMap { first =>
        first.runningOutput.join.flatMap { firstOutput =>
          remainingProcesses match {
            case nextProcess :: rest =>
              connectAndStartProcesses(nextProcess, firstOutput, rest, blocker, first :: startedProcesses)
            case Nil =>
              Applicative[F].pure((startedProcesses.reverse, firstOutput))
          }
        }
      }
    }

    def startProcessGroup[O](processGroup: ProcessGroup[F, O], blocker: Blocker): F[JVMRunningProcessGroup[F, O]] =
      for {
        first <- startProcess(processGroup.firstProcess, blocker)
        firstOutput <- first.runningOutput.join
        innerResult <- if (processGroup.innerProcesses.isEmpty) {
          Sync[F].pure((List.empty, firstOutput))
        } else {
          val inner = processGroup.innerProcesses.reverse
          connectAndStartProcesses(inner.head, firstOutput, inner.tail, blocker, List.empty)
        }
        (inner, lastInput) = innerResult
        last <- startProcess(processGroup.lastProcess.connectInput(InputStream(lastInput, flushChunks = false)), blocker)
      } yield new JVMRunningProcessGroup(
        (first :: inner) :+ last,
        last.runningOutput)

    def start[O](processGroup: ProcessGroup[F, O], blocker: Blocker): Resource[F, Fiber[F, ProcessResult[O, Unit]]] = {
      val run =
        Concurrent[F].start(
          Sync[F].bracketCase(startProcessGroup(processGroup, blocker)) { runningProcess =>
            runningProcess.waitForExit()
          } {
            case (_, Completed) =>
              Applicative[F].unit
            case (_, Error(reason)) =>
              Sync[F].raiseError(reason)
            case (runningProcess, Canceled) =>
              runningProcess.terminate() >> Applicative[F].unit
          }
        )

      Resource.make(run)(_.cancel)
    }

    private def runInputStream[O, E](process: Process[F, O, E], nativeProcess: JvmProcess, blocker: Blocker): F[Unit] = {
      process.inputRedirection match {
        case StdIn() => Applicative[F].unit
        case InputFile(_) => Applicative[F].unit
        case InputStream(stream, false) =>
          stream
            .observe(
              io.writeOutputStream[F](
                Sync[F].delay(nativeProcess.getOutputStream),
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
    def withWorkingDirectory[F[_], O, E](process: Process[F, O, E], builder: ProcessBuilder): ProcessBuilder =
      process.workingDirectory match {
        case Some(directory) => builder.directory(directory.toFile)
        case None => builder
      }

    def withEnvironmentVariables[F[_], O, E](process: Process[F, O, E], builder: ProcessBuilder): ProcessBuilder = {
      process.environmentVariables.foreach { case (name, value) =>
        builder.environment().put(name, value)
      }
      process.removedEnvironmentVariables.foreach { name =>
        builder.environment().remove(name)
      }
      builder
    }

    def ouptutRedirectionToNative[F[_]](outputRedirection: OutputRedirection[F]): ProcessBuilder.Redirect = {
      outputRedirection match {
        case StdOut() => ProcessBuilder.Redirect.INHERIT
        case OutputFile(path, false) => ProcessBuilder.Redirect.to(path.toFile)
        case OutputFile(path, true) => ProcessBuilder.Redirect.appendTo(path.toFile)
        case OutputStream(_, _, _) => ProcessBuilder.Redirect.PIPE
      }
    }

    def inputRedirectionToNative[F[_]](inputRedirection: InputRedirection[F]): ProcessBuilder.Redirect = {
      inputRedirection match {
        case StdIn() => ProcessBuilder.Redirect.INHERIT
        case InputFile(path) => ProcessBuilder.Redirect.from(path.toFile)
        case InputStream(_, _) => ProcessBuilder.Redirect.PIPE
      }
    }

    def writeAndFlushOutputStream[F[_] : Applicative : Sync](stream: java.io.OutputStream,
                                                             blocker: Blocker)
                                                            (implicit contextShift: ContextShift[F]): Pipe[F, Byte, Unit] =
      s => {
        Stream
          .bracket(Applicative[F].pure(stream))(os => Sync[F].delay(os.close()))
          .flatMap { os =>
            s.chunks.evalMap { chunk =>
              blocker.blockOn {
                Sync[F].delay {
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

  sealed trait OutputRedirection[F[_]]

  case class StdOut[F[_]]() extends OutputRedirection[F]

  case class OutputFile[F[_]](path: Path, append: Boolean) extends OutputRedirection[F]

  case class OutputStream[F[_], O, OR](pipe: Pipe[F, Byte, O],
                                       runner: Stream[F, O] => F[OR],
                                       chunkSize: Int = 8192) extends OutputRedirection[F]

  sealed trait InputRedirection[F[_]]

  case class StdIn[F[_]]() extends InputRedirection[F]

  case class InputFile[F[_]](path: Path) extends InputRedirection[F]

  case class InputStream[F[_]](stream: Stream[F, Byte], flushChunks: Boolean) extends InputRedirection[F]

  // Dependent typing helper
  trait OutputRedirectionType[F[_], R] {
    type Out

    def runner(of: R)(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[F]): F[Out]
  }

  object OutputRedirectionType {
    type Aux[F[_], R, O] = OutputRedirectionType[F, R] {
      type Out = O
    }

    implicit def outputRedirectionTypeOfStdOut[F[_] : Applicative]: Aux[F, StdOut[F], Unit] = new OutputRedirectionType[F, StdOut[F]] {
      override type Out = Unit

      override def runner(of: StdOut[F])(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[F]): F[Unit] = Applicative[F].unit
    }

    implicit def outputRedirectionTypeOfFile[F[_] : Applicative]: Aux[F, OutputFile[F], Unit] = new OutputRedirectionType[F, OutputFile[F]] {
      override type Out = Unit

      override def runner(of: OutputFile[F])(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[F]): F[Unit] = Applicative[F].unit
    }

    implicit def outputRedirectionTypeOfStream[F[_] : Applicative : Sync, O, OR]: Aux[F, OutputStream[F, O, OR], OR] = new OutputRedirectionType[F, OutputStream[F, O, OR]] {
      override type Out = OR

      override def runner(of: OutputStream[F, O, OR])(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[F]): F[OR] = {
        implicit val cs: ContextShift[F] = contextShift
        of.runner(
          io.readInputStream[F](
            Applicative[F].pure(nativeStream),
            of.chunkSize,
            closeAfterUse = true,
            blocker = blocker)
            .through(of.pipe))
      }
    }
  }

  // Piping processes together

  // TODO: how to bind error streams. compound error output indexed by process ids?

  trait ProcessGroup[F[_], O] {
    val firstProcess: Process[F, Stream[F, Byte], Unit]
    val innerProcesses: List[Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]]]
    val lastProcess: Process[F, O, Unit] with RedirectableInput[F, Process[F, O, Unit]]

    def start(blocker: Blocker)(implicit runner: ProcessRunner[F]): Resource[F, Fiber[F, ProcessResult[O, Unit]]] =
      runner.start(this, blocker)
  }

  trait PipingSupport[F[_]] {
    def |[O2, P2 <: Process[F, O2, Unit]](other: Process[F, O2, Unit] with RedirectableInput[F, P2] with RedirectableOutput[F, Process[F, *, Unit]]): ProcessGroup[F, O2]
  }

  object ProcessGroup {

    case class ProcessGroupImpl[F[_] : Sync, O](override val firstProcess: Process[F, Stream[F, Byte], Unit],
                                                override val innerProcesses: List[Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]]],
                                                override val lastProcess: Process[F, O, Unit] with RedirectableInput[F, Process[F, O, Unit]] with RedirectableOutput[F, Process[F, *, Unit]])
      extends ProcessGroup[F, O]
        with PipingSupport[F] {
      // TODO: redirection support

      override def |[O2, P2 <: Process[F, O2, Unit]](other: Process[F, O2, Unit] with RedirectableInput[F, P2] with RedirectableOutput[F, Process[F, *, Unit]]): ProcessGroup[F, O2] = {
        val channel = identity[Stream[F, Byte]] _ // TODO: customizable
        val pl1 = lastProcess.connectOutput(OutputStream(channel, (stream: Stream[F, Byte]) => Applicative[F].pure(stream)))
          .asInstanceOf[Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]]] // TODO: try to get rid of this
        copy(
          innerProcesses = pl1 :: innerProcesses,
          lastProcess = other
        )
      }
    }

  }

  // TODO: support any E?
  implicit class ProcessPiping[F[_] : Sync, O1, P1[_] <: Process[F, _, _]](process: Process[F, O1, Unit] with RedirectableOutput[F, P1]) {

    // TODO: do not allow pre-redirected IO
    def |[O2, P2 <: Process[F, O2, Unit]](other: Process[F, O2, Unit] with RedirectableInput[F, P2] with RedirectableOutput[F, Process[F, *, Unit]]): ProcessGroup.ProcessGroupImpl[F, O2] = {

      val channel = identity[Stream[F, Byte]] _ // TODO: customizable
      val p1 = process.connectOutput(OutputStream(channel, (stream: Stream[F, Byte]) => Applicative[F].pure(stream))).asInstanceOf[Process[F, Stream[F, Byte], Unit]] // TODO: try to get rid of this

      ProcessGroup.ProcessGroupImpl(
        p1,
        List.empty,
        other
      )
    }
  }

  object syntax {

    object cats {

      implicit class ProcessStringContextIO(ctx: StringContext)
                                           (implicit contextShift: ContextShift[IO]) {
        def proc(args: Any*): Process.ProcessImpl[IO, Unit, Unit] = {
          val staticParts = ctx.parts.map(Left.apply)
          val injectedParts = args.map(Right.apply)
          val parts = (injectedParts zip staticParts).flatMap { case (a, b) => List(b, a) }
          val words = parts.flatMap {
            case Left(value) => value.trim.split(' ')
            case Right(value) => List(value.toString)
          }.toList
          words match {
            case head :: remaining =>
              Process[IO](head, remaining)(Sync[IO], Concurrent[IO])
            case Nil =>
              throw new IllegalArgumentException(s"The proc interpolator needs at least a process name")
          }
        }
      }

    }

  }

}


// Trying out things

object Playground extends App {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val runner: ProcessRunner[IO] = new JVMProcessRunner[IO]
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  import TypedRedirection.syntax.cats._

  println("--1--")
  val input = Stream("This is a test string").through(text.utf8Encode)
  val output = text
    .utf8Decode[IO]
    .andThen(text.lines[IO])
    .andThen(_.evalMap(s => IO(println(s))))
  val process1 = (((Process[IO]("cat", List.empty) in home) > output) < input) without "TEMP"

  val program1 = Blocker[IO].use { blocker =>
    for {
      result <- process1.start(blocker).use(_.join)
    } yield result.exitCode
  }

  val result1 = program1.unsafeRunSync()
  println(result1)

  println("--2--")
  val sleep = "sleep 500"
  val process2 = proc"sh -c $sleep"
  val program2 = Blocker[IO].use { blocker =>
    for {
      result <- process2.start(blocker).use { runningProcess =>
        runningProcess.join.timeoutTo(2.second, IO.pure(SimpleProcessResult(ExitCode(100), (), ())))
      }
    } yield result.exitCode
  }
  val result2 = program2.unsafeRunSync()

  println(result2)

  println("--3--")
  val process3 = Process[IO]("sh", List("-c", "sleep 500"))
  val program3 = Blocker[IO].use { blocker =>
    for {
      result <- process3.start(blocker).use { runningProcess =>
        runningProcess.join
      }.timeoutTo(2.second, IO.pure(SimpleProcessResult(ExitCode(100), (), ())))
    } yield result.exitCode
  }
  val result3 = program3.unsafeRunSync()

  println(result3)

  println("--4--")

  def withInput[F[_], O, E, P <: Process[F, O, E] with ProcessConfiguration[F, P]](s: String)(process: Process[F, O, E] with RedirectableInput[F, P]): P = {
    val input = Stream("This is a test string").through(text.utf8Encode)
    process < input `with` ("hello" -> "world")
  }

  val process4 = withInput("Test string")(Process[IO]("cat", List.empty))

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

  val process5 = (Process[IO]("echo", List("Hello", "\n", "world")) in home) >? output5

  val program5 = Blocker[IO].use { blocker =>
    for {
      result <- process5.start(blocker).use(_.join)
    } yield result.output
  }
  val result5 = program5.unsafeRunSync()
  println(result5)

  println("--6--")
  val output6 = text
    .utf8Decode[IO]
    .andThen(text.lines[IO])

  val process6 = (Process[IO]("perl", List("-e", """print STDERR "Hello\nworld"""")) in home) !>? output6

  val program6 = Blocker[IO].use { blocker =>
    for {
      result <- process6.start(blocker).use(_.join)
    } yield result.error
  }
  val result6 = program6.unsafeRunSync()
  println(result6)

  println("--7--")
  val sink = text
    .utf8Decode[IO]
    .andThen(text.lines[IO])
    .andThen(_.evalMap(s => IO(println(s))))
  val process7 = Process[IO]("echo", List("Hello", "\n", "world")).errorToSink(sink) | Process[IO]("wc", List("-l")).errorToSink(sink)
  val program7 = Blocker[IO].use { blocker =>
    for {
      result <- process7.start(blocker).use(_.join)
    } yield result.output
  }
  val result7 = program7.unsafeRunSync()
  println(result7)

  println("--8--")
  val process8 = Process[IO]("ls", List("-hal")) | Process[IO]("sort", List.empty) | Process[IO]("uniq", List("-c"))
  val program8 = Blocker[IO].use { blocker =>
    for {
      result <- process8.start(blocker).use(_.join)
    } yield result.output
  }
  val result8 = program8.unsafeRunSync()
  println(result8)
}
