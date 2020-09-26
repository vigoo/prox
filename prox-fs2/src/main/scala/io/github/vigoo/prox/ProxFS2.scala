package io.github.vigoo.prox

import java.io

import cats.effect.{Blocker, Concurrent, ContextShift, ExitCase, Sync}
import cats.{Applicative, ApplicativeError, FlatMap, Traverse}

import scala.concurrent.blocking

trait ProxFS2[F[_]] extends Prox {

  implicit val concurrent: Concurrent[F]
  implicit val blocker: Blocker
  implicit val contextShift: ContextShift[F]

  override type ExitCode = cats.effect.ExitCode
  override type Fiber[A] = cats.effect.Fiber[F, A]
  override type IO[A] = F[A]
  override type Resource[A] = cats.effect.Resource[F, A]

  override type Pipe[A, B] = fs2.Pipe[F, A, B]
  override type Sink[A] = fs2.Pipe[F, A, Unit]
  override type Stream[A] = fs2.Stream[F, A]

  override type Monoid[A] = cats.kernel.Monoid[A]

  protected override final def exitCodeFromInt(value: Int): ExitCode = cats.effect.ExitCode(value)

  protected override final def unit: IO[Unit] = Applicative[F].unit

  protected override final def pure[A](value: A): IO[A] = Applicative[F].pure(value)

  protected override final def effect[A](f: => A, wrapError: Throwable => ProxError): IO[A] = {
    Sync[F].adaptError(Sync[F].delay(f)) {
      case failure: Throwable => wrapError(failure).toThrowable
    }
  }

  protected override final def raiseError(error: ProxError): IO[Unit] = ApplicativeError[F, Throwable].raiseError(error.toThrowable)

  protected override final def ioMap[A, B](io: IO[A], f: A => B): IO[B] = Applicative[F].map(io)(f)

  protected override final def ioFlatMap[A, B](io: IO[A], f: A => IO[B]): IO[B] = FlatMap[F].flatMap(io)(f)

  protected override final def traverse[A, B](list: List[A])(f: A => IO[B]): IO[List[B]] = Traverse[List].traverse(list)(f)

  protected override final def identityPipe[A]: Pipe[A, A] = identity[Stream[A]]

  protected override final def bracket[A, B](acquire: IO[A])(use: A => IO[B])(fin: (A, IOResult) => IO[Unit]): IO[B] =
    Sync[F].bracketCase(acquire)(use) {
      case (value, ExitCase.Completed) => fin(value, Completed)
      case (value, ExitCase.Error(error)) => fin(value, Failed(List(UnknownProxError(error))))
      case (value, ExitCase.Canceled) => fin(value, Canceled)
    }

  protected override final def makeResource[A](acquire: IO[A], release: A => IO[Unit]): Resource[A] = cats.effect.Resource.make(acquire)(release)

  protected override final def useResource[A, B](r: Resource[A], f: A => IO[B]): IO[B] = r.use(f)

  protected override final def joinFiber[A](f: Fiber[A]): IO[A] = f.join

  protected override final def cancelFiber[A](f: Fiber[A]): IO[Unit] = f.cancel

  protected override final def startFiber[A](f: IO[A]): IO[Fiber[A]] = Concurrent[F].start(f)

  protected override final def drainStream[A](s: Stream[A]): IO[Unit] = s.compile.drain

  protected override final def streamToVector[A](s: Stream[A]): IO[Vector[A]] = s.compile.toVector

  protected override final def foldStream[A, B](s: Stream[A], init: B, f: (B, A) => B): IO[B] = s.compile.fold(init)(f)

  protected override final def foldMonoidStream[A: Monoid](s: Stream[A]): IO[A] = s.compile.foldMonoid

  protected override final def streamThrough[A, B](s: Stream[A], pipe: Pipe[A, B]): Stream[B] = s.through(pipe)

  protected override final def runStreamTo[A](s: Stream[A], sink: Sink[A]): IO[Unit] =
    s.through(sink).compile.drain

  protected override final def fromJavaInputStream(input: io.InputStream, chunkSize: Int): Stream[Byte] =
    fs2.io.readInputStream(
      pure(input),
      chunkSize,
      closeAfterUse = true,
      blocker = blocker)

  protected override final def drainToJavaOutputStream(stream: Stream[Byte], output: io.OutputStream, flushChunks: Boolean): IO[Unit] =
    stream
      .observe(
        if (flushChunks) writeAndFlushOutputStream(output)
        else fs2.io.writeOutputStream(
          effect(output, UnknownProxError),
          closeAfterUse = true,
          blocker = blocker))
      .compile
      .drain

  private def writeAndFlushOutputStream(stream: java.io.OutputStream): Pipe[Byte, Unit] =
    s => {
      fs2.Stream
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

object ProxFS2 {
  def apply[F[_]](blk: Blocker)(implicit c: Concurrent[F], cs: ContextShift[F]): ProxFS2[F] = new ProxFS2[F] {
    override implicit val concurrent: Concurrent[F] = c
    override implicit val blocker: Blocker = blk
    override implicit val contextShift: ContextShift[F] = cs
  }
}
