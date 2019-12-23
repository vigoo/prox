package io.github.vigoo.prox

import cats.Applicative
import cats.effect._
import fs2._

import scala.language.higherKinds

object syntax {

  // TODO: support any E?
  implicit class ProcessPiping[F[_] : Concurrent, O1, P1[_] <: Process[F, _, _]](process: Process[F, O1, Unit] with RedirectableOutput[F, P1]) {

    // TODO: do not allow pre-redirected IO
    def |[O2, P2 <: Process[F, O2, Unit]](other: Process[F, O2, Unit] with RedirectableInput[F, P2] with RedirectableOutput[F, Process[F, *, Unit]]): ProcessGroup.ProcessGroupImpl[F, O2] = {

      val channel = identity[Stream[F, Byte]] _ // TODO: customizable
      val p1 = process.connectOutput(OutputStream(channel, (stream: Stream[F, Byte]) => Applicative[F].pure(stream)))
        .asInstanceOf[Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]]]// TODO: try to get rid of this

      ProcessGroup.ProcessGroupImpl(
        p1,
        List.empty,
        other
      )
    }
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