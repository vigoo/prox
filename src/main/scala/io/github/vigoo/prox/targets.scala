package io.github.vigoo.prox

import java.io.InputStream
import java.lang
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import akka.stream.Materializer
import akka.stream.scaladsl._
import akka.util.ByteString
import cats.Monoid
import cats.effect.{Concurrent, ContextShift, Fiber, IO}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

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
  * by [[Sink.ignore]] and the result type will be [[Unit]].
  *
  * @param pipe The pipe to wrap
  * @tparam O Stream element type
  */
case class Drain[O](pipe: Flow[ByteString, O, Any])

/** Wraps a pipe to modify how the stream is executed
  *
  * If a pipe used as an output or error target is wrapped by [[ToVector]], the stream will be executed
  * by [[Sink.seq]] and the result type will be a [[Vector]] of its element
  * type.
  *
  * @param pipe The pipe to wrap
  * @tparam O Stream element type
  */
case class ToVector[O](pipe: Flow[ByteString, O, Any])

/** Wraps the pipe to modify how the stream is executed
  *
  * If a pipe used as an output or error target is wrapped by [[Fold]], the stream will be executed
  * by [[Sink.fold]] and the result type will be the result type of the provided
  * fold function.
  *
  * @param pipe The pipe to wrap
  * @param init Initial value for the fold
  * @param f    The fold function
  * @tparam O Stream element type
  * @tparam R Fold result type
  */
case class Fold[O, R](pipe: Flow[ByteString, O, Any], init: R, f: (R, O) => R)

trait LowPriorityCanBeProcessOutputTarget {
  implicit def pipeAsTarget[Out, Mat]: CanBeProcessOutputTarget.Aux[Flow[ByteString, Out, Mat], Out, Vector[Out]] =
    CanBeProcessOutputTarget.create((pipe: Flow[ByteString, Out, Mat]) => new OutputStreamingTarget(pipe) with ProcessOutputTarget[Out, Vector[Out]] {
      override def run(stream: Source[Out, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, Vector[Out]]] =
        Concurrent[IO].start(IO.async { complete =>
          stream.runWith(Sink.seq).onComplete {
            case Success(value) => complete(Right(value.toVector))
            case Failure(reason) => complete(Left(reason))
          }
        })
    })
}

/** Instances of the [[CanBeProcessOutputTarget]] type class
  *
  * There are instances for the following types:
  *
  *  - [[java.nio.file.Path]] to redirect the output to a file
  *  - [[Sink]] to redirect the output to a sink. The result type is [[Unit]].
  *  - [[Flow]] if the pipe's output element type is a [[cats.Monoid]]. The result type is its element type.
  *  - [[Flow]] if the pipe's output element type is not a [[cats.Monoid]]. The result type is a [[Vector]] of its element type.
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

  implicit val pathAsTarget: Aux[Path, ByteString, Unit] =
    create((path: Path) => new FileTarget(path))

  implicit def sinkAsTarget[R]: Aux[Sink[ByteString, Future[R]], ByteString, R] =
    create((sink: Sink[ByteString, Future[R]]) => new OutputStreamingTarget(Flow.fromFunction(identity)) with ProcessOutputTarget[ByteString, R] {

      override def run(stream: Source[ByteString, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, R]] =
        Concurrent[IO].start(IO.async { complete =>
          stream.runWith(sink).onComplete {
            case Success(value) => complete(Right(value))
            case Failure(reason) => complete(Left(reason))
          }
        })
    })

  implicit def monoidPipeAsTarget[Out, Mat](implicit monoid: Monoid[Out]): Aux[Flow[ByteString, Out, Mat], Out, Out] =
    create((pipe: Flow[ByteString, Out, Mat]) => new OutputStreamingTarget(pipe) with ProcessOutputTarget[Out, Out] {
      override def run(stream: Source[Out, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, Out]] = {
        Concurrent[IO].start(IO.async { complete =>
          stream.runFold(monoid.empty)(monoid.combine).onComplete {
            case Success(value) => complete(Right(value))
            case Failure(reason) => complete(Left(reason))
          }
        })
      }
    })

  implicit def ignorePipeAsOutputTarget[Out]: Aux[Drain[Out], Out, Unit] =
    create((ignore: Drain[Out]) => new OutputStreamingTarget(ignore.pipe) with ProcessOutputTarget[Out, Unit] {
      override def run(stream: Source[Out, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, Unit]] = {
        Concurrent[IO].start(IO.async { complete =>
          stream.runWith(Sink.ignore).onComplete {
            case Success(value) => complete(Right(()))
            case Failure(reason) => complete(Left(reason))
          }
        })
      }
    })

  implicit def logPipeAsOutputTarget[Out]: Aux[ToVector[Out], Out, Vector[Out]] =
    create((log: ToVector[Out]) => new OutputStreamingTarget(log.pipe) with ProcessOutputTarget[Out, Vector[Out]] {
      override def run(stream: Source[Out, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, Vector[Out]]] = {
        Concurrent[IO].start(IO.async { complete =>
          stream.runWith(Sink.seq).onComplete {
            case Success(value) => complete(Right(value.toVector))
            case Failure(reason) => complete(Left(reason))
          }
        })
      }
    })

  implicit def foldPipeAsOutputTarget[Out, Res]: Aux[Fold[Out, Res], Out, Res] =
    create((fold: Fold[Out, Res]) => new OutputStreamingTarget(fold.pipe) with ProcessOutputTarget[Out, Res] {
      override def run(stream: Source[Out, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, Res]] = {
        Concurrent[IO].start(IO.async { complete =>
          stream.runFold(fold.init)(fold.f).onComplete {
            case Success(value) => complete(Right(value))
            case Failure(reason) => complete(Left(reason))
          }
        })
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
  implicit def pipeAsErrorTarget[Err, Mat]: CanBeProcessErrorTarget.Aux[Flow[ByteString, Err, Mat], Err, Vector[Err]] =
    CanBeProcessErrorTarget.create((pipe: Flow[ByteString, Err, Mat]) => new ErrorStreamingTarget(pipe) with ProcessErrorTarget[Err, Vector[Err]] {
      override def run(stream: Source[Err, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, Vector[Err]]] = {
        Concurrent[IO].start(IO.async { complete =>
          stream.runWith(Sink.seq).onComplete {
            case Success(value) => complete(Right(value.toVector))
            case Failure(reason) => complete(Left(reason))
          }
        })
      }
    })
}

/** Instances of the [[CanBeProcessErrorTarget]] type class
  *
  * There are instances for the following types:
  *
  *  - [[java.nio.file.Path]] to redirect the error channel to a file
  *  - [[Sink]] to redirect the error channel to a sink. The result type is [[Unit]].
  *  - [[Flow]] if the pipe's output element type is a [[cats.Monoid]]. The result type is its element type.
  *  - [[Flow]] if the pipe's output element type is not a [[cats.Monoid]]. The result type is a [[Vector]] of its element type.
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

  implicit val pathAsErrorTarget: Aux[Path, ByteString, Unit] =
    create((path: Path) => new FileTarget(path))

  implicit def sinkAsErrorTarget[R]: Aux[Sink[ByteString, Future[R]], ByteString, R] =
    create((sink: Sink[ByteString, Future[R]]) => new ErrorStreamingTarget(Flow.fromFunction(identity)) with ProcessErrorTarget[ByteString, R] {

      override def run(stream: Source[ByteString, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, R]] =
        Concurrent[IO].start(IO.async { complete =>
          stream.runWith(sink).onComplete {
            case Success(value) => complete(Right(value))
            case Failure(reason) => complete(Left(reason))
          }
        })
    })

  implicit def monoidPipeAsErrorTarget[Err, Mat](implicit monoid: Monoid[Err]): Aux[Flow[ByteString, Err, Mat], Err, Err] =
    create((pipe: Flow[ByteString, Err, Mat]) => new ErrorStreamingTarget(pipe) with ProcessErrorTarget[Err, Err] {
      override def run(stream: Source[Err, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, Err]] = {
        Concurrent[IO].start(IO.async { complete =>
          stream.runFold(monoid.empty)(monoid.combine).onComplete {
            case Success(value) => complete(Right(value))
            case Failure(reason) => complete(Left(reason))
          }
        })
      }
    })

  implicit def logPipeAsErrorTarget[Err]: Aux[ToVector[Err], Err, Vector[Err]] =
    create((log: ToVector[Err]) => new ErrorStreamingTarget(log.pipe) with ProcessErrorTarget[Err, Vector[Err]] {
      override def run(stream: Source[Err, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, Vector[Err]]] = {
        Concurrent[IO].start(IO.async { complete =>
          stream.runWith(Sink.seq).onComplete {
            case Success(value) => complete(Right(value.toVector))
            case Failure(reason) => complete(Left(reason))
          }
        })
      }
    })

  implicit def ignorePipeAsErrorTarget[Err]: Aux[Drain[Err], Err, Unit] =
    create((ignore: Drain[Err]) => new ErrorStreamingTarget(ignore.pipe) with ProcessErrorTarget[Err, Unit] {
      override def run(stream: Source[Err, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, Unit]] = {
        Concurrent[IO].start(IO.async { complete =>
          stream.runWith(Sink.ignore).onComplete {
            case Success(value) => complete(Right(()))
            case Failure(reason) => complete(Left(reason))
          }
        })
      }
    })

  implicit def foldPipeAsErrorTarget[Err, Res]: Aux[Fold[Err, Res], Err, Res] =
    create((fold: Fold[Err, Res]) => new ErrorStreamingTarget(fold.pipe) with ProcessErrorTarget[Err, Res] {
      override def run(stream: Source[Err, Any])
                      (implicit contextShift: ContextShift[IO],
                       materializer: Materializer,
                       executionContext: ExecutionContext): IO[Fiber[IO, Res]] = {
        Concurrent[IO].start(IO.async { complete =>
          stream.runFold(fold.init)(fold.f).onComplete {
            case Success(value) => complete(Right(value))
            case Failure(reason) => complete(Left(reason))
          }
        })
      }
    })
}

/** Default implementation of [[ProcessOutputTarget]] representing no redirection */
object StdOut extends ProcessOutputTarget[ByteString, Unit] {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process)(implicit contextShift: ContextShift[IO]): IO[Source[ByteString, Any]] =
    IO.pure(Source.empty)

  override def run(stream: Source[ByteString, Any])
                  (implicit contextShift: ContextShift[IO],
                   materializer: Materializer,
                   executionContext: ExecutionContext): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(IO.unit)
}

/** Default implementation of [[ProcessErrorTarget]] representing no redirection */
object StdError extends ProcessErrorTarget[ByteString, Unit] {
  override def toRedirect: Redirect = Redirect.INHERIT

  override def connect(systemProcess: lang.Process)(implicit contextShift: ContextShift[IO]): IO[Source[ByteString, Any]] =
    IO.pure(Source.empty)

  override def run(stream: Source[ByteString, Any])
                  (implicit contextShift: ContextShift[IO],
                   materializer: Materializer,
                   executionContext: ExecutionContext): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(IO.unit)
}

/** Output/error target implementation for using a file as the target
  *
  * @param path Path to the file to be written
  */
class FileTarget(path: Path) extends ProcessOutputTarget[ByteString, Unit] with ProcessErrorTarget[ByteString, Unit] {
  override def toRedirect: Redirect = Redirect.to(path.toFile)

  override def connect(systemProcess: lang.Process)(implicit contextShift: ContextShift[IO]): IO[Source[ByteString, Any]] =
    IO.pure(Source.empty)

  override def run(stream: Source[ByteString, Any])
                  (implicit contextShift: ContextShift[IO],
                   materializer: Materializer,
                   executionContext: ExecutionContext): IO[Fiber[IO, Unit]] =
    Concurrent[IO].start(IO.unit)
}

/** Base class for output/error target implementations using a stream pipe as the target
  *
  * @param target    Target stream
  * @param chunkSize Chunk size
  * @tparam Out Stream output element type
  */
abstract class OutputStreamingTargetBase[Out](target: Flow[ByteString, Out, Any], chunkSize: Int = 4096) {

  def toRedirect: Redirect = Redirect.PIPE

  def connect(systemProcess: lang.Process)
             (implicit contextShift: ContextShift[IO]): IO[Source[Out, Any]] = {
    for {
      inputStream <- getStream(systemProcess)
      source = StreamConverters.fromInputStream(() => inputStream, chunkSize)
    } yield source.via(target)
  }

  def getStream(systemProcess: java.lang.Process): IO[InputStream]
}

/** Output target implementation using a stream pipe as the target
  *
  * @param target Target stream
  * @tparam Out Stream output element type
  */
abstract class OutputStreamingTarget[Out, Mat](target: Flow[ByteString, Out, Mat])
  extends OutputStreamingTargetBase(target) {

  override def getStream(systemProcess: java.lang.Process): IO[InputStream] =
    IO(systemProcess.getInputStream)
}

/** Error target implementation using a stream pipe as the target
  *
  * @param target Target stream
  * @tparam Err Stream output element type
  */
abstract class ErrorStreamingTarget[Err, Mat](target: Flow[ByteString, Err, Mat])
  extends OutputStreamingTargetBase(target) {

  override def getStream(systemProcess: java.lang.Process): IO[InputStream] =
    IO(systemProcess.getErrorStream)
}
