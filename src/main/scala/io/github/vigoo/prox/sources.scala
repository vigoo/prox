package io.github.vigoo.prox

import java.io.OutputStream
import java.lang
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.effect.{Concurrent, ContextShift, Fiber, IO}
import fs2._

import scala.concurrent.{blocking, ExecutionContext}
import scala.language.higherKinds

/** Wrapper for input streams that enables flushing the process pipe stream after each chunk of bytes.
  *
  * This is useful for interactive processes, as the pipe stream has a 8K buffer on it.
  *
  * @param stream The input stream for the process
  */
final case class FlushChunks[F[_]](stream: Stream[F, Byte])

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
  * - [[java.nio.file.Path]] to use a file as input
  * - [[fs2.Stream]] to use a byte stream as input
  */
object CanBeProcessInputSource {
  implicit val pathAsSource: CanBeProcessInputSource[Path] =
    (path: Path) => new FileSource(path)

  implicit def streamAsSource: CanBeProcessInputSource[Stream[IO, Byte]] =
    (source: Stream[IO, Byte]) => new InputStreamingSource(source)

  implicit def pureStreamAsSource: CanBeProcessInputSource[Stream[Pure, Byte]] =
    (source: Stream[Pure, Byte]) => new InputStreamingSource(source)

  implicit def flushControlledStreamAsSource: CanBeProcessInputSource[FlushChunks[IO]] =
    (source: FlushChunks[IO]) => new FlushControlledInputStreamingSource(source)
}

/** Default input source representing no redirection */
object StdIn extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process, blockingExecutionContext: ExecutionContext)(implicit contextShift: ContextShift[IO]): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(IO.unit)
}

/** Input source implementation of using a file as input
  *
  * @param path Path to the file
  */
class FileSource(path: Path) extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.from(path.toFile)

  override def connect(systemProcess: lang.Process, blockingExecutionContext: ExecutionContext)(implicit contextShift: ContextShift[IO]): Stream[IO, Byte] =
    Stream.empty

  override def run(stream: Stream[IO, Byte])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(IO.unit)
}

/** Input source implementation of using a byte stream as input
  *
  * @param source The input byte stream
  */
class InputStreamingSource(source: Stream[IO, Byte]) extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.PIPE

  override def connect(systemProcess: lang.Process, blockingExecutionContext: ExecutionContext)(implicit contextShift: ContextShift[IO]): Stream[IO, Byte] = {
    source.observe(
      io.writeOutputStream[IO](
        IO {
          systemProcess.getOutputStream
        },
        closeAfterUse = true,
        blockingExecutionContext = blockingExecutionContext))
  }

  override def run(stream: Stream[IO, Byte])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(stream.compile.drain)
}

/** Input source implementation that calls 'flush' after each chunk of bytes. Useful for interactive
  * communication with processes.
  *
  * @param source The input byte stream
  */
class FlushControlledInputStreamingSource(source: FlushChunks[IO]) extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.PIPE

  override def connect(systemProcess: lang.Process, blockingExecutionContext: ExecutionContext)(implicit contextShift: ContextShift[IO]): Stream[IO, Byte] = {
    source.stream.observe(
      writeAndFlushOutputStream(
        systemProcess.getOutputStream,
        blockingExecutionContext = blockingExecutionContext))
  }

  override def run(stream: Stream[IO, Byte])(implicit contextShift: ContextShift[IO]): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(stream.compile.drain)

  def writeAndFlushOutputStream(stream: OutputStream,
                                blockingExecutionContext: ExecutionContext)(
                                 implicit contextShift: ContextShift[IO]): Pipe[IO, Byte, Unit] = s => {
    Stream
      .bracket(IO.pure(stream))(os => IO(os.close()))
      .flatMap { os =>
        s.chunks.evalMap { chunk =>
          contextShift.evalOn(blockingExecutionContext) {
            IO {
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