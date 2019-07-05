package io.github.vigoo.prox

import java.io.OutputStream
import java.lang
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.Applicative
import cats.effect._
import cats.effect.syntax.all._
import cats.implicits._
import fs2._

import scala.concurrent.blocking
import scala.language.higherKinds

/** Wrapper for input streams that enables flushing the process pipe stream after each chunk of bytes.
  *
  * This is useful for interactive processes, as the pipe stream has a 8K buffer on it.
  *
  * @param stream The input stream for the process
  */
final case class FlushChunks[F[_]](stream: Stream[F, Byte])

/** Base trait for input redirection handlers */
trait ProcessInputSource[F[_]] extends ProcessIO[F, Byte, Unit]

/** Type class for creating input redirection handlers
  *
  * By providing an instance of this type class, a value of type From can be used
  * to redirect the input channel of a process to.
  *
  * @tparam From Type to be used as an input source
  */
trait CanBeProcessInputSource[F[_], From] {
  def apply(from: From): ProcessInputSource[F]
}

/** Instances of the [[CanBeProcessInputSource]] type class
  *
  * There are instances for the following types:
  *
  * - [[java.nio.file.Path]] to use a file as input
  * - [[fs2.Stream]] to use a byte stream as input
  */
object CanBeProcessInputSource {
  implicit def pathAsSource[F[_]: Concurrent]: CanBeProcessInputSource[F, Path] =
    (path: Path) => new FileSource[F](path)

  implicit def streamAsSource[F[_] : Concurrent]: CanBeProcessInputSource[F, Stream[F, Byte]] =
    (source: Stream[F, Byte]) => new InputStreamingSource(source)

  implicit def pureStreamAsSource[F[_] : Concurrent]: CanBeProcessInputSource[F, Stream[Pure, Byte]] =
    (source: Stream[Pure, Byte]) => new InputStreamingSource(source)

  implicit def flushControlledStreamAsSource[F[_] : Concurrent]: CanBeProcessInputSource[F, FlushChunks[F]] =
    (source: FlushChunks[F]) => new FlushControlledInputStreamingSource(source)
}

/** Default input source representing no redirection */
class StdIn[F[_] : Concurrent] extends ProcessInputSource[F] {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process, blocker: Blocker)(implicit contextShift: ContextShift[F]): Stream[F, Byte] =
    Stream.empty

  override def run(stream: Stream[F, Byte])(implicit contextShift: ContextShift[F]): F[Fiber[F, Unit]] =
    Concurrent[F].start(Applicative[F].unit)
}

/** Input source implementation of using a file as input
  *
  * @param path Path to the file
  */
class FileSource[F[_] : Concurrent](path: Path) extends ProcessInputSource[F] {
  override def toRedirect: Redirect = Redirect.from(path.toFile)

  override def connect(systemProcess: lang.Process, blocker: Blocker)(implicit contextShift: ContextShift[F]): Stream[F, Byte] =
    Stream.empty

  override def run(stream: Stream[F, Byte])(implicit contextShift: ContextShift[F]): F[Fiber[F, Unit]] =
    Concurrent[F].start(Applicative[F].unit)
}

/** Input source implementation of using a byte stream as input
  *
  * @param source The input byte stream
  */
class InputStreamingSource[F[_] : Concurrent](source: Stream[F, Byte]) extends ProcessInputSource[F] {
  override def toRedirect: Redirect = Redirect.PIPE

  override def connect(systemProcess: lang.Process, blocker: Blocker)(implicit contextShift: ContextShift[F]): Stream[F, Byte] = {
    source.observe(
      io.writeOutputStream[F](
        Sync[F].delay(systemProcess.getOutputStream),
        closeAfterUse = true,
        blocker = blocker))
  }

  override def run(stream: Stream[F, Byte])(implicit contextShift: ContextShift[F]): F[Fiber[F, Unit]] =
    Concurrent[F].start(stream.compile.drain)
}

/** Input source implementation that calls 'flush' after each chunk of bytes. Useful for interactive
  * communication with processes.
  *
  * @param source The input byte stream
  */
class FlushControlledInputStreamingSource[F[_] : Concurrent](source: FlushChunks[F]) extends ProcessInputSource[F] {
  override def toRedirect: Redirect = Redirect.PIPE

  override def connect(systemProcess: lang.Process, blocker: Blocker)(implicit contextShift: ContextShift[F]): Stream[F, Byte] = {
    source.stream.observe(
      writeAndFlushOutputStream(
        systemProcess.getOutputStream,
        blocker = blocker))
  }

  override def run(stream: Stream[F, Byte])(implicit contextShift: ContextShift[F]): F[Fiber[F, Unit]] =
    Concurrent[F].start(stream.compile.drain)

  def writeAndFlushOutputStream(stream: OutputStream,
                                blocker: Blocker)
                               (implicit contextShift: ContextShift[F]): Pipe[F, Byte, Unit] = s => {
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