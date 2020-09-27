package io.github.vigoo.prox

private[prox] sealed trait IOResult
private[prox] case object Completed extends IOResult
private[prox] final case class Failed(errors: List[ProxError]) extends IOResult
private[prox] case object Canceled extends IOResult

trait ProxRuntime {
  // NOTE: the Prox prefix was added to avoid collision with the host environment's names (when importing cats.effect._ or zio._)
  type ProxExitCode
  type ProxFiber[_]
  type ProxIO[_]
  type ProxResource[_]

  type ProxSink[_]
  type ProxPipe[_, _]
  type ProxStream[_]

  type ProxMonoid[_]

  protected def exitCodeFromInt(value: Int): ProxExitCode

  protected def unit: ProxIO[Unit]
  protected def pure[A](value: A): ProxIO[A]
  protected def effect[A](f: => A, wrapError: Throwable => ProxError): ProxIO[A]
  protected def raiseError(error: ProxError): ProxIO[Unit]
  protected def ioMap[A, B](io: ProxIO[A], f: A => B): ProxIO[B]
  protected def ioFlatMap[A, B](io: ProxIO[A], f: A => ProxIO[B]): ProxIO[B]
  protected def traverse[A, B](list: List[A])(f: A => ProxIO[B]): ProxIO[List[B]]

  protected def identityPipe[A]: ProxPipe[A, A]

  protected def bracket[A, B](acquire: ProxIO[A])(use: A => ProxIO[B])(fin: (A, IOResult) => ProxIO[Unit]): ProxIO[B]

  protected def makeResource[A](acquire: ProxIO[A], release: A => ProxIO[Unit]): ProxResource[A]
  protected def useResource[A, B](r: ProxResource[A], f: A => ProxIO[B]): ProxIO[B]

  protected def joinFiber[A](f: ProxFiber[A]): ProxIO[A]
  protected def cancelFiber[A](f: ProxFiber[A]): ProxIO[Unit]

  protected def drainStream[A](s: ProxStream[A]): ProxIO[Unit]
  protected def streamToVector[A](s: ProxStream[A]): ProxIO[Vector[A]]
  protected def foldStream[A, B](s: ProxStream[A], init: B, f: (B, A) => B): ProxIO[B]
  protected def foldMonoidStream[A : ProxMonoid](s: ProxStream[A]): ProxIO[A]
  protected def streamThrough[A, B](s: ProxStream[A], pipe: ProxPipe[A, B]): ProxStream[B]
  protected def runStreamTo[A](s: ProxStream[A], sink: ProxSink[A]): ProxIO[Unit]

  protected def fromJavaInputStream(input: java.io.InputStream, chunkSize: Int): ProxStream[Byte]
  protected def drainToJavaOutputStream(stream: ProxStream[Byte], output: java.io.OutputStream, flushChunks: Boolean): ProxIO[Unit]

  protected def startFiber[A](f: ProxIO[A]): ProxIO[ProxFiber[A]]

  protected implicit class IOOps[A](io: ProxIO[A]) {
    def map[B](f: A => B): ProxIO[B] = ioMap(io, f)
    def flatMap[B](f: A => ProxIO[B]): ProxIO[B] = ioFlatMap(io, f)
  }

  protected implicit class ResourceOps[A](r: ProxResource[A]) {
    def use[B](f: A => ProxIO[B]): ProxIO[B] = useResource(r, f)
  }

  protected implicit class FiberOps[A](f: ProxFiber[A]) {
    def cancel: ProxIO[Unit] = cancelFiber(f)
    def join: ProxIO[A] = joinFiber(f)
  }

  protected implicit class StreamOps[A](s: ProxStream[A]) {
    def drain: ProxIO[Unit] = drainStream(s)
    def toVector: ProxIO[Vector[A]] = streamToVector(s)
    def fold[B](init: B, f: (B, A) => B): ProxIO[B] = foldStream(s, init, f)
    def through[B](pipe: ProxPipe[A, B]): ProxStream[B] = streamThrough(s, pipe)
    def run(sink: ProxSink[A]): ProxIO[Unit] = runStreamTo(s, sink)
  }

  protected implicit class MonoidStreamOps[A : ProxMonoid](s: ProxStream[A]) {
    def foldMonoid: ProxIO[A] = foldMonoidStream(s)
  }

  protected implicit class ListProxErrorOps(list: List[ProxError]) {
    def toSingleError: ProxError =
      list match {
        case Nil => UnknownProxError(new IllegalArgumentException("Error list is empty"))
        case List(single) => single
        case _ => MultipleProxErrors(list)
      }
  }
}
