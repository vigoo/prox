package io.github.vigoo.prox

import java.io.InputStream
import java.lang
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.effect.IO
import fs2.{Pipe, Sink, Stream, io}

import scala.concurrent.ExecutionContext

trait ProcessOutputTarget[O] extends ProcessIO[O]

trait ProcessErrorTarget[O] extends ProcessIO[O]

trait CanBeProcessOutputTarget[To] {
  type Out
  def apply(to: To): ProcessOutputTarget[Out]
}

object CanBeProcessOutputTarget {
  type Aux[To, Out0] = CanBeProcessOutputTarget[To] { type Out = Out0 }

  def create[To, Out0](fn: To => ProcessOutputTarget[Out0]): Aux[To, Out0] =
    new CanBeProcessOutputTarget[To] {
      override type Out = Out0
      override def apply(to: To): ProcessOutputTarget[Out] = fn(to)
    }

  implicit val pathAsTarget: Aux[Path, Byte] =
    create((path: Path) => new FileTarget(path))

  implicit def pipeAsTarget[Out]: Aux[Pipe[IO, Byte, Out], Out] =
    create((pipe: Pipe[IO, Byte, Out]) => new OutputStreamingTarget(pipe))
}

trait CanBeProcessErrorTarget[To] {
  type Err
  def apply(to: To): ProcessErrorTarget[Err]
}

object CanBeProcessErrorTarget {
  type Aux[To, Err0] = CanBeProcessErrorTarget[To] { type Err = Err0 }

  def create[To, Err0](fn: To => ProcessErrorTarget[Err0]): Aux[To, Err0] =
    new CanBeProcessErrorTarget[To] {
      override type Err = Err0
      override def apply(to: To): ProcessErrorTarget[Err] = fn(to)
    }

  implicit val pathAsErrorTarget: Aux[Path, Byte] =
    create((path: Path) => new FileTarget(path))

  implicit def pipeAsErrorTarget[Err]: Aux[Pipe[IO, Byte, Err], Err] =
    create((pipe: Pipe[IO, Byte, Err]) => new ErrorStreamingTarget(pipe))
}

object StdOut extends ProcessOutputTarget[Byte] {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty
}

object StdError extends ProcessErrorTarget[Byte] {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty
}

class FileTarget(path: Path) extends ProcessOutputTarget[Byte] with ProcessErrorTarget[Byte] {
  override def toRedirect: Redirect = Redirect.to(path.toFile)

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty
}

abstract class OutputStreamingTargetBase[Out](target: Pipe[IO, Byte, Out], chunkSize: Int = 4096)
  extends ProcessOutputTarget[Out] {

  override def toRedirect: Redirect = Redirect.PIPE
  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Out] = {
    io.readInputStreamAsync[IO](getStream(systemProcess), chunkSize, closeAfterUse = true)
      .through(target)
  }

  def getStream(systemProcess: java.lang.Process): IO[InputStream]
}

class OutputStreamingTarget[Out](target: Pipe[IO, Byte, Out]) extends OutputStreamingTargetBase(target) with ProcessOutputTarget[Out] {
  override def getStream(systemProcess: java.lang.Process): IO[InputStream] =
    IO {
      systemProcess.getInputStream
    }
}

class ErrorStreamingTarget[Err](target: Pipe[IO, Byte, Err]) extends OutputStreamingTargetBase(target) with ProcessErrorTarget[Err] {
  override def getStream(systemProcess: java.lang.Process): IO[InputStream] =
    IO {
      systemProcess.getErrorStream
    }
}
