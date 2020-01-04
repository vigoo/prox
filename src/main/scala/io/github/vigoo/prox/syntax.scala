package io.github.vigoo.prox

import cats.Applicative
import cats.effect._
import fs2._

import scala.language.higherKinds

object syntax {

  implicit class ProcessPiping[F[_] : Concurrent](process: Process.UnboundProcess[F]) {

    def pipeInto(other: Process.UnboundProcess[F],
                 channel: Pipe[F, Byte, Byte]): ProcessGroup.ProcessGroupImpl[F] = {

      val p1 = process.connectOutput(OutputStream(channel, (stream: Stream[F, Byte]) => Applicative[F].pure(stream)))

      ProcessGroup.ProcessGroupImpl(
        p1,
        List.empty,
        other,
        List(other, process)
      )
    }

    def |(other: Process.UnboundProcess[F]): ProcessGroup.ProcessGroupImpl[F] =
      pipeInto(other, identity)

    def via(channel: Pipe[F, Byte, Byte]): PipeBuilderSyntax[F, ProcessGroup.ProcessGroupImpl[F]] =
      new PipeBuilderSyntax(new PipeBuilder[F, ProcessGroup.ProcessGroupImpl[F]] {
        override def build(other: Process.UnboundProcess[F], channel: Pipe[F, Byte, Byte]): ProcessGroup.ProcessGroupImpl[F] =
          process.pipeInto(other, channel)
      }, channel)
  }

  trait PipeBuilder[F[_], P] {
    def build(other: Process.UnboundProcess[F],
              channel: Pipe[F, Byte, Byte]): P
  }

  class PipeBuilderSyntax[F[_], P](builder: PipeBuilder[F, P], channel: Pipe[F, Byte, Byte]) {
    def to(other: Process.UnboundProcess[F]): P =
      builder.build(other, channel)

  }

  object catsInterpolation {

    implicit class ProcessStringContextIO(ctx: StringContext)
                                         (implicit contextShift: ContextShift[IO]) {
      def proc(args: Any*): Process.ProcessImpl[IO] = {
        val staticParts = ctx.parts.map(Left.apply)
        val injectedParts = args.map(Right.apply)
        val parts = staticParts.zipAll(injectedParts, Left(""), Right("")).flatMap { case (a, b) => List(a, b) }
        val words = parts.flatMap {
          case Left(value) => value.trim.split(' ')
          case Right(value) => List(value.toString)
        }.filter(_.nonEmpty).toList
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