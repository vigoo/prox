package io.github.vigoo.prox

import cats.effect.IO
import shapeless._
import shapeless.ops.hlist.{IsHCons, Last, Prepend, Tupler}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

trait Start[PN <: ProcessNode[_, _, _]] {
  type RunningProcesses
  type RunningProcessList <: HList

  def apply(process: PN)(implicit executionContext: ExecutionContext): IO[RunningProcesses]
  def toHList(process: PN)(implicit executionContext: ExecutionContext): IO[RunningProcessList]
}

object Start {
  type Aux[PN <: ProcessNode[_, _, _], RP, RPL <: HList] = Start[PN] {
    type RunningProcesses = RP
    type RunningProcessList = RPL
  }
  def apply[PN <: ProcessNode[_, _, _], RP, RPL <: HList](implicit start: Start.Aux[PN, RP, RPL]) : Aux[PN, RP, RPL] = start

  implicit def startProcess[IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState]:
    Aux[Process[IRS, ORS, ERS], RunningProcess, RunningProcess :: HNil] =

    new Start[Process[IRS, ORS, ERS]] {
      override type RunningProcesses = RunningProcess
      override type RunningProcessList = RunningProcess :: HNil
      override def apply(process: Process[IRS, ORS, ERS])(implicit executionContext: ExecutionContext): IO[RunningProcess] = {
        def withWorkingDirectory(builder: ProcessBuilder): ProcessBuilder =
          process.workingDirectory match {
            case Some(directory) => builder.directory(directory.toFile)
            case None => builder
          }

        val builder = withWorkingDirectory(new ProcessBuilder((process.command :: process.arguments).asJava))
        builder.redirectInput(process.inputSource.toRedirect)
        builder.redirectOutput(process.outputTarget.toRedirect)
        builder.redirectError(process.errorTarget.toRedirect)
        for {
          proc <- IO {
            builder.start
          }
          inputStream = process.inputSource.connect(proc)
          outputStream = process.outputTarget.connect(proc)
          errorStream = process.errorTarget.connect(proc)
        } yield new WrappedProcess(proc, inputStream, outputStream, errorStream)
      }

      override def toHList(process: Process[IRS, ORS, ERS])(implicit executionContext: ExecutionContext): IO[RunningProcessList] =
        apply(process).map(runningProcess => runningProcess :: HNil)
    }

  implicit def startPipedProcess[
    PN1 <: ProcessNode[_, _, _],
    PN2 <: ProcessNode[_, _, _],
    IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState,
    RP1, RP2, RPL1 <: HList, RPL2 <: HList, RPL <: HList,
    RPT,
    RP1Last <: RunningProcess, RP2Head <: RunningProcess, RP2Tail <: HList]
  (implicit
   start1: Start.Aux[PN1, RP1, RPL1],
   start2: Start.Aux[PN2, RP2, RPL2],
   last1: Last.Aux[RPL1, RP1Last],
   hcons2: IsHCons.Aux[RPL2, RP2Head, RP2Tail],
   prepend: Prepend.Aux[RPL1, RPL2, RPL],
   tupler: Tupler.Aux[RPL, RPT]): Aux[PipedProcess[PN1, PN2, IRS, ORS, ERS], RPT, RPL] =
    new Start[PipedProcess[PN1, PN2, IRS, ORS, ERS]] {
      override type RunningProcesses = RPT
      override type RunningProcessList = RPL

      override def apply(pipe: PipedProcess[PN1, PN2, IRS, ORS, ERS])(implicit executionContext: ExecutionContext): IO[RPT] = {
        toHList(pipe).map(_.tupled)
      }

      override def toHList(pipe: PipedProcess[PN1, PN2, IRS, ORS, ERS])(implicit executionContext: ExecutionContext): IO[RPL] = {
        import syntax._

        pipe.from.startHL.flatMap { runningSourceProcesses =>
          val runningFrom = runningSourceProcesses.last
          val to = pipe.createTo(PipeConstruction(runningFrom.output, runningFrom.error))
          to.startHL.flatMap { runningTargetProcesses =>
            val runningTo = runningTargetProcesses.head
            runningTo.input.run.map { _ =>
              runningSourceProcesses ::: runningTargetProcesses
            }
          }
        }
      }
    }
}

object syntax {
  implicit class ProcessNodeOutputRedirect[PN <: ProcessNode[_, NotRedirected, _]](processNode: PN) {
    def >[To: CanBeProcessOutputTarget](to: To): PN#RedirectedOutput = {
      processNode.unsafeChangeRedirectedOutput(to)
    }
  }

  implicit class ProcessNodeInputRedirect[PN <: ProcessNode[NotRedirected, _, _]](processNode: PN) {
    def <[From: CanBeProcessInputSource](from: From): PN#RedirectedInput = {
      processNode.unsafeChangeRedirectedInput(from)
    }
  }

  implicit class ProcessNodeErrorRedirect[PN <: ProcessNode[_, _, NotRedirected]](processNode: PN) {
    def redirectErrorTo[To: CanBeProcessErrorTarget](to: To): PN#RedirectedError = {
      processNode.unsafeChangeRedirectedError(to)
    }
  }

  implicit class ProcessOps[PN <: ProcessNode[_, _, _]](processNode: PN) {
    def start[RP, RPL <: HList](implicit start: Start.Aux[PN, RP, RPL], executionContext: ExecutionContext): IO[RP] =
      start(processNode)
    def startHL[RP, RPL <: HList](implicit start: Start.Aux[PN, RP, RPL], executionContext: ExecutionContext): IO[RPL] =
      start.toHList(processNode)
  }
}
