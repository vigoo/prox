package io.github.vigoo.prox

import java.nio.file.Path

import cats.Applicative
import cats.effect._
import cats.kernel.Monoid
import fs2._

/**
  * The capability to redirect the output of a process or a process group
  * @tparam F Effect type
  * @tparam P Self type without the [[RedirectableOutput]] capability
  */
trait RedirectableOutput[F[_], +P[_] <: ProcessLike[F]] {
  implicit val concurrent: Concurrent[F]

  /**
    * The low level operation to attach an output to a process
    *
    * Use one of the other methods of this trait for convenience. This is the place where the output type gets
    * calculated with a helper type class called [[OutputRedirectionType]] which implements the type level
    * computation for figuring out O.
    *
    * @param target Redirection target
    * @param outputRedirectionType Helper for dependent output type
    * @tparam R Output redirection type
    * @tparam O Output type
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def connectOutput[R <: OutputRedirection[F], O](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, O]): P[O]

  /**
    * Redirects the output to a sink.
    *
    * The process output type will be [[Unit]].
    * An alias for [[toSink]]
    *
    * @param sink Target sink
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def >(sink: Pipe[F, Byte, Unit]): P[Unit] =
    toSink(sink)

  /**
    * Redirects the output to a sink.
    *
    * The process output type will be [[Unit]].
    * An alias for [[>]]
    *
    * @param sink Target sink
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def toSink(sink: Pipe[F, Byte, Unit]): P[Unit] =
    connectOutput(OutputStream(sink, (s: Stream[F, Unit]) => s.compile.drain))

  /**
    * Redirects the output to a pipe and folds its output with a monoid instance.
    *
    * The process output type will be the same as the pipe's output type.
    * An alias for [[toFoldMonoid]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe. Must have a monoid instance.
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def >#[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
    toFoldMonoid(pipe)

  /**
    * Redirects the output to a pipe and folds its output with a monoid instance.
    *
    * The process output type will be the same as the pipe's output type.
    * An alias for [[>#]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe. Must have a monoid instance.
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def toFoldMonoid[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
    connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.foldMonoid))

  /**
    * Redirects the output to a pipe and collects its output to a vector
    *
    * The process output type will be a vector of the pipe's output type.
    * An alias for [[toVector]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def >?[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
    toVector(pipe)

  /**
    * Redirects the output to a pipe and collects its output to a vector
    *
    * The process output type will be a vector of the pipe's output type.
    * An alias for [[>?]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def toVector[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
    connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.toVector))

  /**
    * Redirects the output to a pipe and drains it regardless of its output type.
    *
    * The process output type will be [[Unit]].
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def drainOutput[O](pipe: Pipe[F, Byte, O]): P[Unit] =
    connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.drain))

  /**
    * Redirects the output to a pipe and folds it with a custom function.
    *
    * The process output type will be R.
    *
    * @param pipe Target pipe
    * @param init The initial value for the fold
    * @param fn The fold function
    * @tparam O Output type of the pipe
    * @tparam R Result type of the fold
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def foldOutput[O, R](pipe: Pipe[F, Byte, O], init: R, fn: (R, O) => R): P[R] =
    connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.fold(init)(fn)))

  /**
    * Redirects the output to a file natively
    *
    * An alias for [[toFile]]
    *
    * @param path Target file path
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def >(path: Path): P[Unit] =
    toFile(path)

  /**
    * Redirects the output to a file natively
    *
    * An alias for [[>]]
    *
    * @param path Target file path
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def toFile(path: Path): P[Unit] =
    connectOutput(OutputFile[F](path, append = false))

  /**
    * Redirects the output to a file natively in append mode
    *
    * An alias for [[appendToFile]]
    *
    * @param path Target file path
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def >>(path: Path): P[Unit] =
    appendToFile(path)

  /**
    * Redirects the output to a file natively in append mode
    *
    * An alias for [[>>]]
    *
    * @param path Target file path
    * @return Returns a new process or process group with its output redirected and its output redirection capability removed.
    */
  def appendToFile(path: Path): P[Unit] =
    connectOutput(OutputFile[F](path, append = true))
}

/**
  * The capability to redirect the error output of a process
  * @tparam F Effect type
  * @tparam P Self type without the [[RedirectableError]] capability
  */
trait RedirectableError[F[_], +P[_] <: Process[F, _, _]] {
  implicit val concurrent: Concurrent[F]

  /**
    * The low level operation to attach an error output to a process
    *
    * Use one of the other methods of this trait for convenience. This is the place where the output type gets
    * calculated with a helper type class called [[OutputRedirectionType]] which implements the type level
    * computation for figuring out E.
    *
    * @param target Redirection target
    * @param outputRedirectionType Helper for dependent error output type
    * @tparam R Error output redirection type
    * @tparam E Error output type
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def connectError[R <: OutputRedirection[F], E](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, E]): P[E]

  /**
    * Redirects the error output to a sink.
    *
    * The process error output type will be [[Unit]].
    * An alias for [[errorToSink]]
    *
    * @param sink Target sink
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def !>(sink: Pipe[F, Byte, Unit]): P[Unit] =
    errorToSink(sink)

  /**
    * Redirects the error output to a sink.
    *
    * The process error output type will be [[Unit]].
    * An alias for [[!>]]
    *
    * @param sink Target sink
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def errorToSink(sink: Pipe[F, Byte, Unit]): P[Unit] =
    connectError(OutputStream(sink, (s: Stream[F, Unit]) => s.compile.drain))

  /**
    * Redirects the error output to a pipe and folds its output with a monoid instance.
    *
    * The process error output type will be the same as the pipe's output type.
    * An alias for [[errorToFoldMonoid]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe. Must have a monoid instance.
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def !>#[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
    errorToFoldMonoid(pipe)

  /**
    * Redirects the error output to a pipe and folds its output with a monoid instance.
    *
    * The process error output type will be the same as the pipe's output type.
    * An alias for [[!>#]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe. Must have a monoid instance.
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def errorToFoldMonoid[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
    connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.foldMonoid))

  /**
    * Redirects the error output to a pipe and collects its output to a vector
    *
    * The process error output type will be a vector of the pipe's output type.
    * An alias for [[errorToVector]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def !>?[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
    errorToVector(pipe)

  /**
    * Redirects the error output to a pipe and collects its output to a vector
    *
    * The process error output type will be a vector of the pipe's output type.
    * An alias for [[!>?]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def errorToVector[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
    connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.toVector))

  /**
    * Redirects the error output to a pipe and drains it regardless of its output type.
    *
    * The process error output type will be [[Unit]].
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def drainError[O](pipe: Pipe[F, Byte, O]): P[Unit] =
    connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.drain))

  /**
    * Redirects the error output to a pipe and folds it with a custom function.
    *
    * The process error output type will be R.
    *
    * @param pipe Target pipe
    * @param init The initial value for the fold
    * @param fn The fold function
    * @tparam O Output type of the pipe
    * @tparam R Result type of the fold
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def foldError[O, R](pipe: Pipe[F, Byte, O], init: R, fn: (R, O) => R): P[R] =
    connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.fold(init)(fn)))

  /**
    * Redirects the error output to a file natively
    *
    * An alias for [[errorToFile]]
    *
    * @param path Target file path
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def !>(path: Path): P[Unit] =
    errorToFile(path)

  /**
    * Redirects the error output to a file natively
    *
    * An alias for [[!>]]
    *
    * @param path Target file path
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def errorToFile(path: Path): P[Unit] =
    connectError(OutputFile[F](path, append = false))

  /**
    * Redirects the error output to a file natively in append mode
    *
    * An alias for [[appendErrorToFile]]
    *
    * @param path Target file path
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def !>>(path: Path): P[Unit] =
    appendErrorToFile(path)

  /**
    * Redirects the error output to a file natively in append mode
    *
    * An alias for [[!>>]]
    *
    * @param path Target file path
    * @return Returns a new process with its error output redirected and its error redirection capability removed.
    */
  def appendErrorToFile(path: Path): P[Unit] =
    connectError(OutputFile[F](path, append = true))
}

/**
  * The capability to redirect all the error outputs simultaneously of a process group
  * @tparam F Effect type
  * @tparam P Self type without the [[RedirectableErrors]] capability
  */
trait RedirectableErrors[F[_], +P[_] <: ProcessGroup[F, _, _]] {
  implicit val concurrent: Concurrent[F]

  /** A more advanced interface for customizing the redirection per process */
  lazy val customizedPerProcess: RedirectableErrors.CustomizedPerProcess[F, P] =
    new RedirectableErrors.CustomizedPerProcess[F, P] {
      override implicit val concurrent: Concurrent[F] = RedirectableErrors.this.concurrent

      override def connectErrors[R <: GroupErrorRedirection[F], OR <: OutputRedirection[F], E](target: R)
                                                                                              (implicit groupErrorRedirectionType: GroupErrorRedirectionType.Aux[F, R, OR, E],
                                                                                               outputRedirectionType: OutputRedirectionType.Aux[F, OR, E]): P[E] =
        RedirectableErrors.this.connectErrors(target)
    }

  /**
    * The low level operation to attach an error output to all the processes in the group.
    *
    * Use one of the other methods of this trait or the advanced interface represented by [[customizedPerProcess]] for
    * convenience.
    *
    * This is the place where the process group's error output type gets calculated using the [[GroupErrorRedirectionType]]
    * and [[OutputRedirectionType]] type classes.
    *
    * @param target Redirection target
    * @param groupErrorRedirectionType Helper for dependent error output type
    * @param outputRedirectionType Helper for dependent error output type
    * @tparam R Error output grouped redirection type
    * @tparam OR Error output redirection type
    * @tparam E Error output type
    * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
    */
  def connectErrors[R <: GroupErrorRedirection[F], OR <: OutputRedirection[F], E](target: R)
                                                                                 (implicit groupErrorRedirectionType: GroupErrorRedirectionType.Aux[F, R, OR, E],
                                                                                  outputRedirectionType: OutputRedirectionType.Aux[F, OR, E]): P[E]

  /**
    * Redirects the error outputs to a sink.
    *
    * The process error output type will be [[Unit]].
    * An alias for [[errorsToSink]]
    *
    * @param sink Target sink
    * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
    */
  def !>(sink: Pipe[F, Byte, Unit]): P[Unit] =
    errorsToSink(sink)

  /**
    * Redirects the error outputs to a sink.
    *
    * The process error output type will be [[Unit]].
    * An alias for [[!>]]
    *
    * @param sink Target sink
    * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
    */
  def errorsToSink(sink: Pipe[F, Byte, Unit]): P[Unit] =
    customizedPerProcess.errorsToSink(_ => sink)

  /**
    * Redirects the error outputs to a pipe and folds its output with a monoid instance.
    *
    * The process error output type will be the same as the pipe's output type.
    * An alias for [[errorsToFoldMonoid]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe. Must have a monoid instance.
    * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
    */
  def !>#[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
    errorsToFoldMonoid(pipe)

  /**
    * Redirects the error outputs to a pipe and folds its output with a monoid instance.
    *
    * The process error output type will be the same as the pipe's output type.
    * An alias for [[!>#]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe. Must have a monoid instance.
    * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
    */
  def errorsToFoldMonoid[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
    customizedPerProcess.errorsToFoldMonoid(_ => pipe)

  /**
    * Redirects the error outputs to a pipe and collects its output to a vector
    *
    * The process error output type will be a vector of the pipe's output type.
    * An alias for [[errorsToVector]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe
    * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
    */
  def !>?[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
    errorsToVector(pipe)

  /**
    * Redirects the error outputs to a pipe and collects its output to a vector
    *
    * The process error output type will be a vector of the pipe's output type.
    * An alias for [[!>?]]
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe
    * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
    */
  def errorsToVector[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
    customizedPerProcess.errorsToVector(_ => pipe)

  /**
    * Redirects the error outputs to a pipe and drains it regardless of its output type.
    *
    * The process error output type will be [[Unit]].
    *
    * @param pipe Target pipe
    * @tparam O Output type of the pipe
    * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
    */
  def drainErrors[O](pipe: Pipe[F, Byte, O]): P[Unit] =
    customizedPerProcess.drainErrors(_ => pipe)

  /**
    * Redirects the error outputs to a pipe and folds it with a custom function.
    *
    * The process error output type will be R.
    *
    * @param pipe Target pipe
    * @param init The initial value for the fold
    * @param fn The fold function
    * @tparam O Output type of the pipe
    * @tparam R Result type of the fold
    * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
    */
  def foldErrors[O, R](pipe: Pipe[F, Byte, O], init: R, fn: (R, O) => R): P[R] =
    customizedPerProcess.foldErrors(_ => pipe, init, fn)
}

object RedirectableErrors {

  /**
    * Advanced version of the [[RedirectableErrors]] interface enabling per-process customizations.
    *
    * @tparam F Effect type
    * @tparam P Self type without [[RedirectableErrors]]
    */
  trait CustomizedPerProcess[F[_], +P[_] <: ProcessGroup[F, _, _]] {
    implicit val concurrent: Concurrent[F]

    /** See [[RedirectableErrors.connectErrors]] */
    def connectErrors[R <: GroupErrorRedirection[F], OR <: OutputRedirection[F], E](target: R)
                                                                                   (implicit groupErrorRedirectionType: GroupErrorRedirectionType.Aux[F, R, OR, E],
                                                                                    outputRedirectionType: OutputRedirectionType.Aux[F, OR, E]): P[E]

    /**
      * Redirects the error outputs to a sink.
      *
      * The process error output type will be [[Unit]].
      *
      * @param sinkFn Function to get a sink for each process in the group
      * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
      */
    def errorsToSink(sinkFn: Process[F, _, _] => Pipe[F, Byte, Unit]): P[Unit] =
      connectErrors(AllCaptured(sinkFn, (s: Stream[F, Unit]) => s.compile.drain))

    /**
      * Redirects the error outputs to a pipe and folds its output with a monoid instance.
      *
      * The process error output type will be the same as the pipe's output type.
      *
      * @param pipeFn A function to get a pipe for each process in the group
      * @tparam O Output type of the pipe. Must have a monoid instance.
      * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
      */
    def errorsToFoldMonoid[O: Monoid](pipeFn: Process[F, _, _] => Pipe[F, Byte, O]): P[O] =
      connectErrors(AllCaptured(pipeFn, (s: Stream[F, O]) => s.compile.foldMonoid))

    /**
      * Redirects the error outputs to a pipe and collects its output to a vector
      *
      * The process error output type will be a vector of the pipe's output type.
      *
      * @param pipeFn A function to get a pipe for each process in the group
      * @tparam O Output type of the pipe
      * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
      */
    def errorsToVector[O](pipeFn: Process[F, _, _] => Pipe[F, Byte, O]): P[Vector[O]] =
      connectErrors(AllCaptured(pipeFn, (s: Stream[F, O]) => s.compile.toVector))

    /**
      * Redirects the error outputs to a pipe and drains it regardless of its output type.
      *
      * The process error output type will be [[Unit]].
      *
      * @param pipeFn A function to get a pipe for each process in the group
      * @tparam O Output type of the pipe
      * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
      */
    def drainErrors[O](pipeFn: Process[F, _, _] => Pipe[F, Byte, O]): P[Unit] =
      connectErrors(AllCaptured(pipeFn, (s: Stream[F, O]) => s.compile.drain))

    /**
      * Redirects the error outputs to a pipe and folds it with a custom function.
      *
      * The process error output type will be R.
      *
      * @param pipeFn A function to get a pipe for each process in the group
      * @param init The initial value for the fold
      * @param fn The fold function
      * @tparam O Output type of the pipe
      * @tparam R Result type of the fold
      * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
      */
    def foldErrors[O, R](pipeFn: Process[F, _, _] => Pipe[F, Byte, O], init: R, fn: (R, O) => R): P[R] =
      connectErrors(AllCaptured(pipeFn, (s: Stream[F, O]) => s.compile.fold(init)(fn)))

    /**
      * Redirects the error outputs to one file per process
      *
      * The process error output type will be [[Unit]].
      *
      * @param pathFn A function to get a file path for each process in the group
      * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
      */
    def errorsToFile(pathFn: Process[F, _, _] => Path): P[Unit] =
      connectErrors(AllToFile[F](pathFn, append = false))

    /**
      * Redirects the error outputs to one file per process in append mode
      *
      * The process error output type will be [[Unit]].
      *
      * @param pathFn A function to get a file path for each process in the group
      * @return Returns a new process group with all the error streams redirected and the error redirection capability removed.
      */
    def appendErrorsToFile(pathFn: Process[F, _, _] => Path): P[Unit] =
      connectErrors(AllToFile[F](pathFn, append = true))
  }

}

/**
  * The capability to redirect the input of a process or process group
  * @tparam F
  * @tparam P
  */
trait RedirectableInput[F[_], +P <: ProcessLike[F]] {
  /**
    * The low level method to attach an input to a process or process group.
    *
    * Use the other methods in this trait for convenience.
    *
    * @param source Redirection source
    * @return A new process or process group with the input redirected and the input redirection capability removed.
    */
  def connectInput(source: InputRedirection[F]): P

  /**
    * Feed the process input from a file natively.
    *
    * An alias for [[fromFile]].
    *
    * @param path Path to the file
    * @return A new process or process group with the input redirected and the input redirection capability removed.
    */
  def <(path: Path): P =
    fromFile(path)

  /**
    * Feed the process input from a file natively.
    *
    * An alias for [[<]].
    *
    * @param path Path to the file
    * @return A new process or process group with the input redirected and the input redirection capability removed.
    */
  def fromFile(path: Path): P =
    connectInput(InputFile(path))

  /**
    * Feed the process input from a byte stream.
    *
    * An alias for [[fromStream]].
    *
    * @param stream Input stream
    * @return A new process or process group with the input redirected and the input redirection capability removed.
    */
  def <(stream: Stream[F, Byte]): P =
    fromStream(stream, flushChunks = false)

  /**
    * Feed the process input from a byte stream with flushing per chunks enabled.
    *
    * An alias for [[fromStream]].
    *
    * @param stream Input stream
    * @return A new process or process group with the input redirected and the input redirection capability removed.
    */
  def !<(stream: Stream[F, Byte]): P =
    fromStream(stream, flushChunks = true)

  /**
    * Feed the process input from a byte stream.
    *
    * An alias for [[<]] and [[!<]].
    *
    * @param stream Input stream
    * @param flushChunks Flush the process input stream after each chunk
    * @return A new process or process group with the input redirected and the input redirection capability removed.
    */
  def fromStream(stream: Stream[F, Byte], flushChunks: Boolean): P =
    connectInput(InputStream(stream, flushChunks))
}

/** Supported output redirection types. Should not be used directly, see the redirection traits instead. */
sealed trait OutputRedirection[F[_]]

case class StdOut[F[_]]() extends OutputRedirection[F]

case class OutputFile[F[_]](path: Path, append: Boolean) extends OutputRedirection[F]

case class OutputStream[F[_], O, OR](pipe: Pipe[F, Byte, O],
                                     runner: Stream[F, O] => F[OR],
                                     chunkSize: Int = 8192) extends OutputRedirection[F]


/** Supported process group error redirection types. Should not be used directly, see the redirection traits instead. */
sealed trait GroupErrorRedirection[F[_]]

case class AllToStdErr[F[_]]() extends GroupErrorRedirection[F]

case class AllToFile[F[_]](pathFn: Process[F, _, _] => Path, append: Boolean) extends GroupErrorRedirection[F]

case class AllCaptured[F[_], O, OR](pipeFn: Process[F, _, _] => Pipe[F, Byte, O],
                                    runner: Stream[F, O] => F[OR],
                                    chunkSize: Int = 8192) extends GroupErrorRedirection[F]

/** Supported input redirection types. Should not be used directly, see the redirection traits instead. */
sealed trait InputRedirection[F[_]]

case class StdIn[F[_]]() extends InputRedirection[F]

case class InputFile[F[_]](path: Path) extends InputRedirection[F]

case class InputStream[F[_]](stream: Stream[F, Byte], flushChunks: Boolean) extends InputRedirection[F]

/** Helper type class for output and error redirection dependent typing */
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

/** Helper type class for process group error redirection dependent typing */
trait GroupErrorRedirectionType[F[_], R] {
  type Out
  type OutputR <: OutputRedirection[F]

  def toOutputRedirectionType(redir: R, process: Process[F, _, _]): OutputR
}

object GroupErrorRedirectionType {
  type Aux[F[_], R, OR, O] = GroupErrorRedirectionType[F, R] {
    type Out = O
    type OutputR = OR
  }

  implicit def groupErrorRedirectionTypeOfStdErr[F[_] : Applicative]: Aux[F, AllToStdErr[F], StdOut[F], Unit] = new GroupErrorRedirectionType[F, AllToStdErr[F]] {
    override type Out = Unit
    override type OutputR = StdOut[F]

    override def toOutputRedirectionType(redir: AllToStdErr[F], process: Process[F, _, _]): StdOut[F] = StdOut[F]()
  }

  implicit def groupErrorRedirectionTypeOfFile[F[_] : Applicative]: Aux[F, AllToFile[F], OutputFile[F], Unit] = new GroupErrorRedirectionType[F, AllToFile[F]] {
    override type Out = Unit
    override type OutputR = OutputFile[F]

    override def toOutputRedirectionType(redir: AllToFile[F], process: Process[F, _, _]): OutputFile[F] = OutputFile[F](redir.pathFn(process), redir.append)
  }

  implicit def groupErrorRedirectionTypeOfStream[F[_] : Applicative : Sync, O, OR]: Aux[F, AllCaptured[F, O, OR], OutputStream[F, O, OR], OR] = new GroupErrorRedirectionType[F, AllCaptured[F, O, OR]] {
    override type Out = OR
    override type OutputR = OutputStream[F, O, OR]

    override def toOutputRedirectionType(redir: AllCaptured[F, O, OR], process: Process[F, _, _]): OutputStream[F, O, OR] = OutputStream(redir.pipeFn(process), redir.runner, redir.chunkSize)
  }
}