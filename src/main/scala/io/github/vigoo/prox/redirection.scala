package io.github.vigoo.prox

import java.nio.file.Path

import cats.Applicative
import cats.effect._
import cats.kernel.Monoid
import fs2._

import scala.language.higherKinds


// Redirection is an extra capability
trait RedirectableOutput[F[_], +P[_] <: Process[F, _, _]] {
  implicit val concurrent: Concurrent[F]

  def connectOutput[R <: OutputRedirection[F], O](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, O]): P[O]

  def >(sink: Pipe[F, Byte, Unit]): P[Unit] =
    toSink(sink)

  def toSink(sink: Pipe[F, Byte, Unit]): P[Unit] =
    connectOutput(OutputStream(sink, (s: Stream[F, Unit]) => s.compile.drain))

  // Note: these operators can be merged with > with further type classes and implicit prioritization
  def >#[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
    toFoldMonoid(pipe)

  def toFoldMonoid[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
    connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.foldMonoid))

  def >?[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
    toVector(pipe)

  def toVector[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
    connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.toVector))

  def drainOutput[O](pipe: Pipe[F, Byte, O]): P[Unit] =
    connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.drain))

  def foldOutput[O, R](pipe: Pipe[F, Byte, O], init: R, fn: (R, O) => R): P[R] =
    connectOutput(OutputStream(pipe, (s: Stream[F, O]) => s.compile.fold(init)(fn)))

  def >(path: Path): P[Unit] =
    toFile(path)

  def toFile(path: Path): P[Unit] =
    connectOutput(OutputFile[F](path, append = false))

  def >>(path: Path): P[Unit] =
    appendToFile(path)

  def appendToFile(path: Path): P[Unit] =
    connectOutput(OutputFile[F](path, append = true))
}

trait RedirectableError[F[_], +P[_] <: Process[F, _, _]] {
  implicit val concurrent: Concurrent[F]

  def connectError[R <: OutputRedirection[F], E](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, E]): P[E]

  def !>(sink: Pipe[F, Byte, Unit]): P[Unit] =
    errorToSink(sink)

  def errorToSink(sink: Pipe[F, Byte, Unit]): P[Unit] =
    connectError(OutputStream(sink, (s: Stream[F, Unit]) => s.compile.drain))

  // Note: these operators can be merged with > with further type classes and implicit prioritization
  def !>#[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
    errorToFoldMonoid(pipe)

  def errorToFoldMonoid[O: Monoid](pipe: Pipe[F, Byte, O]): P[O] =
    connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.foldMonoid))

  def !>?[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
    errorToVector(pipe)

  def errorToVector[O](pipe: Pipe[F, Byte, O]): P[Vector[O]] =
    connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.toVector))

  def drainError[O](pipe: Pipe[F, Byte, O]): P[Unit] =
    connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.drain))

  def foldError[O, R](pipe: Pipe[F, Byte, O], init: R, fn: (R, O) => R): P[R] =
    connectError(OutputStream(pipe, (s: Stream[F, O]) => s.compile.fold(init)(fn)))

  def !>(path: Path): P[Unit] =
    errorToFile(path)

  def errorToFile(path: Path): P[Unit] =
    connectError(OutputFile[F](path, append = false))

  def !>>(path: Path): P[Unit] =
    appendErrorToFile(path)

  def appendErrorToFile(path: Path): P[Unit] =
    connectError(OutputFile[F](path, append = true))
}

trait RedirectableInput[F[_], +P] {
  def connectInput(source: InputRedirection[F]): P

  def <(path: Path): P =
    fromFile(path)

  def fromFile(path: Path): P =
    connectInput(InputFile(path))

  def <(stream: Stream[F, Byte]): P =
    fromStream(stream, flushChunks = false)

  def !<(stream: Stream[F, Byte]): P =
    fromStream(stream, flushChunks = true)

  def fromStream(stream: Stream[F, Byte], flushChunks: Boolean): P =
    connectInput(InputStream(stream, flushChunks))
}

sealed trait OutputRedirection[F[_]]

case class StdOut[F[_]]() extends OutputRedirection[F]

case class OutputFile[F[_]](path: Path, append: Boolean) extends OutputRedirection[F]

case class OutputStream[F[_], O, OR](pipe: Pipe[F, Byte, O],
                                     runner: Stream[F, O] => F[OR],
                                     chunkSize: Int = 8192) extends OutputRedirection[F]

sealed trait InputRedirection[F[_]]

case class StdIn[F[_]]() extends InputRedirection[F]

case class InputFile[F[_]](path: Path) extends InputRedirection[F]

case class InputStream[F[_]](stream: Stream[F, Byte], flushChunks: Boolean) extends InputRedirection[F]

// Dependent typing helper
trait OutputRedirectionType[F[_], R] {
  type Out

  def runner(of: R)(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[F]): F[Out]
}

object OutputRedirectionType {
  type Aux[F[_], R, O] = OutputRedirectionType[F, R] {
    type Out = O
  }

  implicit def outputRedirectionTypeOfStdOut[F[_] : Applicative]: Aux[F, StdOut[F], Unit] = new OutputRedirectionType[F, StdOut[F]] {
    override type Out = Unit

    override def runner(of: StdOut[F])(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[F]): F[Unit] = Applicative[F].unit
  }

  implicit def outputRedirectionTypeOfFile[F[_] : Applicative]: Aux[F, OutputFile[F], Unit] = new OutputRedirectionType[F, OutputFile[F]] {
    override type Out = Unit

    override def runner(of: OutputFile[F])(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[F]): F[Unit] = Applicative[F].unit
  }

  implicit def outputRedirectionTypeOfStream[F[_] : Applicative : Sync, O, OR]: Aux[F, OutputStream[F, O, OR], OR] = new OutputRedirectionType[F, OutputStream[F, O, OR]] {
    override type Out = OR

    override def runner(of: OutputStream[F, O, OR])(nativeStream: java.io.InputStream, blocker: Blocker, contextShift: ContextShift[F]): F[OR] = {
      implicit val cs: ContextShift[F] = contextShift
      of.runner(
        io.readInputStream[F](
          Applicative[F].pure(nativeStream),
          of.chunkSize,
          closeAfterUse = true,
          blocker = blocker)
          .through(of.pipe))
    }
  }
}