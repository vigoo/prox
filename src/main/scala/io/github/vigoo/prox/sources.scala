package io.github.vigoo.prox

import java.lang
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.effect.IO
import fs2._

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

/** Base trait for input redirection handlers */
trait ProcessInputSource extends ProcessIO[Byte, Unit]

/** Type class for creating input redirection handlers
  *
  * By providing an instance of this type class, a value of type From can be used
  * to redirect the input channel of a process to.
  *
  * @tparam From Type to be used as an input source
  */
trait CanBeProcessInputSource[From] {
  def apply(from: From): ProcessInputSource
}

/** Instances of the [[CanBeProcessInputSource]] type class
  *
  * There are instances for the following types:
  *
  * * [[java.nio.file.Path]] to use a file as input
  * * [[fs2.Stream]] to use a byte stream as input
  */
object CanBeProcessInputSource {
  implicit val pathAsSource: CanBeProcessInputSource[Path] =
    (path: Path) => new FileSource(path)

  implicit def streamAsSource: CanBeProcessInputSource[Stream[IO, Byte]] =
    (source: Stream[IO, Byte]) => new InputStreamingSource(source)

  implicit def pureStreamAsSource: CanBeProcessInputSource[Stream[Pure, Byte]] =
    (source: Stream[Pure, Byte]) => new InputStreamingSource(source)
}

/** Default input source representing no redirection */
object StdIn extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit executionContext: ExecutionContext): IO[IO[Unit]] =
    IO(IO(()))
}

/** Input source implementation of using a file as input
  *
  * @param path Path to the file
  */
class FileSource(path: Path) extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.from(path.toFile)
  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit executionContext: ExecutionContext): IO[IO[Unit]] =
    IO(IO(()))
}

/** Input source implementation of using a byte stream as input
  *
  * @param source The input byte stream
  */
class InputStreamingSource(source: Stream[IO, Byte]) extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.PIPE
  override def connect(systemProcess: lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte] = {
    source.observe(io.writeOutputStreamAsync[IO](IO { systemProcess.getOutputStream }, closeAfterUse = true))
  }

  override def run(stream: Stream[IO, Byte])(implicit executionContext: ExecutionContext): IO[IO[Unit]] =
    async.start(stream.run)
}