package io.github.vigoo.prox

import cats.Applicative
import cats.effect._
import fs2._

import scala.language.higherKinds
import _root_.io.github.vigoo.prox.syntax._

// TODO: how to bind error streams. compound error output indexed by process ids?

trait RunningProcessGroup[F[_], O, E] {
  val runningOutput: Fiber[F, O]

  def kill(): F[ProcessResult[O, Unit]]

  def terminate(): F[ProcessResult[O, Unit]]

  def waitForExit(): F[ProcessResult[O, Unit]]
}

trait ProcessGroup[F[_], O, E] extends ProcessLike[F] {
  implicit val concurrent: Concurrent[F]

  val firstProcess: Process[F, Stream[F, Byte], E]
  val innerProcesses: List[Process.UnboundIProcess[F, Stream[F, Byte], E]]
  val lastProcess: Process.UnboundIProcess[F, O, E]

  def start(blocker: Blocker)(implicit runner: ProcessRunner[F]): Resource[F, Fiber[F, ProcessResult[O, Unit]]] =
    runner.start(this, blocker)

  def run(blocker: Blocker)(implicit runner: ProcessRunner[F]): F[ProcessResult[O, Unit]] =
    start(blocker).use(_.join)
}

object ProcessGroup {

  case class ProcessGroupImplIOE[F[_], O, E](override val firstProcess: Process[F, Stream[F, Byte], E],
                                             override val innerProcesses: List[Process.UnboundIProcess[F, Stream[F, Byte], E]],
                                             override val lastProcess: Process.UnboundIProcess[F, O, E])
                                            (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O, E] {
  }

  case class ProcessGroupImplIO[F[_], O](override val firstProcess: Process.UnboundEProcess[F, Stream[F, Byte]],
                                         override val innerProcesses: List[Process.UnboundIEProcess[F, Stream[F, Byte]]],
                                         override val lastProcess: Process.UnboundIEProcess[F, O])
                                        (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O, Unit]
      with RedirectableErrors[F, ProcessGroupImplIOE[F, O, *]] {

    override def connectErrors[R <: GroupErrorRedirection[F], OR <: OutputRedirection[F], E](target: R)
                                                                                            (implicit groupErrorRedirectionType: GroupErrorRedirectionType.Aux[F, R, OR, E],
                                                                                             outputRedirectionType: OutputRedirectionType.Aux[F, OR, E]): ProcessGroupImplIOE[F, O, E] =
      ProcessGroupImplIOE(
        firstProcess.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, firstProcess)),
        innerProcesses.map(p => p.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, p))),
        lastProcess.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, lastProcess)),
      )
  }

  case class ProcessGroupImplIE[F[_], E](override val firstProcess: Process[F, Stream[F, Byte], E],
                                         override val innerProcesses: List[Process.UnboundIProcess[F, Stream[F, Byte], E]],
                                         override val lastProcess: Process.UnboundIOProcess[F, E])
                                        (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, Unit, E]
      with RedirectableOutput[F, ProcessGroupImplIOE[F, *, E]] {

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessGroupImplIOE[F, RO, E] = {
      ProcessGroupImplIOE(
        firstProcess,
        innerProcesses,
        lastProcess.connectOutput(target)
      )
    }
  }

  case class ProcessGroupImplOE[F[_], O, E](override val firstProcess: Process.UnboundIProcess[F, Stream[F, Byte], E],
                                            override val innerProcesses: List[Process.UnboundIProcess[F, Stream[F, Byte], E]],
                                            override val lastProcess: Process.UnboundIProcess[F, O, E])
                                           (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O, E]
      with RedirectableInput[F, ProcessGroupImplIOE[F, O, E]] {

    override def connectInput(source: InputRedirection[F]): ProcessGroupImplIOE[F, O, E] = {
      ProcessGroupImplIOE(
        firstProcess.connectInput(source),
        innerProcesses,
        lastProcess
      )
    }
  }

  case class ProcessGroupImplI[F[_]](override val firstProcess: Process.UnboundEProcess[F, Stream[F, Byte]],
                                     override val innerProcesses: List[Process.UnboundIEProcess[F, Stream[F, Byte]]],
                                     override val lastProcess: Process.UnboundProcess[F])
                                    (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, Unit, Unit]
      with RedirectableOutput[F, ProcessGroupImplIO[F, *]]
      with RedirectableErrors[F, ProcessGroupImplIE[F, *]] {

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessGroupImplIO[F, RO] = {
      ProcessGroupImplIO(
        firstProcess,
        innerProcesses,
        lastProcess.connectOutput(target)
      )
    }

    override def connectErrors[R <: GroupErrorRedirection[F], OR <: OutputRedirection[F], E](target: R)
                                                                                            (implicit groupErrorRedirectionType: GroupErrorRedirectionType.Aux[F, R, OR, E],
                                                                                             outputRedirectionType: OutputRedirectionType.Aux[F, OR, E]): ProcessGroupImplIE[F, E] =
      ProcessGroupImplIE(
        firstProcess.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, firstProcess)),
        innerProcesses.map(p => p.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, p))),
        lastProcess.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, lastProcess)),
      )
  }

  case class ProcessGroupImplO[F[_], O](override val firstProcess: Process.UnboundIEProcess[F, Stream[F, Byte]],
                                        override val innerProcesses: List[Process.UnboundIEProcess[F, Stream[F, Byte]]],
                                        override val lastProcess: Process.UnboundIEProcess[F, O])
                                       (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O, Unit]
      with RedirectableInput[F, ProcessGroupImplIO[F, O]]
      with RedirectableErrors[F, ProcessGroupImplOE[F, O, *]] {

    override def connectInput(source: InputRedirection[F]): ProcessGroupImplIO[F, O] = {
      ProcessGroupImplIO(
        firstProcess.connectInput(source),
        innerProcesses,
        lastProcess
      )
    }

    override def connectErrors[R <: GroupErrorRedirection[F], OR <: OutputRedirection[F], E](target: R)
                                                                                            (implicit groupErrorRedirectionType: GroupErrorRedirectionType.Aux[F, R, OR, E],
                                                                                             outputRedirectionType: OutputRedirectionType.Aux[F, OR, E]): ProcessGroupImplOE[F, O, E] =
      ProcessGroupImplOE(
        firstProcess.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, firstProcess)),
        innerProcesses.map(p => p.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, p))),
        lastProcess.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, lastProcess)),
      )
  }

  case class ProcessGroupImplE[F[_], E](override val firstProcess: Process.UnboundIProcess[F, Stream[F, Byte], E],
                                        override val innerProcesses: List[Process.UnboundIProcess[F, Stream[F, Byte], E]],
                                        override val lastProcess: Process.UnboundIOProcess[F, E])
                                       (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, Unit, E]
      with RedirectableOutput[F, ProcessGroupImplOE[F, *, E]]
      with RedirectableInput[F, ProcessGroupImplIE[F, E]] {

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessGroupImplOE[F, RO, E] = {
      ProcessGroupImplOE(
        firstProcess,
        innerProcesses,
        lastProcess.connectOutput(target)
      )
    }

    override def connectInput(source: InputRedirection[F]): ProcessGroupImplIE[F, E] = {
      ProcessGroupImplIE(
        firstProcess.connectInput(source),
        innerProcesses,
        lastProcess
      )
    }
  }

  case class ProcessGroupImpl[F[_]](override val firstProcess: Process.UnboundIEProcess[F, Stream[F, Byte]],
                                    override val innerProcesses: List[Process.UnboundIEProcess[F, Stream[F, Byte]]],
                                    override val lastProcess: Process.UnboundProcess[F])
                                   (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, Unit, Unit]
      with RedirectableOutput[F, ProcessGroupImplO[F, *]]
      with RedirectableInput[F, ProcessGroupImplI[F]]
      with RedirectableErrors[F, ProcessGroupImplE[F, *]] {

    def pipeInto(other: Process.UnboundProcess[F],
                 channel: Pipe[F, Byte, Byte]): ProcessGroupImpl[F] = {
      val pl1 = lastProcess.connectOutput(OutputStream(channel, (stream: Stream[F, Byte]) => Applicative[F].pure(stream)))

      copy(
        innerProcesses = pl1 :: innerProcesses,
        lastProcess = other
      )
    }

    def |(other: Process.UnboundProcess[F]): ProcessGroupImpl[F] =
      pipeInto(other, identity)


    def via(channel: Pipe[F, Byte, Byte]): PipeBuilderSyntax[F, ProcessGroupImpl[F]] =
      new PipeBuilderSyntax(new PipeBuilder[F, ProcessGroupImpl[F]] {
        override def build(other: Process.UnboundProcess[F], channel: Pipe[F, Byte, Byte]): ProcessGroupImpl[F] =
          ProcessGroupImpl.this.pipeInto(other, channel)
      }, channel)

    override def connectInput(source: InputRedirection[F]): ProcessGroupImplI[F] =
      ProcessGroupImplI(
        firstProcess.connectInput(source),
        innerProcesses,
        lastProcess
      )

    override def connectErrors[R <: GroupErrorRedirection[F], OR <: OutputRedirection[F], E](target: R)
                                                                                            (implicit groupErrorRedirectionType: GroupErrorRedirectionType.Aux[F, R, OR, E],
                                                                                             outputRedirectionType: OutputRedirectionType.Aux[F, OR, E]): ProcessGroupImplE[F, E] = {
      ProcessGroupImplE(
        firstProcess.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, firstProcess)),
        innerProcesses.map(p => p.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, p))),
        lastProcess.connectError(groupErrorRedirectionType.toOuptutRedirectionType(target, lastProcess)),
      )
    }

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessGroupImplO[F, RO] = {
      ProcessGroupImplO(
        firstProcess,
        innerProcesses,
        lastProcess.connectOutput(target)
      )
    }
  }

}
