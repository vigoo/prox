package io.github.vigoo.prox

import cats.Applicative
import cats.effect._
import fs2._

import scala.language.higherKinds

object syntax {

  /** Extension methods for unbound processes enabling the creation of process groups */
  implicit class ProcessPiping[F[_] : Concurrent](process: Process.UnboundProcess[F]) {

    /**
      * Attaches the output of this process to an other process' input
      *
      * Use the [[|]] or the [[via]] methods instead for more readability.
      *
      * @param other The other process
      * @param channel Pipe between the two processes
      * @return Returns a [[ProcessGroup]]
      */
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

    /**
      * Attaches the output of this process to an other process' input
      *
      * @param other The other process
      * @return Returns a [[ProcessGroup]]
      */
    def |(other: Process.UnboundProcess[F]): ProcessGroup.ProcessGroupImpl[F] =
      pipeInto(other, identity)

    /**
      * Attaches the output of this process to an other process' input with a custom channel
      *
      * There is a syntax helper step to allow the following syntax:
      * {{{
      *   val processGroup = process1.via(channel).to(process2)
      * }}}
      *
      * @param channel Pipe between the two processes
      * @return Returns a syntax helper trait that has a [[PipeBuilderSyntax.to]] method to finish the construction
      */
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

  /**
    * String interpolator for an alternative of [[Process.apply]]
    *
    * {{{
    * val process = proc"ls -hal $dir"
    * }}}
    *
    * As it cannot be parametric in the effect type, it is bound to cats-effect [[IO]]
    */
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