package io.github.vigoo.prox

import java.io.InputStream
import java.lang
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.Monoid
import cats.effect.{Concurrent, ContextShift, Fiber, IO}
import fs2._

import scala.concurrent.ExecutionContext

/** Base trait for output redirection handlers
  *
  * @tparam O The redirection stream element type
  * @tparam R Result type we get by running the redirection stream
  */
trait ProcessOutputTarget[O, R] extends ProcessIO[O, R]

/** Base trait for error redirection handlers
  *
  * @tparam O The redirection stream element type
  * @tparam R Result type we get by running the redirection stream
  */
trait ProcessErrorTarget[O, R] extends ProcessIO[O, R]

/** Type class for creating output redirection handlers
  *
  * @tparam To Type to be used as a target
  */
trait CanBeProcessOutputTarget[To] {
  /** Output stream element type */
  type Out
  /** Result type of running the output stream */
  type OutResult

  def apply(to: To): ProcessOutputTarget[Out, OutResult]
}

/** Wraps a pipe to modify how the stream is executed
  *
  * If a pipe used as an output or error target is wrapped by [[Drain]], the stream will be executed
  * by [[fs2.Stream.CompileOps.drain]] and the result type will be [[Unit]].
  *
  * @param pipe The pipe to wrap
  * @tparam O   Stream element type
  */
case class Drain[O](pipe: Pipe[IO, Byte, O])

/** Wraps a pipe to modify how the stream is executed
  *
  * If a pipe used as an output or error target is wrapped by [[ToVector]], the stream will be executed
  * by [[fs2.Stream.CompileOps.toVector]] and the result type will be a [[Vector]] of its element
  * type.
  *
  * @param pipe The pipe to wrap
  * @tparam O   Stream element type
  */
case class ToVector[O](pipe: Pipe[IO, Byte, O])

/** Wraps the pipe to modify how the stream is executed
  *
  * If a pipe used as an output or error target is wrapped by [[Fold]], the stream will be executed
  * by [[fs2.Stream.CompileOps.fold]] and the result type will be the result type of the provided
  * fold function.
  *
  * @param pipe The pipe to wrap
  * @param init Initial value for the fold
  * @param f    The fold function
  * @tparam O   Stream element type
  * @tparam R   Fold result type
  */
case class Fold[O, R](pipe: Pipe[IO, Byte, O], init: R, f: (R, O) => R)

trait LowPriorityCanBeProcessOutputTarget {
  implicit def pipeAsTarget[Out]: CanBeProcessOutputTarget.Aux[Pipe[IO, Byte, Out], Out, Vector[Out]] =
    CanBeProcessOutputTarget.create((pipe: Pipe[IO, Byte, Out]) => new OutputStreamingTarget(pipe) with ProcessOutputTarget[Out, Vector[Out]] {
      override def run(stream: Stream[IO, Out])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Vector[Out]]] =
        Concurrent[IO].start(stream.compile.toVector)
    })
}

/** Instances of the [[CanBeProcessOutputTarget]] type class
  *
  * There are instances for the following types:
  *
  *  - [[java.nio.file.Path]] to redirect the output to a file
  *  - [[fs2.Sink]] to redirect the output to a sink. The result type is [[Unit]].
  *  - [[fs2.Pipe]] if the pipe's output element type is a [[cats.Monoid]]. The result type is its element type.
  *  - [[fs2.Pipe]] if the pipe's output element type is not a [[cats.Monoid]]. The result type is a [[Vector]] of its element type.
  *  - [[Drain]]
  *  - [[ToVector]]
  *  - [[Fold]]
  */
object CanBeProcessOutputTarget extends LowPriorityCanBeProcessOutputTarget {
  type Aux[To, Out0, OutResult0] = CanBeProcessOutputTarget[To] {
    type Out = Out0
    type OutResult = OutResult0
  }

  def create[To, Out0, OutResult0](fn: To => ProcessOutputTarget[Out0, OutResult0]): Aux[To, Out0, OutResult0] =
    new CanBeProcessOutputTarget[To] {
      override type Out = Out0
      override type OutResult = OutResult0
      override def apply(to: To): ProcessOutputTarget[Out, OutResult] = fn(to)
    }

  implicit val pathAsTarget: Aux[Path, Byte, Unit] =
    create((path: Path) => new FileTarget(path))

  implicit def sinkAsTarget: Aux[Pipe[IO, Byte, Unit], Unit, Unit] =
    create((pipe: Pipe[IO, Byte, Unit]) => new OutputStreamingTarget(pipe) with ProcessOutputTarget[Unit, Unit] {

      override def run(stream: Stream[IO, Unit])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Unit]] =
        Concurrent[IO].start(stream.compile.drain)
    })

  implicit def monoidPipeAsTarget[Out](implicit monoid: Monoid[Out]): Aux[Pipe[IO, Byte, Out], Out, Out] =
    create((pipe: Pipe[IO, Byte, Out]) => new OutputStreamingTarget(pipe) with ProcessOutputTarget[Out, Out] {
      override def run(stream: Stream[IO, Out])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Out]] = {
        Concurrent[IO].start(stream.compile.foldMonoid)
      }
    })

  implicit def ignorePipeAsOutputTarget[Out]: Aux[Drain[Out], Out, Unit] =
    create((ignore: Drain[Out]) => new OutputStreamingTarget(ignore.pipe) with ProcessOutputTarget[Out, Unit] {
      override def run(stream: Stream[IO, Out])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Unit]] = {
        Concurrent[IO].start(stream.compile.drain)
      }
    })

  implicit def logPipeAsOutputTarget[Out]: Aux[ToVector[Out], Out, Vector[Out]] =
    create((log: ToVector[Out]) => new OutputStreamingTarget(log.pipe) with ProcessOutputTarget[Out, Vector[Out]] {
      override def run(stream: Stream[IO, Out])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Vector[Out]]] = {
        Concurrent[IO].start(stream.compile.toVector)
      }
    })

  implicit def foldPipeAsOutputTarget[Out, Res]: Aux[Fold[Out, Res], Out, Res] =
    create((fold: Fold[Out, Res]) => new OutputStreamingTarget(fold.pipe) with ProcessOutputTarget[Out, Res] {
      override def run(stream: Stream[IO, Out])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Res]] = {
        Concurrent[IO].start(stream.compile.fold(fold.init)(fold.f))
      }
    })
}

/** Type class for creating error redirection handlers
  *
  * @tparam To Type to be used as a target
  */
trait CanBeProcessErrorTarget[To] {
  type Err
  type ErrResult
  def apply(to: To): ProcessErrorTarget[Err, ErrResult]
}

trait LowPriorityCanBeProcessErrorTarget {
  implicit def pipeAsErrorTarget[Err]: CanBeProcessErrorTarget.Aux[Pipe[IO, Byte, Err], Err, Vector[Err]] =
    CanBeProcessErrorTarget.create((pipe: Pipe[IO, Byte, Err]) => new ErrorStreamingTarget(pipe) with ProcessErrorTarget[Err, Vector[Err]] {
      override def run(stream: Stream[IO, Err])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Vector[Err]]] = {
        Concurrent[IO].start(stream.compile.toVector)
      }
    })
}

/** Instances of the [[CanBeProcessErrorTarget]] type class
  *
  * There are instances for the following types:
  *
  *  - [[java.nio.file.Path]] to redirect the error channel to a file
  *  - [[fs2.Sink]] to redirect the error channel to a sink. The result type is [[Unit]].
  *  - [[fs2.Pipe]] if the pipe's output element type is a [[cats.Monoid]]. The result type is its element type.
  *  - [[fs2.Pipe]] if the pipe's output element type is not a [[cats.Monoid]]. The result type is a [[Vector]] of its element type.
  *  - [[Drain]]
  *  - [[ToVector]]
  *  - [[Fold]]
  */
object CanBeProcessErrorTarget extends LowPriorityCanBeProcessErrorTarget {
  type Aux[To, Err0, ErrResult0] = CanBeProcessErrorTarget[To] {
    type Err = Err0
    type ErrResult = ErrResult0
  }

  def create[To, Err0, ErrResult0](fn: To => ProcessErrorTarget[Err0, ErrResult0]): Aux[To, Err0, ErrResult0] =
    new CanBeProcessErrorTarget[To] {
      override type Err = Err0
      override type ErrResult = ErrResult0
      override def apply(to: To): ProcessErrorTarget[Err, ErrResult] = fn(to)
    }

  implicit val pathAsErrorTarget: Aux[Path, Byte, Unit] =
    create((path: Path) => new FileTarget(path))

  implicit def monoidPipeAsErrorTarget[Err](implicit monoid: Monoid[Err]): Aux[Pipe[IO, Byte, Err], Err, Err] =
    create((pipe: Pipe[IO, Byte, Err]) => new ErrorStreamingTarget(pipe) with ProcessErrorTarget[Err, Err] {
      override def run(stream: Stream[IO, Err])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Err]] = {
        Concurrent[IO].start(stream.compile.foldMonoid)
      }
    })

  implicit def logPipeAsErrorTarget[Err]: Aux[ToVector[Err], Err, Vector[Err]] =
    create((log: ToVector[Err]) => new ErrorStreamingTarget(log.pipe) with ProcessErrorTarget[Err, Vector[Err]] {
      override def run(stream: Stream[IO, Err])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Vector[Err]]] = {
        Concurrent[IO].start(stream.compile.toVector)
      }
    })

  implicit def ignorePipeAsErrorTarget[Err]: Aux[Drain[Err], Err, Unit] =
    create((ignore: Drain[Err]) => new ErrorStreamingTarget(ignore.pipe) with ProcessErrorTarget[Err, Unit] {
      override def run(stream: Stream[IO, Err])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Unit]] = {
        Concurrent[IO].start(stream.compile.drain)
      }
    })

  implicit def foldPipeAsErrorTarget[Err, Res]: Aux[Fold[Err, Res], Err, Res] =
    create((fold: Fold[Err, Res]) => new ErrorStreamingTarget(fold.pipe) with ProcessErrorTarget[Err, Res] {
      override def run(stream: Stream[IO, Err])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Res]] = {
        Concurrent[IO].start(stream.compile.fold(fold.init)(fold.f))
      }
    })
}

/** Default implementation of [[ProcessOutputTarget]] representing no redirection */
object StdOut extends ProcessOutputTarget[Byte, Unit] {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process, blockingExecutionContext: ExecutionContext)(implicit contextShift: ContextShift[IO]): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(IO.unit)
}

/** Default implementation of [[ProcessErrorTarget]] representing no redirection */
object StdError extends ProcessErrorTarget[Byte, Unit] {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process, blockingExecutionContext: ExecutionContext)(implicit contextShift: ContextShift[IO]): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(IO.unit)
}

/** Output/error target implementation for using a file as the target
  *
  * @param path Path to the file to be written
  */
class FileTarget(path: Path) extends ProcessOutputTarget[Byte, Unit] with ProcessErrorTarget[Byte, Unit] {
  override def toRedirect: Redirect = Redirect.to(path.toFile)

  override def connect(systemProcess: lang.Process, blockingExecutionContext: ExecutionContext)(implicit contextShift: ContextShift[IO]): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(IO.unit)
}

/** Base class for output/error target implementations using a stream pipe as the target
  *
  * @param target    Target stream
  * @param chunkSize Chunk size
  * @tparam Out      Stream output element type
  */
abstract class OutputStreamingTargetBase[Out](target: Pipe[IO, Byte, Out], chunkSize: Int = 4096) {

  def toRedirect: Redirect = Redirect.PIPE
  def connect(systemProcess: lang.Process, blockingExecutionContext: ExecutionContext)(implicit contextShift: ContextShift[IO]): Stream[IO, Out] = {
    io.readInputStream[IO](
      getStream(systemProcess),
      chunkSize,
      closeAfterUse = true,
      blockingExecutionContext = blockingExecutionContext)
      .through(target)
  }

  def getStream(systemProcess: java.lang.Process): IO[InputStream]
}

/** Output target implementation using a stream pipe as the target
  *
  * @param target    Target stream
  * @tparam Out      Stream output element type
  */
abstract class OutputStreamingTarget[Out](target: Pipe[IO, Byte, Out])
  extends OutputStreamingTargetBase(target) {

  override def getStream(systemProcess: java.lang.Process): IO[InputStream] =
    IO(systemProcess.getInputStream)
}

/** Error target implementation using a stream pipe as the target
  *
  * @param target    Target stream
  * @tparam Err      Stream output element type
  */
abstract class ErrorStreamingTarget[Err](target: Pipe[IO, Byte, Err])
  extends OutputStreamingTargetBase(target) {

  override def getStream(systemProcess: java.lang.Process): IO[InputStream] =
    IO(systemProcess.getErrorStream)
}
