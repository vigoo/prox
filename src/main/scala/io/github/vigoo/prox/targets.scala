package io.github.vigoo.prox

import java.io.InputStream
import java.lang
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.Monoid
import cats.effect.IO
import fs2._

import scala.concurrent.ExecutionContext

trait ProcessOutputTarget[O, R] extends ProcessIO[O, R]

trait ProcessErrorTarget[O, R] extends ProcessIO[O, R]

trait CanBeProcessOutputTarget[To] {
  type Out
  type OutResult
  def apply(to: To): ProcessOutputTarget[Out, OutResult]
}

case class Ignore[O](pipe: Pipe[IO, Byte, O])

case class Log[O](pipe: Pipe[IO, Byte, O])

case class Fold[O, R](pipe: Pipe[IO, Byte, O], init: R, f: ((R, O) => R))

trait LowPriorityCanBeProcessOutputTarget {
  implicit def pipeAsTarget[Out]: CanBeProcessOutputTarget.Aux[Pipe[IO, Byte, Out], Out, Vector[Out]] =
    CanBeProcessOutputTarget.create((pipe: Pipe[IO, Byte, Out]) => new OutputStreamingTarget(pipe) with ProcessOutputTarget[Out, Vector[Out]] {
      override def run(stream: Stream[IO, Out])(implicit executionContext: ExecutionContext): IO[IO[Vector[Out]]] =
        async.start(stream.runLog)
    })
}

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

      override def run(stream: Stream[IO, Unit])(implicit executionContext: ExecutionContext): IO[IO[Unit]] =
        async.start(stream.run)
    })

  implicit def monoidPipeAsTarget[Out](implicit monoid: Monoid[Out]): Aux[Pipe[IO, Byte, Out], Out, Out] =
    create((pipe: Pipe[IO, Byte, Out]) => new OutputStreamingTarget(pipe) with ProcessOutputTarget[Out, Out] {
      override def run(stream: Stream[IO, Out])(implicit executionContext: ExecutionContext): IO[IO[Out]] = {
        async.start(stream.runFoldMonoid)
      }
    })

  implicit def ignorePipeAsOutputTarget[Out]: Aux[Ignore[Out], Out, Unit] =
    create((ignore: Ignore[Out]) => new OutputStreamingTarget(ignore.pipe) with ProcessOutputTarget[Out, Unit] {
      override def run(stream: Stream[IO, Out])(implicit executionContext: ExecutionContext): IO[IO[Unit]] = {
        async.start(stream.run)
      }
    })

  implicit def logPipeAsOutputTarget[Out]: Aux[Log[Out], Out, Vector[Out]] =
    create((log: Log[Out]) => new OutputStreamingTarget(log.pipe) with ProcessOutputTarget[Out, Vector[Out]] {
      override def run(stream: Stream[IO, Out])(implicit executionContext: ExecutionContext): IO[IO[Vector[Out]]] = {
        async.start(stream.runLog)
      }
    })

  implicit def foldPipeAsOutputTarget[Out, Res]: Aux[Fold[Out, Res], Out, Res] =
    create((fold: Fold[Out, Res]) => new OutputStreamingTarget(fold.pipe) with ProcessOutputTarget[Out, Res] {
      override def run(stream: Stream[IO, Out])(implicit executionContext: ExecutionContext): IO[IO[Res]] = {
        async.start(stream.runFold(fold.init)(fold.f))
      }
    })
}

trait CanBeProcessErrorTarget[To] {
  type Err
  type ErrResult
  def apply(to: To): ProcessErrorTarget[Err, ErrResult]
}

trait LowPriorityCanBeProcessErrorTarget {
  implicit def pipeAsErrorTarget[Err]: CanBeProcessErrorTarget.Aux[Pipe[IO, Byte, Err], Err, Vector[Err]] =
    CanBeProcessErrorTarget.create((pipe: Pipe[IO, Byte, Err]) => new ErrorStreamingTarget(pipe) with ProcessErrorTarget[Err, Vector[Err]] {
      override def run(stream: Stream[IO, Err])(implicit executionContext: ExecutionContext): IO[IO[Vector[Err]]] = {
        async.start(stream.runLog)
      }
    })
}

object CanBeProcessErrorTarget {
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
      override def run(stream: Stream[IO, Err])(implicit executionContext: ExecutionContext): IO[IO[Err]] = {
        async.start(stream.runFoldMonoid)
      }
    })

  implicit def logPipeAsErrorTarget[Err]: Aux[Log[Err], Err, Vector[Err]] =
    create((log: Log[Err]) => new ErrorStreamingTarget(log.pipe) with ProcessErrorTarget[Err, Vector[Err]] {
      override def run(stream: Stream[IO, Err])(implicit executionContext: ExecutionContext): IO[IO[Vector[Err]]] = {
        async.start(stream.runLog)
      }
    })

  implicit def ignorePipeAsErrorTarget[Err]: Aux[Ignore[Err], Err, Unit] =
    create((ignore: Ignore[Err]) => new ErrorStreamingTarget(ignore.pipe) with ProcessErrorTarget[Err, Unit] {
      override def run(stream: Stream[IO, Err])(implicit executionContext: ExecutionContext): IO[IO[Unit]] = {
        async.start(stream.run)
      }
    })

  implicit def foldPipeAsErrorTarget[Err, Res]: Aux[Fold[Err, Res], Err, Res] =
    create((fold: Fold[Err, Res]) => new ErrorStreamingTarget(fold.pipe) with ProcessErrorTarget[Err, Res] {
      override def run(stream: Stream[IO, Err])(implicit executionContext: ExecutionContext): IO[IO[Res]] = {
        async.start(stream.runFold(fold.init)(fold.f))
      }
    })
}

object StdOut extends ProcessOutputTarget[Byte, Unit] {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit executionContext: ExecutionContext): IO[IO[Unit]] =
    IO(IO(()))
}

object StdError extends ProcessErrorTarget[Byte, Unit] {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit executionContext: ExecutionContext): IO[IO[Unit]] =
    IO(IO())
}

class FileTarget(path: Path) extends ProcessOutputTarget[Byte, Unit] with ProcessErrorTarget[Byte, Unit] {
  override def toRedirect: Redirect = Redirect.to(path.toFile)

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit executionContext: ExecutionContext): IO[IO[Unit]] =
    IO(IO(()))
}

abstract class OutputStreamingTargetBase[Out](target: Pipe[IO, Byte, Out], chunkSize: Int = 4096) {

  def toRedirect: Redirect = Redirect.PIPE
  def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Out] = {
    io.readInputStreamAsync[IO](getStream(systemProcess), chunkSize, closeAfterUse = true)
      .through(target)
  }

  def getStream(systemProcess: java.lang.Process): IO[InputStream]
}

abstract class OutputStreamingTarget[Out](target: Pipe[IO, Byte, Out])
  extends OutputStreamingTargetBase(target) {

  override def getStream(systemProcess: java.lang.Process): IO[InputStream] =
    IO(systemProcess.getInputStream)
}

class ErrorStreamingTarget[Err](target: Pipe[IO, Byte, Err])
  extends OutputStreamingTargetBase(target) {

  override def getStream(systemProcess: java.lang.Process): IO[InputStream] =
    IO(systemProcess.getErrorStream)
}
