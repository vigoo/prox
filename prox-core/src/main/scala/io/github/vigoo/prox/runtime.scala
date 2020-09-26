package io.github.vigoo.prox

private[prox] sealed trait IOResult
private[prox] case object Completed extends IOResult
private[prox] final case class Failed(errors: List[ProxError]) extends IOResult
private[prox] case object Canceled extends IOResult

trait ProxRuntime {
  type ExitCode
  type Fiber[_]
  type IO[_]
  type Resource[_]

  type Sink[_]
  type Pipe[_, _]
  type Stream[_]

  type Monoid[_]

  protected def exitCodeFromInt(value: Int): ExitCode

  protected def unit: IO[Unit]
  protected def pure[A](value: A): IO[A]
  protected def effect[A](f: => A, wrapError: Throwable => ProxError): IO[A]
  protected def raiseError(error: ProxError): IO[Unit]
  protected def ioMap[A, B](io: IO[A], f: A => B): IO[B]
  protected def ioFlatMap[A, B](io: IO[A], f: A => IO[B]): IO[B]
  protected def traverse[A, B](list: List[A])(f: A => IO[B]): IO[List[B]]

  protected def identityPipe[A]: Pipe[A, A]

  protected def bracket[A, B](acquire: IO[A])(use: A => IO[B])(fin: (A, IOResult) => IO[Unit]): IO[B]

  protected def makeResource[A](acquire: IO[A], release: A => IO[Unit]): Resource[A]
  protected def useResource[A, B](r: Resource[A], f: A => IO[B]): IO[B]

  protected def joinFiber[A](f: Fiber[A]): IO[A]
  protected def cancelFiber[A](f: Fiber[A]): IO[Unit]

  protected def drainStream[A](s: Stream[A]): IO[Unit]
  protected def streamToVector[A](s: Stream[A]): IO[Vector[A]]
  protected def foldStream[A, B](s: Stream[A], init: B, f: (B, A) => B): IO[B]
  protected def foldMonoidStream[A : Monoid](s: Stream[A]): IO[A]
  protected def streamThrough[A, B](s: Stream[A], pipe: Pipe[A, B]): Stream[B]
  protected def runStreamTo[A](s: Stream[A], sink: Sink[A]): IO[Unit]

  protected def fromJavaInputStream(input: java.io.InputStream, chunkSize: Int): Stream[Byte]
  protected def drainToJavaOutputStream(stream: Stream[Byte], output: java.io.OutputStream, flushChunks: Boolean): IO[Unit]

  protected def startFiber[A](f: IO[A]): IO[Fiber[A]]

  protected implicit class IOOps[A](io: IO[A]) {
    def map[B](f: A => B): IO[B] = ioMap(io, f)
    def flatMap[B](f: A => IO[B]): IO[B] = ioFlatMap(io, f)
  }

  protected implicit class ResourceOps[A](r: Resource[A]) {
    def use[B](f: A => IO[B]): IO[B] = useResource(r, f)
  }

  protected implicit class FiberOps[A](f: Fiber[A]) {
    def cancel: IO[Unit] = cancelFiber(f)
    def join: IO[A] = joinFiber(f)
  }

  protected implicit class StreamOps[A](s: Stream[A]) {
    def drain: IO[Unit] = drainStream(s)
    def toVector: IO[Vector[A]] = streamToVector(s)
    def fold[B](init: B, f: (B, A) => B): IO[B] = foldStream(s, init, f)
    def through[B](pipe: Pipe[A, B]): Stream[B] = streamThrough(s, pipe)
    def run(sink: Sink[A]): IO[Unit] = runStreamTo(s, sink)
  }

  protected implicit class MonoidStreamOps[A : Monoid](s: Stream[A]) {
    def foldMonoid: IO[A] = foldMonoidStream(s)
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
