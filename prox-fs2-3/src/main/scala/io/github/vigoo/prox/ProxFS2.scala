package io.github.vigoo.prox

import java.io

import cats.effect.{Concurrent, Outcome, Async, Sync}
import cats.{Applicative, ApplicativeError, FlatMap, Traverse}

trait ProxFS2[F[_]] extends Prox {

  val instances: Sync[F] & Concurrent[F]

  override type ProxExitCode = cats.effect.ExitCode
  override type ProxFiber[A] = cats.effect.Fiber[F, Throwable, A]
  override type ProxIO[A] = F[A]
  override type ProxResource[A] = cats.effect.Resource[F, A]

  override type ProxPipe[A, B] = fs2.Pipe[F, A, B]
  override type ProxSink[A] = fs2.Pipe[F, A, Unit]
  override type ProxStream[A] = fs2.Stream[F, A]

  override type ProxMonoid[A] = cats.kernel.Monoid[A]

  protected override final def exitCodeFromInt(value: Int): ProxExitCode =
    cats.effect.ExitCode(value)

  protected override final def unit: ProxIO[Unit] =
    Applicative[F](instances).unit

  protected override final def pure[A](value: A): ProxIO[A] =
    Applicative[F](instances).pure(value)

  protected override final def effect[A](
      f: => A,
      wrapError: Throwable => ProxError
  ): ProxIO[A] = {
    implicit val i: Sync[F] & Concurrent[F] = instances
    Sync[F].adaptError(Sync[F].delay(f)) { case failure: Throwable =>
      wrapError(failure).toThrowable
    }
  }

  protected override final def blockingEffect[A](
      f: => A,
      wrapError: Throwable => ProxError
  ): ProxIO[A] = {
    implicit val i: Sync[F] & Concurrent[F] = instances
    Sync[F].adaptError(Sync[F].interruptibleMany(f)) {
      case failure: Throwable => wrapError(failure).toThrowable
    }
  }

  protected override final def raiseError(error: ProxError): ProxIO[Unit] =
    ApplicativeError[F, Throwable](instances).raiseError(error.toThrowable)

  protected override final def ioMap[A, B](
      io: ProxIO[A],
      f: A => B
  ): ProxIO[B] = Applicative[F](instances).map(io)(f)

  protected override final def ioFlatMap[A, B](
      io: ProxIO[A],
      f: A => ProxIO[B]
  ): ProxIO[B] = FlatMap[F](instances).flatMap(io)(f)

  protected override final def traverse[A, B](list: List[A])(
      f: A => ProxIO[B]
  ): ProxIO[List[B]] = Traverse[List].traverse(list)(f)(instances)

  protected override final def identityPipe[A]: ProxPipe[A, A] =
    identity[ProxStream[A]]

  protected override final def bracket[A, B](
      acquire: ProxIO[A]
  )(use: A => ProxIO[B])(fin: (A, IOResult) => ProxIO[Unit]): ProxIO[B] =
    Sync[F](instances).bracketCase(acquire)(use) {
      case (value, Outcome.Succeeded(_)) => fin(value, Completed)
      case (value, Outcome.Errored(error)) =>
        fin(value, Failed(List(UnknownProxError(error))))
      case (value, Outcome.Canceled()) => fin(value, Canceled)
    }

  protected override final def makeResource[A](
      acquire: ProxIO[A],
      release: A => ProxIO[Unit]
  ): ProxResource[A] = cats.effect.Resource.make(acquire)(release)(instances)

  protected override final def useResource[A, B](
      r: ProxResource[A],
      f: A => ProxIO[B]
  ): ProxIO[B] = r.use(f)(instances)

  protected override final def joinFiber[A](f: ProxFiber[A]): ProxIO[A] =
    f.joinWithNever(instances)

  protected override final def cancelFiber[A](f: ProxFiber[A]): ProxIO[Unit] =
    f.cancel

  protected override final def startFiber[A](
      f: ProxIO[A]
  ): ProxIO[ProxFiber[A]] = {
    implicit val i: Sync[F] & Concurrent[F] = instances
    Concurrent[F].start(f)
  }

  protected override final def drainStream[A](
      s: ProxStream[A]
  ): ProxIO[Unit] = {
    implicit val i: Sync[F] & Concurrent[F] = instances
    s.compile.drain
  }

  protected override final def streamToVector[A](
      s: ProxStream[A]
  ): ProxIO[Vector[A]] = {
    implicit val i: Sync[F] & Concurrent[F] = instances
    s.compile.toVector
  }

  protected override final def foldStream[A, B](
      s: ProxStream[A],
      init: B,
      f: (B, A) => B
  ): ProxIO[B] = {
    implicit val i: Sync[F] & Concurrent[F] = instances
    s.compile.fold(init)(f)
  }

  protected override final def foldMonoidStream[A: ProxMonoid](
      s: ProxStream[A]
  ): ProxIO[A] = {
    implicit val i: Sync[F] & Concurrent[F] = instances
    s.compile.foldMonoid
  }

  protected override final def streamThrough[A, B](
      s: ProxStream[A],
      pipe: ProxPipe[A, B]
  ): ProxStream[B] = s.through(pipe)

  protected override final def runStreamTo[A](
      s: ProxStream[A],
      sink: ProxSink[A]
  ): ProxIO[Unit] = {
    implicit val i: Sync[F] & Concurrent[F] = instances
    s.through(sink).compile.drain
  }

  protected override final def fromJavaInputStream(
      input: io.InputStream,
      chunkSize: Int
  ): ProxStream[Byte] =
    fs2.io.readInputStream(pure(input), chunkSize, closeAfterUse = true)(
      instances
    )

  protected override final def drainToJavaOutputStream(
      stream: ProxStream[Byte],
      output: io.OutputStream,
      flushChunks: Boolean
  ): ProxIO[Unit] = {
    implicit val i: Sync[F] & Concurrent[F] = instances
    stream
      .through(
        if (flushChunks) writeAndFlushOutputStream(output)(_).drain
        else
          fs2.io.writeOutputStream(
            effect(output, UnknownProxError.apply),
            closeAfterUse = true
          )
      )
      .compile
      .drain
  }

  private def writeAndFlushOutputStream(
      stream: java.io.OutputStream
  ): ProxPipe[Byte, Unit] = {
    implicit val i: Sync[F] & Concurrent[F] = instances
    s => {
      fs2.Stream
        .bracket(Applicative[F].pure(stream))(os => Sync[F].delay(os.close()))
        .flatMap { os =>
          s.chunks.evalMap { chunk =>
            Sync[F].blocking {
              os.write(chunk.toArray)
              os.flush()
            }
          }
        }
    }
  }
}

object ProxFS2 {
  def apply[F[_]](implicit a: Async[F]): ProxFS2[F] = new ProxFS2[F] {
    override val instances: Sync[F] & Concurrent[F] = a
  }
}
