package io.github.vigoo.prox

import java.io
import java.io.IOException

import zio.blocking.Blocking
import zio.prelude.Identity
import zio.stream.{ZSink, ZStream, ZTransducer}
import zio._

import scala.language.implicitConversions

trait ProxZStream extends Prox {
  override type ExitCode = zio.ExitCode
  override type Fiber[A] = zio.Fiber[ProxError, A]
  override type IO[A] = ZIO[Blocking, ProxError, A]
  override type Resource[A] = ZManaged[Blocking, ProxError, A]
  override type Stream[A] = ZStream[Blocking, ProxError, A]
  override type Pipe[A, B] = Stream[A] => Stream[B]
  override type Sink[A] = ZSink[Blocking, ProxError, A, Any, Unit]
  override type Monoid[A] = zio.prelude.Identity[A]

  protected override final def exitCodeFromInt(value: Int): ExitCode =
    zio.ExitCode(value)

  protected override final def unit: IO[Unit] =
    ZIO.unit

  protected override final def pure[A](value: A): IO[A] =
    ZIO.succeed(value)

  protected override final def effect[A](f: => A, wrapError: Throwable => ProxError): IO[A] =
    ZIO.effect(f).mapError(wrapError)

  protected override final def raiseError(error: ProxError): IO[Unit] =
    ZIO.fail(error)

  protected override final def ioMap[A, B](io: IO[A], f: A => B): IO[B] =
    io.map(f)

  protected override final def ioFlatMap[A, B](io: IO[A], f: A => IO[B]): IO[B] =
    io.flatMap(f)

  protected override final def traverse[A, B](list: List[A])(f: A => IO[B]): IO[List[B]] =
    ZIO.foreach(list)(f)

  protected override final def identityPipe[A]: Pipe[A, A] =
    identity

  protected override final def bracket[A, B](acquire: IO[A])(use: A => IO[B])(fin: (A, IOResult) => IO[Unit]): IO[B] = {
    ZIO.bracketExit(acquire) { (value: A, exit: Exit[ProxError, B]) =>
      exit match {
        case Exit.Success(_) => fin(value, Completed).mapError(_.toThrowable).orDie
        case Exit.Failure(cause) =>
          if (cause.interrupted) {
            fin(value, Canceled).mapError(_.toThrowable).orDie
          } else {
            fin(value, Failed(cause.failures ++ cause.defects.map(UnknownProxError))).mapError(_.toThrowable).orDie
          }
      }
    }(use)
  }

  protected override final def makeResource[A](acquire: IO[A], release: A => IO[Unit]): Resource[A] =
    ZManaged.make(acquire)(x => release(x).mapError(_.toThrowable).orDie)

  protected override final def useResource[A, B](r: Resource[A], f: A => IO[B]): IO[B] =
    r.use(f)

  protected override final def joinFiber[A](f: Fiber[A]): IO[A] =
    f.join

  protected override final def cancelFiber[A](f: Fiber[A]): IO[Unit] =
    f.interrupt.unit

  protected override final def drainStream[A](s: Stream[A]): IO[Unit] =
    s.runDrain

  protected override final def streamToVector[A](s: Stream[A]): IO[Vector[A]] =
    s.runCollect.map(_.toVector)

  protected override final def foldStream[A, B](s: Stream[A], init: B, f: (B, A) => B): IO[B] =
    s.fold(init)(f)

  protected override final def foldMonoidStream[A: Identity](s: Stream[A]): IO[A] =
    s.fold(Identity[A].identity)((a, b) => Identity[A].combine(a, b))

  protected override final def streamThrough[A, B](s: Stream[A], pipe: Pipe[A, B]): Stream[B] =
    pipe(s)

  override protected def runStreamTo[A](s: Stream[A], sink: Sink[A]): IO[Unit] =
    s.run(sink)

  protected override final def fromJavaInputStream(input: io.InputStream, chunkSize: Int): Stream[Byte] =
    ZStream.fromInputStream(input, chunkSize).mapError(FailedToReadProcessOutput)

  protected override final def drainToJavaOutputStream(stream: Stream[Byte], output: io.OutputStream, flushChunks: Boolean): IO[Unit] = {
    if (flushChunks) {
      stream.run(flushingOutputStreamSink(output).mapError(FailedToWriteProcessInput)).unit
    } else {
      stream.run(ZSink.fromOutputStream(output).mapError(FailedToWriteProcessInput)).unit
    }
  }

  final def flushingOutputStreamSink(os: io.OutputStream): ZSink[Blocking, IOException, Byte, Byte, Long] =
    ZSink.foldLeftChunksM(0L) { (bytesWritten, byteChunk: Chunk[Byte]) =>
      blocking.effectBlockingInterrupt {
        val bytes = byteChunk.toArray
        os.write(bytes)
        os.flush()
        bytesWritten + bytes.length
      }.refineOrDie {
        case e: IOException => e
      }
    }

  protected override final def startFiber[A](f: IO[A]): IO[Fiber[A]] =
    f.fork

  implicit def transducerAsPipe[A, B](transducer: ZTransducer[Blocking, ProxError, A, B]): Pipe[A, B] =
    (s: Stream[A]) => s.transduce(transducer)
}

object zstream extends ProxZStream
