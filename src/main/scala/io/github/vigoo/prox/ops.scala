package io.github.vigoo.prox

import cats.effect.IO
import cats.kernel.Monoid
import fs2._
import shapeless._
import shapeless.ops.hlist.{IsHCons, Last, Prepend, Tupler}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.language.higherKinds

trait Start[PN <: ProcessNode[_, _, _, _, _]] {
  type RunningProcesses
  type RunningProcessList <: HList

  def apply(process: PN, dontStartOutput: Boolean = false)(implicit executionContext: ExecutionContext): IO[RunningProcesses]
  def toHList(process: PN, dontStartOutput: Boolean = false)(implicit executionContext: ExecutionContext): IO[RunningProcessList]
}

object Start {
  type Aux[PN <: ProcessNode[_, _, _, _, _], RP, RPL <: HList] = Start[PN] {
    type RunningProcesses = RP
    type RunningProcessList = RPL
  }
  def apply[PN <: ProcessNode[_, _, _, _, _], RP, RPL <: HList](implicit start: Start.Aux[PN, RP, RPL]) : Aux[PN, RP, RPL] = start

  implicit def startProcess[Out, Err, OutResult, ErrResult, IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState]
    (implicit outResultMonoid: Monoid[OutResult]):
    Aux[Process[Out, Err, OutResult, ErrResult, IRS, ORS, ERS], RunningProcess[Out, OutResult, ErrResult], RunningProcess[Out, OutResult, ErrResult] :: HNil] =

    new Start[Process[Out, Err, OutResult, ErrResult, IRS, ORS, ERS]] {
      override type RunningProcesses = RunningProcess[Out, OutResult, ErrResult]
      override type RunningProcessList = RunningProcess[Out, OutResult, ErrResult] :: HNil
      override def apply(process: Process[Out, Err, OutResult, ErrResult, IRS, ORS, ERS], dontStartOutput: Boolean)
                        (implicit executionContext: ExecutionContext): IO[RunningProcess[Out, OutResult, ErrResult]] = {
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
          proc <- IO(builder.start)
          inputStream = process.inputSource.connect(proc)
          outputStream = process.outputTarget.connect(proc)
          errorStream = process.errorTarget.connect(proc)
          runningInput <- process.inputSource.run(inputStream)
          runningOutput <- if (dontStartOutput) { IO(IO(outResultMonoid.empty)) } else { process.outputTarget.run(outputStream) }
          runningError <- process.errorTarget.run(errorStream)
        } yield new WrappedProcess(
            proc,
            if (dontStartOutput) Some(outputStream) else None,
            runningInput,
            runningOutput,
            runningError)
      }

      override def toHList(process: Process[Out, Err, OutResult, ErrResult, IRS, ORS, ERS], dontStartOutput: Boolean)(implicit executionContext: ExecutionContext): IO[RunningProcessList] =
        apply(process, dontStartOutput).map(runningProcess => runningProcess :: HNil)
    }

  implicit def startPipedProcess[
    Out, Err,
    PN1 <: ProcessNode[_, _, _, _, _],
    PN2 <: ProcessNode[_, _, _, _, _],
    IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState,
    RP1, RPL1 <: HList, RP1Last <: RunningProcess[_, _, _],
    RP2, RPL2 <: HList, RP2Head <: RunningProcess[_, _, _], RP2Tail <: HList,
    RPT, RPL <: HList]
    (implicit
     start1: Start.Aux[PN1, RP1, RPL1],
     start2: Start.Aux[PN2, RP2, RPL2],
     last1: Last.Aux[RPL1, RP1Last],
     rp1LastType: RP1Last <:< RunningProcess[Byte, _, _],
     hcons2: IsHCons.Aux[RPL2, RP2Head, RP2Tail],
     prepend: Prepend.Aux[RPL1, RPL2, RPL],
     tupler: Tupler.Aux[RPL, RPT]):
    Aux[PipedProcess[Out, Err, Byte, PN1, PN2, IRS, ORS, ERS], RPT, RPL] =
    new Start[PipedProcess[Out, Err, Byte, PN1, PN2, IRS, ORS, ERS]] {
      override type RunningProcesses = RPT
      override type RunningProcessList = RPL

      override def apply(pipe: PipedProcess[Out, Err, Byte, PN1, PN2, IRS, ORS, ERS], dontStartOutput: Boolean)
                        (implicit executionContext: ExecutionContext): IO[RPT] = {
        toHList(pipe, dontStartOutput).map(_.tupled)
      }

      override def toHList(pipe: PipedProcess[Out, Err, Byte, PN1, PN2, IRS, ORS, ERS], dontStartOutput: Boolean)
                          (implicit executionContext: ExecutionContext): IO[RPL] = {
        start1.toHList(pipe.from, dontStartOutput = true).flatMap { runningSourceProcesses =>
          val runningFrom = runningSourceProcesses.last.asInstanceOf[RunningProcess[Byte, _, _]]
          val to = pipe.createTo(PipeConstruction(runningFrom.notStartedOutput.get))
          start2.toHList(to, dontStartOutput).flatMap { runningTargetProcesses =>
            val runningTo = runningTargetProcesses.head
            IO(runningSourceProcesses ::: runningTargetProcesses)
          }
        }
      }
    }
}

trait RedirectInput[PN <: ProcessNode[_, _, NotRedirected, _, _]] {
  type Result <: ProcessNode[_, _, Redirected, _, _]
  def apply[From: CanBeProcessInputSource](process: PN, from: From): Result
}

object RedirectInput {
  type Aux[PN <: ProcessNode[_, _, NotRedirected, _, _], Result0 <: ProcessNode[_, _, Redirected, _, _]] =
    RedirectInput[PN] { type Result = Result0 }

  implicit def redirectProcessInput[Out, Err, OutResult, ErrResult, ORS <: RedirectionState, ERS <: RedirectionState]:
    Aux[Process[Out, Err, OutResult, ErrResult, NotRedirected, ORS, ERS], Process[Out, Err, OutResult, ErrResult, Redirected, ORS, ERS]] =
    new RedirectInput[Process[Out, Err, OutResult, ErrResult, NotRedirected, ORS, ERS]] {
      override type Result = Process[Out, Err, OutResult, ErrResult, Redirected, ORS, ERS]

      override def apply[From: CanBeProcessInputSource](process: Process[Out, Err, OutResult, ErrResult, NotRedirected, ORS, ERS], from: From): Result =
        new Process(
          process.command,
          process.arguments,
          process.workingDirectory,
          implicitly[CanBeProcessInputSource[From]].apply(from),
          process.outputTarget,
          process.errorTarget
        )
    }

  implicit def redirectPipedProcessInput[
    Out, Err, PN1Out,
    PN1 <: ProcessNode[_, _, NotRedirected, _, _],
    PN2 <: ProcessNode[_, _, _, _, _],
    ORS <: RedirectionState, ERS <: RedirectionState,
    PN1Redirected <: ProcessNode[_, _, Redirected, _, _]]
    (implicit redirectPN1Input: RedirectInput.Aux[PN1, PN1Redirected]):
    Aux[PipedProcess[Out, Err, PN1Out, PN1, PN2, NotRedirected, ORS, ERS], PipedProcess[Out, Err, PN1Out, PN1Redirected, PN2, Redirected, ORS, ERS]] =
    new RedirectInput[PipedProcess[Out, Err, PN1Out, PN1, PN2, NotRedirected, ORS, ERS]] {
      override type Result = PipedProcess[Out, Err, PN1Out, PN1Redirected, PN2, Redirected, ORS, ERS]

      override def apply[From: CanBeProcessInputSource](process: PipedProcess[Out, Err, PN1Out, PN1, PN2, NotRedirected, ORS, ERS], from: From): Result = {
        new PipedProcess[Out, Err, PN1Out, PN1Redirected, PN2, Redirected, ORS, ERS](redirectPN1Input(process.from, from), process.createTo)
      }
    }
}

trait RedirectOutput[PN <: ProcessNode[_, _, _, NotRedirected, _], To, NewOut, NewOutResult] {
  type Result <: ProcessNode[NewOut, _, _, Redirected, _]
  def apply(process: PN, to: To)(implicit target: CanBeProcessOutputTarget.Aux[To, NewOut, NewOutResult]): Result
}

object RedirectOutput {
  type Aux[PN <: ProcessNode[_, _, _, NotRedirected, _], To, NewOut, NewOutResult, Result0] =
    RedirectOutput[PN, To, NewOut, NewOutResult] { type Result = Result0 }

  implicit def redirectProcessOutput[Out, Err, OutResult, ErrResult, IRS <: RedirectionState, ERS <: RedirectionState, To, NewOut, NewOutResult]:
  Aux[Process[Out, Err, OutResult, ErrResult, IRS, NotRedirected, ERS], To, NewOut, NewOutResult, Process[NewOut, Err, NewOutResult, ErrResult, IRS, Redirected, ERS]] =
    new RedirectOutput[Process[Out, Err, OutResult, ErrResult, IRS, NotRedirected, ERS], To, NewOut, NewOutResult] {
      override type Result = Process[NewOut, Err, NewOutResult, ErrResult, IRS, Redirected, ERS]

      override def apply(process: Process[Out, Err, OutResult, ErrResult, IRS, NotRedirected, ERS], to: To)
                        (implicit target: CanBeProcessOutputTarget.Aux[To, NewOut, NewOutResult]): Result =
        new Process(
          process.command,
          process.arguments,
          process.workingDirectory,
          process.inputSource,
          target(to),
          process.errorTarget
        )
    }

  implicit def redirectPipedProcessOutput[
    Out, Err, PN1Out,
    PN1 <: ProcessNode[PN1Out, _, _, _, _],
    PN2 <: ProcessNode[_, _, _, NotRedirected, _],
    IRS <: RedirectionState, ERS <: RedirectionState,
    PN2Redirected <: ProcessNode[_, _, _, Redirected, _],
    To, NewOut, NewOutResult]
  (implicit redirectPN2Output: RedirectOutput.Aux[PN2, To, NewOut, NewOutResult, PN2Redirected]):
  Aux[PipedProcess[Out, Err, PN1Out, PN1, PN2, IRS, NotRedirected, ERS],
      To, NewOut, NewOutResult,
      PipedProcess[NewOut, Err, PN1Out, PN1, PN2Redirected, IRS, Redirected, ERS]] =
    new RedirectOutput[PipedProcess[Out, Err, PN1Out, PN1, PN2, IRS, NotRedirected, ERS], To, NewOut, NewOutResult] {
      override type Result = PipedProcess[NewOut, Err, PN1Out, PN1, PN2Redirected, IRS, Redirected, ERS]

      override def apply(process: PipedProcess[Out, Err, PN1Out, PN1, PN2, IRS, NotRedirected, ERS], to: To)
                        (implicit target: CanBeProcessOutputTarget.Aux[To, NewOut, NewOutResult]): Result =
        new PipedProcess[NewOut, Err, PN1Out, PN1, PN2Redirected, IRS, Redirected, ERS](
          process.from, process.createTo.andThen(redirectPN2Output(_, to)))
    }
}

trait RedirectError[PN <: ProcessNode[_, _, _, _, NotRedirected], To, NewErr, NewErrResult] {
  type Result <: ProcessNode[_, NewErr, _, _, Redirected]
  def apply(process: PN, to: To)(implicit target: CanBeProcessErrorTarget.Aux[To, NewErr, NewErrResult]): Result
}

object RedirectError {
  type Aux[PN <: ProcessNode[_, _, _, _, NotRedirected], To, NewErr, NewErrResult, Result0] =
    RedirectError[PN, To, NewErr, NewErrResult] { type Result = Result0 }

  implicit def redirectProcessError[Out, Err, OutResult, ErrResult, IRS <: RedirectionState, ORS <: RedirectionState, To, NewErr, NewErrResult]:
  Aux[Process[Out, Err, OutResult, ErrResult, IRS, ORS, NotRedirected],
      To,
      NewErr, NewErrResult,
      Process[Out, NewErr, OutResult, NewErrResult, IRS, ORS, Redirected]] =
    new RedirectError[Process[Out, Err, OutResult, ErrResult, IRS, ORS, NotRedirected], To, NewErr, NewErrResult] {
      override type Result = Process[Out, NewErr, OutResult, NewErrResult, IRS, ORS, Redirected]

      override def apply(process: Process[Out, Err, OutResult, ErrResult, IRS, ORS, NotRedirected], to: To)
                        (implicit target: CanBeProcessErrorTarget.Aux[To, NewErr, NewErrResult]): Result =
        new Process(
          process.command,
          process.arguments,
          process.workingDirectory,
          process.inputSource,
          process.outputTarget,
          target(to)
        )
    }

  implicit def redirectPipedProcessError[
  Out, Err, PN1Out,
  PN1 <: ProcessNode[PN1Out, _, _, _, _],
  PN2 <: ProcessNode[_, _, _, _, NotRedirected],
  IRS <: RedirectionState, ORS <: RedirectionState,
  PN2Redirected <: ProcessNode[_, _, _, _, Redirected],
  To, NewErr, NewErrResult]
  (implicit redirectPN2Error: RedirectError.Aux[PN2, To, NewErr, NewErrResult, PN2Redirected]):
  Aux[PipedProcess[Out, Err, PN1Out, PN1, PN2, IRS, ORS, NotRedirected],
    To,
    NewErr, NewErrResult,
    PipedProcess[Out, NewErr, PN1Out, PN1, PN2Redirected, IRS, ORS, Redirected]] =
    new RedirectError[PipedProcess[Out, Err, PN1Out, PN1, PN2, IRS, ORS, NotRedirected], To, NewErr, NewErrResult] {
      override type Result = PipedProcess[Out, NewErr, PN1Out, PN1, PN2Redirected, IRS, ORS, Redirected]

      override def apply(process: PipedProcess[Out, Err, PN1Out, PN1, PN2, IRS, ORS, NotRedirected], to: To)
                        (implicit target: CanBeProcessErrorTarget.Aux[To, NewErr, NewErrResult]): Result =
        new PipedProcess[Out, NewErr, PN1Out, PN1, PN2Redirected, IRS, ORS, Redirected](
          process.from, process.createTo.andThen(redirectPN2Error(_, to)))
    }
}

trait Piping[PN1 <: ProcessNode[_, _, _, NotRedirected, _], PN2 <: ProcessNode[_, _, NotRedirected, _, _]] {
  type ResultProcess <: ProcessNode[_, _, _, _, _]
  def apply(from: PN1, to: PN2): ResultProcess
}


object Piping {
  type Aux[PN1 <: ProcessNode[_, _, _, NotRedirected, _], PN2 <: ProcessNode[_, _, NotRedirected, _, _], RP <: ProcessNode[_, _, _, _, _]] =
    Piping[PN1, PN2] { type ResultProcess = RP }

  implicit def pipeProcess[
    PN1IRS <: RedirectionState, PN1ERS <: RedirectionState,
    PN2Out, PN2Err, PN2ORS <: RedirectionState, PN2ERS <: RedirectionState,
    PN1 <: ProcessNode[_, _, _, NotRedirected, _],
    PN2 <: ProcessNode[_, _, NotRedirected, _, _],
    PN1Redirected <: ProcessNode[_, _, _, Redirected, _],
    PN2Redirected <: ProcessNode[_, _, Redirected, _, _]]
    (implicit
     pn1SubTyping: PN1 <:< ProcessNode[Byte, _, PN1IRS, NotRedirected, PN1ERS],
     pn2SubTyping: PN2 <:< ProcessNode[PN2Out, PN2Err, NotRedirected, PN2ORS, PN2ERS],
     redirectPN1Output: RedirectOutput.Aux[PN1, Ignore[Byte], Byte, Unit, PN1Redirected],
     redirectPN2Input: RedirectInput.Aux[PN2, PN2Redirected]):
    Aux[PN1,
        PN2,
        PipedProcess[PN2Out, PN2Err, Byte, PN1Redirected, PN2Redirected, PN1IRS, PN2ORS, PN2ERS]] =
    new Piping[PN1, PN2] {
      override type ResultProcess =
        PipedProcess[PN2Out, PN2Err, Byte,
                     PN1Redirected,
                     PN2Redirected,
                     PN1IRS, PN2ORS, PN2ERS]

      override def apply(from: PN1, to: PN2): ResultProcess = {
        val channel = Ignore(identity[Stream[IO, Byte]])
        new PipedProcess(
          redirectPN1Output(from, channel),
          construction => redirectPN2Input(to, construction.outStream))
      }
    }
}

object syntax {
  implicit class ProcessNodeOutputRedirect[PN <: ProcessNode[_, _, _, NotRedirected, _]](processNode: PN) {
    def >[To, NewOut, NewOutResult, Result <: ProcessNode[_, _, _, Redirected, _]]
      (to: To)
      (implicit target: CanBeProcessOutputTarget.Aux[To, NewOut, NewOutResult],
       redirectOutput: RedirectOutput.Aux[PN, To, NewOut, NewOutResult, Result]): Result = {
      redirectOutput(processNode, to)
    }

    def |[PN2 <: ProcessNode[_, _, NotRedirected, _, _], RP <: ProcessNode[_, _, _, _, _]]
      (to: PN2)
      (implicit piping: Piping.Aux[PN, PN2, RP]): RP =
      piping(processNode, to)
  }

  implicit class ProcessNodeInputRedirect[PN <: ProcessNode[_, _, NotRedirected, _, _]](processNode: PN) {
    def <[From, PNRedirected <: ProcessNode[_, _, Redirected, _, _]]
      (from: From)
      (implicit source: CanBeProcessInputSource[From], redirectInput: RedirectInput.Aux[PN, PNRedirected]): PNRedirected = {
      redirectInput(processNode, from)
    }
  }

  implicit class ProcessNodeErrorRedirect[PN <: ProcessNode[_, _, _, _, NotRedirected]](processNode: PN) {
    def redirectErrorTo[To, NewErr, NewErrResult, Result <: ProcessNode[_, _, _, _, Redirected]]
      (to: To)
      (implicit target: CanBeProcessErrorTarget.Aux[To, NewErr, NewErrResult],
       redirectError: RedirectError.Aux[PN, To, NewErr, NewErrResult, Result]): Result = {
      redirectError(processNode, to)
    }
  }

  implicit class ProcessOps[PN <: ProcessNode[_, _, _, _, _]](processNode: PN) {
    def start[RP](implicit start: Start.Aux[PN, RP, _], executionContext: ExecutionContext): IO[RP] =
      start(processNode)
    def startHL[RPL <: HList](implicit start: Start.Aux[PN, _, RPL], executionContext: ExecutionContext): IO[RPL] =
      start.toHList(processNode)
  }
}
