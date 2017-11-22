package io.github.vigoo.prox

import java.lang
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.effect.IO
import fs2._

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

trait ProcessInputSource extends ProcessIO[Byte, Unit]

trait CanBeProcessInputSource[From] {
  def apply(from: From): ProcessInputSource
}

object CanBeProcessInputSource {
  implicit val pathAsSource: CanBeProcessInputSource[Path] =
    (path: Path) => new FileSource(path)

  implicit def streamAsSource: CanBeProcessInputSource[Stream[IO, Byte]] =
    (source: Stream[IO, Byte]) => new InputStreamingSource(source)

  implicit def pureStreamAsSource: CanBeProcessInputSource[Stream[Pure, Byte]] =
    (source: Stream[Pure, Byte]) => new InputStreamingSource(source)
}

object StdIn extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit executionContext: ExecutionContext): IO[IO[Unit]] =
    IO(IO(()))
}

class FileSource(path: Path) extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.from(path.toFile)
  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit executionContext: ExecutionContext) =
    IO(IO(()))
}

class InputStreamingSource(source: Stream[IO, Byte]) extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.PIPE
  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] = {
    source.observe(io.writeOutputStreamAsync[IO](IO { systemProcess.getOutputStream }, closeAfterUse = true))
  }

  override def run(stream: Stream[IO, Byte])(implicit executionContext: ExecutionContext): IO[IO[Unit]] =
    async.start(stream.run)
}