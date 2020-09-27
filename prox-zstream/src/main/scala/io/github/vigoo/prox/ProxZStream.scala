package io.github.vigoo.prox

import java.io
import java.io.IOException

import zio.blocking.Blocking
import zio.prelude.Identity
import zio.stream.{ZSink, ZStream, ZTransducer}
import zio._

import scala.language.implicitConversions

trait ProxZStream extends Prox {

  case class TransformAndSink[A, B](transform: ZStream[Blocking, ProxError, A] => ZStream[Blocking, ProxError, B],
                                    sink: ZSink[Blocking, ProxError, B, Any, Unit]) {
    private[ProxZStream] def run(s: ZStream[Blocking, ProxError, A]): ZIO[Blocking, ProxError, Unit] =
      transform(s).run(sink)
  }
  object TransformAndSink {
    def apply[A, B](transducer: ZTransducer[Blocking, ProxError, A, B], sink: ZSink[Blocking, ProxError, B, Any, Unit]): TransformAndSink[A, B] =
      TransformAndSink(_.transduce(transducer), sink)
  }

  override type ProxExitCode = zio.ExitCode
  override type ProxFiber[A] = zio.Fiber[ProxError, A]
  override type ProxIO[A] = ZIO[Blocking, ProxError, A]
  override type ProxResource[A] = ZManaged[Blocking, ProxError, A]
  override type ProxStream[A] = ZStream[Blocking, ProxError, A]
  override type ProxPipe[A, B] = ProxStream[A] => ProxStream[B]
  override type ProxSink[A] = TransformAndSink[A, _]
  override type ProxMonoid[A] = zio.prelude.Identity[A]

  protected override final def exitCodeFromInt(value: Int): ProxExitCode =
    zio.ExitCode(value)

  protected override final def unit: ProxIO[Unit] =
    ZIO.unit

  protected override final def pure[A](value: A): ProxIO[A] =
    ZIO.succeed(value)

  protected override final def effect[A](f: => A, wrapError: Throwable => ProxError): ProxIO[A] =
    ZIO.effect(f).mapError(wrapError)

  protected override final def raiseError(error: ProxError): ProxIO[Unit] =
    ZIO.fail(error)

  protected override final def ioMap[A, B](io: ProxIO[A], f: A => B): ProxIO[B] =
    io.map(f)

  protected override final def ioFlatMap[A, B](io: ProxIO[A], f: A => ProxIO[B]): ProxIO[B] =
    io.flatMap(f)

  protected override final def traverse[A, B](list: List[A])(f: A => ProxIO[B]): ProxIO[List[B]] =
    ZIO.foreach(list)(f)

  protected override final def identityPipe[A]: ProxPipe[A, A] =
    identity

  protected override final def bracket[A, B](acquire: ProxIO[A])(use: A => ProxIO[B])(fin: (A, IOResult) => ProxIO[Unit]): ProxIO[B] = {
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

  protected override final def makeResource[A](acquire: ProxIO[A], release: A => ProxIO[Unit]): ProxResource[A] =
    ZManaged.make(acquire)(x => release(x).mapError(_.toThrowable).orDie)

  protected override final def useResource[A, B](r: ProxResource[A], f: A => ProxIO[B]): ProxIO[B] =
    r.use(f)

  protected override final def joinFiber[A](f: ProxFiber[A]): ProxIO[A] =
    f.join

  protected override final def cancelFiber[A](f: ProxFiber[A]): ProxIO[Unit] =
    f.interrupt.unit

  protected override final def drainStream[A](s: ProxStream[A]): ProxIO[Unit] =
    s.runDrain

  protected override final def streamToVector[A](s: ProxStream[A]): ProxIO[Vector[A]] =
    s.runCollect.map(_.toVector)

  protected override final def foldStream[A, B](s: ProxStream[A], init: B, f: (B, A) => B): ProxIO[B] =
    s.fold(init)(f)

  protected override final def foldMonoidStream[A: Identity](s: ProxStream[A]): ProxIO[A] =
    s.fold(Identity[A].identity)((a, b) => Identity[A].combine(a, b))

  protected override final def streamThrough[A, B](s: ProxStream[A], pipe: ProxPipe[A, B]): ProxStream[B] =
    pipe(s)

  override protected final def runStreamTo[A](s: ProxStream[A], sink: ProxSink[A]): ProxIO[Unit] =
    sink.run(s)

  protected override final def fromJavaInputStream(input: io.InputStream, chunkSize: Int): ProxStream[Byte] =
    ZStream.fromInputStream(input, chunkSize).mapError(FailedToReadProcessOutput)

  protected override final def drainToJavaOutputStream(stream: ProxStream[Byte], output: io.OutputStream, flushChunks: Boolean): ProxIO[Unit] = {
    if (flushChunks) {
      stream.run(flushingOutputStreamSink(output).mapError(FailedToWriteProcessInput)).unit
    } else {
      stream.run(ZSink.fromOutputStream(output).mapError(FailedToWriteProcessInput)).unit
    }
  }

  private final def flushingOutputStreamSink(os: io.OutputStream): ZSink[Blocking, IOException, Byte, Byte, Long] =
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

  protected override final def startFiber[A](f: ProxIO[A]): ProxIO[ProxFiber[A]] =
    f.fork

  implicit def transducerAsPipe[A, B](transducer: ZTransducer[Blocking, ProxError, A, B]): ProxPipe[A, B] =
    (s: ProxStream[A]) => s.transduce(transducer)

  implicit def sinkAsTransformAndSink[A](sink: ZSink[Blocking, ProxError, A, Any, Unit]): TransformAndSink[A, A] =
    TransformAndSink(identity[ZStream[Blocking, ProxError, A]] _, sink)
}

object zstream extends ProxZStream
