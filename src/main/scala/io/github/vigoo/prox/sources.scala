package io.github.vigoo.prox

import java.lang
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.StreamConverters._
import akka.stream.scaladsl._
import akka.util.ByteString
import cats.effect.{Concurrent, ContextShift, Fiber, IO}

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.util.{Failure, Success}

/** Base trait for input redirection handlers */
trait ProcessInputSource extends ProcessIO[ByteString, Unit]

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
  * - [[Source]] to use a byte stream as input
  */
object CanBeProcessInputSource {
  implicit val pathAsSource: CanBeProcessInputSource[Path] =
    (path: Path) => new FileSource(path)

  implicit def streamAsSource: CanBeProcessInputSource[Source[ByteString, Any]] =
    (source: Source[ByteString, Any]) => new InputStreamingSource(source)
}

/** Default input source representing no redirection */
object StdIn extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process)(implicit contextShift: ContextShift[IO]): Source[ByteString, Any] =
    Source.empty

  override def run(stream: Source[ByteString, Any])
                  (implicit contextShift: ContextShift[IO],
                   materializer: Materializer,
                   executionContext: ExecutionContext): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(IO.unit)
}

/** Input source implementation of using a file as input
  *
  * @param path Path to the file
  */
class FileSource(path: Path) extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.from(path.toFile)
  override def connect(systemProcess: lang.Process)(implicit contextShift: ContextShift[IO]): Source[ByteString, Any] =
    Source.empty

  override def run(stream: Source[ByteString, Any])
                  (implicit contextShift: ContextShift[IO],
                   materializer: Materializer,
                   executionContext: ExecutionContext): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(IO.unit)
}

/** Input source implementation of using a byte stream as input
  *
  * @param source The input byte stream
  */
class InputStreamingSource(source: Source[ByteString, Any]) extends ProcessInputSource {
  override def toRedirect: Redirect = Redirect.PIPE

  override def connect(systemProcess: lang.Process)(implicit contextShift: ContextShift[IO]): Source[ByteString, Any] =
    source.alsoTo(fromOutputStream(() => systemProcess.getOutputStream, autoFlush = true))

  override def run(stream: Source[ByteString, Any])
                  (implicit contextShift: ContextShift[IO],
                   materializer: Materializer,
                   executionContext: ExecutionContext): IO[Fiber[IO, Unit]] = {
    Concurrent[IO].start(IO.async { finish =>
      stream.runWith(Sink.ignore).onComplete {
        case Success(Done) => finish(Right(()))
        case Failure(reason) => finish(Left(reason))
      }
    })
  }
}