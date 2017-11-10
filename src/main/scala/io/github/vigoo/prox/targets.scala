package io.github.vigoo.prox

import java.io.InputStream
import java.lang
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.effect.IO
import fs2.{Pipe, Stream, io}

import scala.concurrent.ExecutionContext

trait ProcessOutputTarget extends ProcessIO

trait ProcessErrorTarget extends ProcessIO

trait CanBeProcessOutputTarget[To] {
  def target(to: To): ProcessOutputTarget
}

trait CanBeProcessErrorTarget[To] {
  def target(to: To): ProcessErrorTarget
}

object StdOut extends ProcessOutputTarget {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty
}

object StdError extends ProcessErrorTarget {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty
}

class FileTarget(path: Path) extends ProcessOutputTarget with ProcessErrorTarget {
  override def toRedirect: Redirect = Redirect.to(path.toFile)

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty
}

abstract class OutputStreamingTargetBase(target: Pipe[IO, Byte, Byte], chunkSize: Int = 4096) extends ProcessIO {
  override def toRedirect: Redirect = Redirect.PIPE

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] = {
    io.readInputStreamAsync[IO](getStream(systemProcess), chunkSize, closeAfterUse = true)
      .through(target)
  }

  def getStream(systemProcess: java.lang.Process): IO[InputStream]
}

class OutputStreamingTarget[O](target: Pipe[IO, Byte, Byte]) extends OutputStreamingTargetBase(target) with ProcessOutputTarget {
  override def getStream(systemProcess: java.lang.Process): IO[InputStream] =
    IO {
      systemProcess.getInputStream
    }
}

class ErrorStreamingTarget[O](target: Pipe[IO, Byte, Byte]) extends OutputStreamingTargetBase(target) with ProcessErrorTarget {
  override def getStream(systemProcess: java.lang.Process): IO[InputStream] =
    IO {
      systemProcess.getErrorStream
    }
}
