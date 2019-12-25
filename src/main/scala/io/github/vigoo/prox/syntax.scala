package io.github.vigoo.prox

import cats.Applicative
import cats.effect._
import fs2._

import scala.language.higherKinds

object syntax {

  implicit class ProcessPiping[F[_] : Concurrent](process: Process.UnboundProcess[F]) {

    def pipeInto(other: Process.UnboundProcess[F],
                 channel: Pipe[F, Byte, Byte]): ProcessGroup.ProcessGroupImpl[F, Unit] = {

      val p1 = process.connectOutput(OutputStream(channel, (stream: Stream[F, Byte]) => Applicative[F].pure(stream)))

      ProcessGroup.ProcessGroupImpl(
        p1,
        List.empty,
        other
      )
    }

    def |(other: Process.UnboundProcess[F]): ProcessGroup.ProcessGroupImpl[F, Unit] =
      pipeInto(other, identity)

    def via(channel: Pipe[F, Byte, Byte]): PipeBuilderSyntax[F, ProcessGroup.ProcessGroupImpl[F, *]] =
      new PipeBuilderSyntax(new PipeBuilder[F, ProcessGroup.ProcessGroupImpl[F, *]] {
        override def build(other: Process.UnboundProcess[F], channel: Pipe[F, Byte, Byte]): ProcessGroup.ProcessGroupImpl[F, Unit] =
          process.pipeInto(other, channel)
      }, channel)
  }

  trait PipeBuilder[F[_], P[_]] {
    def build(other: Process.UnboundProcess[F],
              channel: Pipe[F, Byte, Byte]): P[Unit]
  }

  class PipeBuilderSyntax[F[_], P[_]](builder: PipeBuilder[F, P], channel: Pipe[F, Byte, Byte]) {
    def to(other: Process.UnboundProcess[F]): P[Unit] =
      builder.build(other, channel)

  }

  object cats {

    implicit class ProcessStringContextIO(ctx: StringContext)
                                         (implicit contextShift: ContextShift[IO]) {
      def proc(args: Any*): Process.ProcessImpl[IO, Unit, Unit] = {
        val staticParts = ctx.parts.map(Left.apply)
        val injectedParts = args.map(Right.apply)
        val parts = (injectedParts zip staticParts).flatMap { case (a, b) => List(b, a) }
        val words = parts.flatMap {
          case Left(value) => value.trim.split(' ')
          case Right(value) => List(value.toString)
        }.toList
        words match {
          case head :: remaining =>
            Process[IO](head, remaining)(Sync[IO], Concurrent[IO])
          case Nil =>
            throw new IllegalArgumentException(s"The proc interpolator needs at least a process name")
        }
      }
    }

  }

}