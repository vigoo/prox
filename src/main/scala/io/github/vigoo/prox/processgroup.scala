package io.github.vigoo.prox

import cats.Applicative
import cats.effect._
import fs2._

import scala.language.higherKinds


// TODO: how to bind error streams. compound error output indexed by process ids?

trait PipingSupport[F[_]] {
  def |[O2, P2 <: Process[F, O2, Unit]](other: Process[F, O2, Unit] with RedirectableInput[F, P2] with RedirectableOutput[F, Process[F, *, Unit]]): ProcessGroup[F, O2]
}

trait ProcessGroup[F[_], O] extends ProcessLike[F] {
  implicit val concurrent: Concurrent[F]

  val firstProcess: Process[F, Stream[F, Byte], Unit]
  val innerProcesses: List[Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]]]
  val lastProcess: Process[F, O, Unit] with RedirectableInput[F, Process[F, O, Unit]]

  def start(blocker: Blocker)(implicit runner: ProcessRunner[F]): Resource[F, Fiber[F, ProcessResult[O, Unit]]] =
    runner.start(this, blocker)

  def run(blocker: Blocker)(implicit runner: ProcessRunner[F]): F[ProcessResult[O, Unit]] =
    start(blocker).use(_.join)
}

object ProcessGroup {

  case class ProcessGroupImplIO[F[_], O](override val firstProcess: Process[F, Stream[F, Byte], Unit],
                                         override val innerProcesses: List[Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]]],
                                         override val lastProcess: Process[F, O, Unit] with RedirectableInput[F, Process[F, O, Unit]])
                                        (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O] {
  }


  case class ProcessGroupImplI[F[_], O](override val firstProcess: Process[F, Stream[F, Byte], Unit],
                                        override val innerProcesses: List[Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]]],
                                        override val lastProcess: Process[F, O, Unit] with RedirectableInput[F, Process[F, O, Unit]] with RedirectableOutput[F, Process[F, *, Unit]])
                                       (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O]
    with RedirectableOutput[F, ProcessGroupImplIO[F, *]] {

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessGroupImplIO[F, RO] = {
      ProcessGroupImplIO(
        firstProcess,
        innerProcesses,
        lastProcess.connectOutput(target).asInstanceOf[Process[F, RO, Unit] with RedirectableInput[F, Process[F, RO, Unit]]] // TODO: try to get rid of this.
      )
    }
  }

  case class ProcessGroupImplO[F[_], O](override val firstProcess: Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]],
                                        override val innerProcesses: List[Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]]],
                                        override val lastProcess: Process[F, O, Unit] with RedirectableInput[F, Process[F, O, Unit]])
                                       (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O]
    with RedirectableInput[F, ProcessGroupImplIO[F, O]] {

    override def connectInput(source: InputRedirection[F]): ProcessGroupImplIO[F, O] = {
      ProcessGroupImplIO(
        firstProcess.connectInput(source),
        innerProcesses,
        lastProcess
      )
    }
  }

  case class ProcessGroupImpl[F[_], O](override val firstProcess: Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]],
                                       override val innerProcesses: List[Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]]],
                                       override val lastProcess: Process[F, O, Unit] with RedirectableInput[F, Process[F, O, Unit]] with RedirectableOutput[F, Process[F, *, Unit]])
                                      (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O]
      with PipingSupport[F]
      with RedirectableOutput[F, ProcessGroupImplO[F, *]]
      with RedirectableInput[F, ProcessGroupImplI[F, O]] {

    override def |[O2, P2 <: Process[F, O2, Unit]](other: Process[F, O2, Unit] with RedirectableInput[F, P2] with RedirectableOutput[F, Process[F, *, Unit]]): ProcessGroupImpl[F, O2] = {
      val channel = identity[Stream[F, Byte]] _ // TODO: customizable
      val pl1 = lastProcess.connectOutput(OutputStream(channel, (stream: Stream[F, Byte]) => Applicative[F].pure(stream)))
        .asInstanceOf[Process[F, Stream[F, Byte], Unit] with RedirectableInput[F, Process[F, Stream[F, Byte], Unit]]] // TODO: try to get rid of this
      copy(
        innerProcesses = pl1 :: innerProcesses,
        lastProcess = other
      )
    }

    override def connectInput(source: InputRedirection[F]): ProcessGroupImplI[F, O] =
      ProcessGroupImplI(
        firstProcess.connectInput(source),
        innerProcesses,
        lastProcess
      )

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessGroupImplO[F, RO] = {
      ProcessGroupImplO(
        firstProcess,
        innerProcesses,
        lastProcess.connectOutput(target).asInstanceOf[Process[F, RO, Unit] with RedirectableInput[F, Process[F, RO, Unit]]]) // TODO: try to get rid of this
    }
  }

}
