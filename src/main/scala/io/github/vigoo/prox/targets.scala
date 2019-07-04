package io.github.vigoo.prox

import java.io.InputStream
import java.lang
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.{Applicative, Monad, Monoid}
import cats.effect._
import cats.effect.syntax.all._
import cats.implicits._
import fs2._

import scala.concurrent.ExecutionContext

/** Base trait for output redirection handlers
  *
  * @tparam O The redirection stream element type
  * @tparam R Result type we get by running the redirection stream
  */
trait ProcessOutputTarget[F[_], O, R] extends ProcessIO[F, O, R]

/** Base trait for error redirection handlers
  *
  * @tparam O The redirection stream element type
  * @tparam R Result type we get by running the redirection stream
  */
trait ProcessErrorTarget[F[_], O, R] extends ProcessIO[F, O, R]

/** Type class for creating output redirection handlers
  *
  * @tparam To Type to be used as a target
  */
trait CanBeProcessOutputTarget[F[_], To] {
  /** Output stream element type */
  type Out
  /** Result type of running the output stream */
  type OutResult

  def apply(to: To): ProcessOutputTarget[F, Out, OutResult]
}

/** Wraps a pipe to modify how the stream is executed
  *
  * If a pipe used as an output or error target is wrapped by [[Drain]], the stream will be executed
  * by [[fs2.Stream.CompileOps.drain]] and the result type will be [[Unit]].
  *
  * @param pipe The pipe to wrap
  * @tparam O Stream element type
  */
case class Drain[F[_], O](pipe: Pipe[F, Byte, O])

/** Wraps a pipe to modify how the stream is executed
  *
  * If a pipe used as an output or error target is wrapped by [[ToVector]], the stream will be executed
  * by [[fs2.Stream.CompileOps.toVector]] and the result type will be a [[Vector]] of its element
  * type.
  *
  * @param pipe The pipe to wrap
  * @tparam O Stream element type
  */
case class ToVector[F[_], O](pipe: Pipe[F, Byte, O])

/** Wraps the pipe to modify how the stream is executed
  *
  * If a pipe used as an output or error target is wrapped by [[Fold]], the stream will be executed
  * by [[fs2.Stream.CompileOps.fold]] and the result type will be the result type of the provided
  * fold function.
  *
  * @param pipe The pipe to wrap
  * @param init Initial value for the fold
  * @param f    The fold function
  * @tparam O Stream element type
  * @tparam R Fold result type
  */
case class Fold[F[_], O, R](pipe: Pipe[F, Byte, O], init: R, f: (R, O) => R)

trait LowPriorityCanBeProcessOutputTarget {
  implicit def pipeAsTarget[F[_] : Concurrent, Out]: CanBeProcessOutputTarget.Aux[F, Pipe[F, Byte, Out], Out, Vector[Out]] =
    CanBeProcessOutputTarget.create((pipe: Pipe[F, Byte, Out]) => new OutputStreamingTarget(pipe) with ProcessOutputTarget[F, Out, Vector[Out]] {
      override def run(stream: Stream[F, Out])(implicit contextShift: ContextShift[F]): F[Fiber[F, Vector[Out]]] =
        Concurrent[F].start(stream.compile.toVector)
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
  type Aux[F[_], To, Out0, OutResult0] = CanBeProcessOutputTarget[F, To] {
    type Out = Out0
    type OutResult = OutResult0
  }

  def create[F[_], To, Out0, OutResult0](fn: To => ProcessOutputTarget[F, Out0, OutResult0]): Aux[F, To, Out0, OutResult0] =
    new CanBeProcessOutputTarget[F, To] {
      override type Out = Out0
      override type OutResult = OutResult0

      override def apply(to: To): ProcessOutputTarget[F, Out, OutResult] = fn(to)
    }

  implicit def pathAsTarget[F[_] : Concurrent]: Aux[F, Path, Byte, Unit] =
    create((path: Path) => new FileTarget[F](path))

  implicit def sinkAsTarget[F[_] : Concurrent]: Aux[F, Pipe[F, Byte, Unit], Unit, Unit] =
    create((pipe: Pipe[F, Byte, Unit]) => new OutputStreamingTarget(pipe) with ProcessOutputTarget[F, Unit, Unit] {

      override def run(stream: Stream[F, Unit])(implicit contextShift: ContextShift[F]): F[Fiber[F, Unit]] =
        Concurrent[F].start(stream.compile.drain)
    })

  implicit def monoidPipeAsTarget[F[_] : Concurrent, Out: Monoid]: Aux[F, Pipe[F, Byte, Out], Out, Out] =
    create((pipe: Pipe[F, Byte, Out]) => new OutputStreamingTarget(pipe) with ProcessOutputTarget[F, Out, Out] {
      override def run(stream: Stream[F, Out])(implicit contextShift: ContextShift[F]): F[Fiber[F, Out]] = {
        Concurrent[F].start(stream.compile.foldMonoid)
      }
    })

  implicit def ignorePipeAsOutputTarget[F[_] : Concurrent, Out]: Aux[F, Drain[F, Out], Out, Unit] =
    create((ignore: Drain[F, Out]) => new OutputStreamingTarget(ignore.pipe) with ProcessOutputTarget[F, Out, Unit] {
      override def run(stream: Stream[F, Out])(implicit contextShift: ContextShift[F]): F[Fiber[F, Unit]] = {
        Concurrent[F].start(stream.compile.drain)
      }
    })

  implicit def logPipeAsOutputTarget[F[_] : Concurrent, Out]: Aux[F, ToVector[F, Out], Out, Vector[Out]] =
    create((log: ToVector[F, Out]) => new OutputStreamingTarget(log.pipe) with ProcessOutputTarget[F, Out, Vector[Out]] {
      override def run(stream: Stream[F, Out])(implicit contextShift: ContextShift[F]): F[Fiber[F, Vector[Out]]] = {
        Concurrent[F].start(stream.compile.toVector)
      }
    })

  implicit def foldPipeAsOutputTarget[F[_] : Concurrent, Out, Res]: Aux[F, Fold[F, Out, Res], Out, Res] =
    create((fold: Fold[F, Out, Res]) => new OutputStreamingTarget(fold.pipe) with ProcessOutputTarget[F, Out, Res] {
      override def run(stream: Stream[F, Out])(implicit contextShift: ContextShift[F]): F[Fiber[F, Res]] = {
        Concurrent[F].start(stream.compile.fold(fold.init)(fold.f))
      }
    })
}

/** Type class for creating error redirection handlers
  *
  * @tparam To Type to be used as a target
  */
trait CanBeProcessErrorTarget[F[_], To] {
  type Err
  type ErrResult

  def apply(to: To): ProcessErrorTarget[F, Err, ErrResult]
}

trait LowPriorityCanBeProcessErrorTarget {
  implicit def pipeAsErrorTarget[F[_] : Concurrent, Err]: CanBeProcessErrorTarget.Aux[F, Pipe[F, Byte, Err], Err, Vector[Err]] =
    CanBeProcessErrorTarget.create((pipe: Pipe[F, Byte, Err]) => new ErrorStreamingTarget(pipe) with ProcessErrorTarget[F, Err, Vector[Err]] {
      override def run(stream: Stream[F, Err])(implicit contextShift: ContextShift[F]): F[Fiber[F, Vector[Err]]] = {
        Concurrent[F].start(stream.compile.toVector)
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
  type Aux[F[_], To, Err0, ErrResult0] = CanBeProcessErrorTarget[F, To] {
    type Err = Err0
    type ErrResult = ErrResult0
  }

  def create[F[_], To, Err0, ErrResult0](fn: To => ProcessErrorTarget[F, Err0, ErrResult0]): Aux[F, To, Err0, ErrResult0] =
    new CanBeProcessErrorTarget[F, To] {
      override type Err = Err0
      override type ErrResult = ErrResult0

      override def apply(to: To): ProcessErrorTarget[F, Err, ErrResult] = fn(to)
    }

  implicit def pathAsErrorTarget[F[_] : Concurrent]: Aux[F, Path, Byte, Unit] =
    create((path: Path) => new FileTarget[F](path))

  implicit def monoidPipeAsErrorTarget[F[_] : Concurrent, Err: Monoid]: Aux[F, Pipe[F, Byte, Err], Err, Err] =
    create((pipe: Pipe[F, Byte, Err]) => new ErrorStreamingTarget(pipe) with ProcessErrorTarget[F, Err, Err] {
      override def run(stream: Stream[F, Err])(implicit contextShift: ContextShift[F]): F[Fiber[F, Err]] = {
        Concurrent[F].start(stream.compile.foldMonoid)
      }
    })

  implicit def logPipeAsErrorTarget[F[_] : Concurrent, Err]: Aux[F, ToVector[F, Err], Err, Vector[Err]] =
    create((log: ToVector[F, Err]) => new ErrorStreamingTarget(log.pipe) with ProcessErrorTarget[F, Err, Vector[Err]] {
      override def run(stream: Stream[F, Err])(implicit contextShift: ContextShift[F]): F[Fiber[F, Vector[Err]]] = {
        Concurrent[F].start(stream.compile.toVector)
      }
    })

  implicit def ignorePipeAsErrorTarget[F[_] : Concurrent, Err]: Aux[F, Drain[F, Err], Err, Unit] =
    create((ignore: Drain[F, Err]) => new ErrorStreamingTarget(ignore.pipe) with ProcessErrorTarget[F, Err, Unit] {
      override def run(stream: Stream[F, Err])(implicit contextShift: ContextShift[F]): F[Fiber[F, Unit]] = {
        Concurrent[F].start(stream.compile.drain)
      }
    })

  implicit def foldPipeAsErrorTarget[F[_] : Concurrent, Err, Res]: Aux[F, Fold[F, Err, Res], Err, Res] =
    create((fold: Fold[F, Err, Res]) => new ErrorStreamingTarget(fold.pipe) with ProcessErrorTarget[F, Err, Res] {
      override def run(stream: Stream[F, Err])(implicit contextShift: ContextShift[F]): F[Fiber[F, Res]] = {
        Concurrent[F].start(stream.compile.fold(fold.init)(fold.f))
      }
    })
}

/** Default implementation of [[ProcessOutputTarget]] representing no redirection */
class StdOut[F[_] : Concurrent] extends ProcessOutputTarget[F, Byte, Unit] {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process, blocker: Blocker)(implicit contextShift: ContextShift[F]): Stream[F, Byte] =
    Stream.empty

  override def run(stream: Stream[F, Byte])(implicit contextShift: ContextShift[F]): F[Fiber[F, Unit]] =
    Concurrent[F].start(Applicative[F].unit)
}

/** Default implementation of [[ProcessErrorTarget]] representing no redirection */
class StdError[F[_] : Concurrent] extends ProcessErrorTarget[F, Byte, Unit] {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process, blocker: Blocker)(implicit contextShift: ContextShift[F]): Stream[F, Byte] =
    Stream.empty

  override def run(stream: Stream[F, Byte])(implicit contextShift: ContextShift[F]): F[Fiber[F, Unit]] =
    Concurrent[F].start(Applicative[F].unit)
}

/** Output/error target implementation for using a file as the target
  *
  * @param path Path to the file to be written
  */
class FileTarget[F[_] : Concurrent](path: Path) extends ProcessOutputTarget[F, Byte, Unit] with ProcessErrorTarget[F, Byte, Unit] {
  override def toRedirect: Redirect = Redirect.to(path.toFile)

  override def connect(systemProcess: lang.Process, blocker: Blocker)(implicit contextShift: ContextShift[F]): Stream[F, Byte] =
    Stream.empty

  override def run(stream: Stream[F, Byte])(implicit contextShift: ContextShift[F]): F[Fiber[F, Unit]] =
    Concurrent[F].start(Applicative[F].unit)
}

/** Base class for output/error target implementations using a stream pipe as the target
  *
  * @param target    Target stream
  * @param chunkSize Chunk size
  * @tparam Out Stream output element type
  */
abstract class OutputStreamingTargetBase[F[_] : Sync, Out](target: Pipe[F, Byte, Out], chunkSize: Int = 8192) {

  def toRedirect: Redirect = Redirect.PIPE

  def connect(systemProcess: lang.Process, blocker: Blocker)(implicit contextShift: ContextShift[F]): Stream[F, Out] = {
    io.readInputStream[F](
      getStream(systemProcess),
      chunkSize,
      closeAfterUse = true,
      blocker = blocker)
      .through(target)
  }

  def getStream(systemProcess: java.lang.Process): F[InputStream]
}

/** Output target implementation using a stream pipe as the target
  *
  * @param target Target stream
  * @tparam Out Stream output element type
  */
abstract class OutputStreamingTarget[F[_] : Sync, Out](target: Pipe[F, Byte, Out])
  extends OutputStreamingTargetBase(target) {

  override def getStream(systemProcess: java.lang.Process): F[InputStream] =
    Sync[F].delay(systemProcess.getInputStream)
}

/** Error target implementation using a stream pipe as the target
  *
  * @param target Target stream
  * @tparam Err Stream output element type
  */
abstract class ErrorStreamingTarget[F[_] : Sync, Err](target: Pipe[F, Byte, Err])
  extends OutputStreamingTargetBase(target) {

  override def getStream(systemProcess: java.lang.Process): F[InputStream] =
    Sync[F].delay(systemProcess.getErrorStream)
}
