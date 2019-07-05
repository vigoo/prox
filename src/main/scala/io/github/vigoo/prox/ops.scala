package io.github.vigoo.prox

import cats.Applicative
import cats.effect._
import cats.effect.syntax.all._
import cats.implicits._
import cats.kernel.Monoid
import fs2._
import shapeless._
import shapeless.ops.hlist.{IsHCons, Last, Prepend, Tupler}

import scala.jdk.CollectionConverters._
import scala.language.higherKinds

trait ContextOf[PN] {
  type Context[_]
}

object ContextOf {
  type Aux[PN, F[_]] = ContextOf[PN] {
    type Context[_] = F[_]
  }

  def apply[PN <: ProcessNode[_, _, _, _, _], F[_]](implicit contextOf: ContextOf.Aux[PN, F]): Aux[PN, F] = contextOf

  implicit def contextOfProcess[F[_], Out, Err, OutResult, ErrResult, IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState]:
  Aux[Process[F, Out, Err, OutResult, ErrResult, IRS, ORS, ERS], F] =
    new ContextOf[Process[F, Out, Err, OutResult, ErrResult, IRS, ORS, ERS]] {
      override type Context[_] = F[_]
    }

  implicit def contextOfPipedProcess[
  F[_],
  Out, Err,
  PN1 <: ProcessNode[_, _, _, _, _],
  PN2 <: ProcessNode[_, _, _, _, _],
  IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState]:
  Aux[PipedProcess[F, Out, Err, Byte, PN1, PN2, IRS, ORS, ERS], F] =
    new ContextOf[PipedProcess[F, Out, Err, Byte, PN1, PN2, IRS, ORS, ERS]] {
      override type Context[_] = F[_]
    }
}

/** Type class for starting processes
  *
  * @tparam PN Process type
  */
trait Start[F[_], PN] {
  /** The type returned by starting the processes holding one or more [[RunningProcess]] instances */
  type RunningProcesses

  /** The [[RunningProcess]] instances returned by starting the process represented in a [[shapeless.HList]] */
  type RunningProcessList <: HList

  /** Start the given process
    *
    * The dontStartOutput option is used by the pipe construction as the output stream has to be connected
    * to the second process' input channel first.
    *
    * The input and error streams are always started.
    *
    * @param process         The process to be started
    * @param dontStartOutput Do no start the output redirection stream
    * @param blocker         Execution context for the blocking stream IO
    * @param contextShift    Context shifter to be used for the streams
    * @return Returns the [[RunningProcess]] instances of the started system processes
    */
  def apply(process: PN, dontStartOutput: Boolean = false, blocker: Blocker)
           (implicit
            concurrent: Concurrent[F],
            contextShift: ContextShift[F]): F[RunningProcesses]

  /** Start the given process
    *
    * The dontStartOutput option is used by the pipe construction as the output stream has to be connected
    * to the second process' input channel first.
    *
    * The input and error streams are always started.
    *
    * @param process         The process to be started
    * @param dontStartOutput Do no start the output redirection stream
    * @param blocker         Execution context for the blocking stream IO
    * @param contextShift    Context shifter to be used for the streams
    * @return Returns the [[RunningProcess]] instances of the started system processes as a [[shapeless.HList]]
    */
  def toHList(process: PN, dontStartOutput: Boolean = false, blocker: Blocker)
             (implicit
              concurrent: Concurrent[F],
              contextShift: ContextShift[F]): F[RunningProcessList]
}

object Start {
  type Aux[F[_], PN, RP, RPL <: HList] = Start[F, PN] {
    type RunningProcesses = RP
    type RunningProcessList = RPL
  }

  def apply[F[_], PN <: ProcessNode[_, _, _, _, _], RP, RPL <: HList](implicit start: Start.Aux[F, PN, RP, RPL]): Aux[F, PN, RP, RPL] = start

  implicit def startProcess[F[_], Out, Err, OutResult: Monoid, ErrResult, IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState]:
  Aux[F, Process[F, Out, Err, OutResult, ErrResult, IRS, ORS, ERS], RunningProcess[F, Out, OutResult, ErrResult], RunningProcess[F, Out, OutResult, ErrResult] :: HNil] =

    new Start[F, Process[F, Out, Err, OutResult, ErrResult, IRS, ORS, ERS]] {
      override type RunningProcesses = RunningProcess[F, Out, OutResult, ErrResult]
      override type RunningProcessList = RunningProcess[F, Out, OutResult, ErrResult] :: HNil

      override def apply(process: Process[F, Out, Err, OutResult, ErrResult, IRS, ORS, ERS], dontStartOutput: Boolean, blocker: Blocker)
                        (implicit
                         concurrent: Concurrent[F],
                         contextShift: ContextShift[F]): F[RunningProcess[F, Out, OutResult, ErrResult]] = {
        def withWorkingDirectory(builder: ProcessBuilder): ProcessBuilder =
          process.workingDirectory match {
            case Some(directory) => builder.directory(directory.toFile)
            case None => builder
          }

        def withEnvironmentVariables(builder: ProcessBuilder): ProcessBuilder = {
          process.environmentVariables.foreach { case (name, value) =>
            builder.environment().put(name, value)
          }
          process.removedEnvironmentVariables.foreach { name =>
            builder.environment().remove(name)
          }
          builder
        }

        val builder = withEnvironmentVariables(withWorkingDirectory(new ProcessBuilder((process.command :: process.arguments).asJava)))
        builder.redirectInput(process.inputSource.toRedirect)
        builder.redirectOutput(process.outputTarget.toRedirect)
        builder.redirectError(process.errorTarget.toRedirect)
        for {
          proc <- Sync[F].delay(builder.start)
          inputStream = process.inputSource.connect(proc, blocker)
          outputStream = process.outputTarget.connect(proc, blocker)
          errorStream = process.errorTarget.connect(proc, blocker)
          runningInput <- process.inputSource.run(inputStream)
          runningOutput <- if (dontStartOutput) {
            Concurrent[F].start(Sync[F].delay(Monoid[OutResult].empty))
          } else {
            process.outputTarget.run(outputStream)
          }
          runningError <- process.errorTarget.run(errorStream)
        } yield new WrappedProcess(
          proc,
          if (dontStartOutput) Some(outputStream) else None,
          runningInput,
          runningOutput,
          runningError)
      }

      override def toHList(process: Process[F, Out, Err, OutResult, ErrResult, IRS, ORS, ERS], dontStartOutput: Boolean, blocker: Blocker)
                          (implicit
                           concurrent: Concurrent[F],
                           contextShift: ContextShift[F]): F[RunningProcessList] =
        apply(process, dontStartOutput, blocker).map(runningProcess => runningProcess :: HNil)
    }

  implicit def startPipedProcess[
  F[_],
  Out, Err,
  PN1 <: ProcessNode[_, _, _, _, _],
  PN2 <: ProcessNode[_, _, _, _, _],
  IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState,
  RP1, RPL1 <: HList, RP1Last <: RunningProcess[F, _, _, _],
  RP2, RPL2 <: HList, RP2Head <: RunningProcess[F, _, _, _], RP2Tail <: HList,
  RPT, RPL <: HList]
  (implicit
   start1: Start.Aux[F, PN1, RP1, RPL1],
   start2: Start.Aux[F, PN2, RP2, RPL2],
   last1: Last.Aux[RPL1, RP1Last],
   rp1LastType: RP1Last <:< RunningProcess[F, Byte, _, _],
   hcons2: IsHCons.Aux[RPL2, RP2Head, RP2Tail],
   prepend: Prepend.Aux[RPL1, RPL2, RPL],
   tupler: Tupler.Aux[RPL, RPT]):
  Aux[F, PipedProcess[F, Out, Err, Byte, PN1, PN2, IRS, ORS, ERS], RPT, RPL] =
    new Start[F, PipedProcess[F, Out, Err, Byte, PN1, PN2, IRS, ORS, ERS]] {
      override type RunningProcesses = RPT
      override type RunningProcessList = RPL

      override def apply(pipe: PipedProcess[F, Out, Err, Byte, PN1, PN2, IRS, ORS, ERS], dontStartOutput: Boolean, blocker: Blocker)
                        (implicit
                         concurrent: Concurrent[F],
                         contextShift: ContextShift[F]): F[RPT] = {
        toHList(pipe, dontStartOutput, blocker).map(_.tupled)
      }

      override def toHList(pipe: PipedProcess[F, Out, Err, Byte, PN1, PN2, IRS, ORS, ERS], dontStartOutput: Boolean, blocker: Blocker)
                          (implicit
                           concurrent: Concurrent[F],
                           contextShift: ContextShift[F]): F[RPL] = {
        start1.toHList(pipe.from, dontStartOutput = true, blocker).flatMap { runningSourceProcesses =>
          val runningFrom = runningSourceProcesses.last.asInstanceOf[RunningProcess[F, Byte, _, _]]
          val to = pipe.createTo(PipeConstruction[F, Byte](runningFrom.notStartedOutput.get))
          start2.toHList(to, dontStartOutput, blocker).flatMap { runningTargetProcesses =>
            Sync[F].delay(runningSourceProcesses ::: runningTargetProcesses)
          }
        }
      }
    }
}

/** Type class for redirecting the input channel of a process
  *
  * The redirection is encoded in the process type and can be performed only once.
  *
  * @tparam PN The process to modify
  */
trait RedirectInput[F[_], PN <: ProcessNode[_, _, NotRedirected, _, _]] {
  /** The result process type with the redirection encoded */
  type Result <: ProcessNode[_, _, Redirected, _, _]

  /** Sets the given input source for the process
    *
    * @param process The process to modify
    * @param from    The input source to use
    * @tparam From Type of the input source
    * @return Returns the process with its input channel redirected
    */
  def apply[From](process: PN, from: From)(implicit canBeInputSource: CanBeProcessInputSource[F, From]): Result
}

object RedirectInput {
  type Aux[F[_], PN <: ProcessNode[_, _, NotRedirected, _, _], Result0 <: ProcessNode[_, _, Redirected, _, _]] =
    RedirectInput[F, PN] {type Result = Result0}

  implicit def redirectProcessInput[F[_], Out, Err, OutResult, ErrResult, ORS <: RedirectionState, ERS <: RedirectionState]:
  Aux[F, Process[F, Out, Err, OutResult, ErrResult, NotRedirected, ORS, ERS], Process[F, Out, Err, OutResult, ErrResult, Redirected, ORS, ERS]] =
    new RedirectInput[F, Process[F, Out, Err, OutResult, ErrResult, NotRedirected, ORS, ERS]] {
      override type Result = Process[F, Out, Err, OutResult, ErrResult, Redirected, ORS, ERS]

      override def apply[From](process: Process[F, Out, Err, OutResult, ErrResult, NotRedirected, ORS, ERS], from: From)
                              (implicit canBeInputSource: CanBeProcessInputSource[F, From]): Result =
        new Process(
          process.command,
          process.arguments,
          process.workingDirectory,
          implicitly[CanBeProcessInputSource[F, From]].apply(from),
          process.outputTarget,
          process.errorTarget,
          process.environmentVariables,
          process.removedEnvironmentVariables
        )
    }

  implicit def redirectPipedProcessInput[
  F[_],
  Out, Err, PN1Out,
  PN1 <: ProcessNode[_, _, NotRedirected, _, _],
  PN2 <: ProcessNode[_, _, _, _, _],
  ORS <: RedirectionState, ERS <: RedirectionState,
  PN1Redirected <: ProcessNode[_, _, Redirected, _, _]]
  (implicit redirectPN1Input: RedirectInput.Aux[F, PN1, PN1Redirected]):
  Aux[F, PipedProcess[F, Out, Err, PN1Out, PN1, PN2, NotRedirected, ORS, ERS], PipedProcess[F, Out, Err, PN1Out, PN1Redirected, PN2, Redirected, ORS, ERS]] =

    new RedirectInput[F, PipedProcess[F, Out, Err, PN1Out, PN1, PN2, NotRedirected, ORS, ERS]] {
      override type Result = PipedProcess[F, Out, Err, PN1Out, PN1Redirected, PN2, Redirected, ORS, ERS]

      override def apply[From](process: PipedProcess[F, Out, Err, PN1Out, PN1, PN2, NotRedirected, ORS, ERS], from: From)
                              (implicit canBeInputSource: CanBeProcessInputSource[F, From]): Result = {
        new PipedProcess[F, Out, Err, PN1Out, PN1Redirected, PN2, Redirected, ORS, ERS](redirectPN1Input(process.from, from), process.createTo)
      }
    }
}

/** Type class for redirecting the output channel of a process
  *
  * The redirection is encoded in the process type and can be performed only once.
  *
  * @tparam PN           The process to modify
  * @tparam To           Type of the output target to redirect to
  * @tparam NewOut       Output stream element type
  * @tparam NewOutResult Result of running the output stream
  */
trait RedirectOutput[F[_], PN <: ProcessNode[_, _, _, NotRedirected, _], To, NewOut, NewOutResult] {
  /** The result process type with the redirection encoded */
  type Result <: ProcessNode[NewOut, _, _, Redirected, _]

  /** Sets the given output target for the process
    *
    * @param process The process to modify
    * @param to      The output target to use
    * @return Returns the process with its output channel redirected
    */
  def apply(process: PN, to: To)(implicit target: CanBeProcessOutputTarget.Aux[F, To, NewOut, NewOutResult]): Result
}

object RedirectOutput {
  type Aux[F[_], PN <: ProcessNode[_, _, _, NotRedirected, _], To, NewOut, NewOutResult, Result0] =
    RedirectOutput[F, PN, To, NewOut, NewOutResult] {type Result = Result0}

  implicit def redirectProcessOutput[F[_], Out, Err, OutResult, ErrResult, IRS <: RedirectionState, ERS <: RedirectionState, To, NewOut, NewOutResult]:
  Aux[F, Process[F, Out, Err, OutResult, ErrResult, IRS, NotRedirected, ERS], To, NewOut, NewOutResult, Process[F, NewOut, Err, NewOutResult, ErrResult, IRS, Redirected, ERS]] =
    new RedirectOutput[F, Process[F, Out, Err, OutResult, ErrResult, IRS, NotRedirected, ERS], To, NewOut, NewOutResult] {
      override type Result = Process[F, NewOut, Err, NewOutResult, ErrResult, IRS, Redirected, ERS]

      override def apply(process: Process[F, Out, Err, OutResult, ErrResult, IRS, NotRedirected, ERS], to: To)
                        (implicit target: CanBeProcessOutputTarget.Aux[F, To, NewOut, NewOutResult]): Result =
        new Process(
          process.command,
          process.arguments,
          process.workingDirectory,
          process.inputSource,
          target(to),
          process.errorTarget,
          process.environmentVariables,
          process.removedEnvironmentVariables
        )
    }

  implicit def redirectPipedProcessOutput[
  F[_],
  Out, Err, PN1Out,
  PN1 <: ProcessNode[PN1Out, _, _, _, _],
  PN2 <: ProcessNode[_, _, _, NotRedirected, _],
  IRS <: RedirectionState, ERS <: RedirectionState,
  PN2Redirected <: ProcessNode[_, _, _, Redirected, _],
  To, NewOut, NewOutResult]
  (implicit redirectPN2Output: RedirectOutput.Aux[F, PN2, To, NewOut, NewOutResult, PN2Redirected]):
  Aux[F, PipedProcess[F, Out, Err, PN1Out, PN1, PN2, IRS, NotRedirected, ERS],
    To, NewOut, NewOutResult,
    PipedProcess[F, NewOut, Err, PN1Out, PN1, PN2Redirected, IRS, Redirected, ERS]] =
    new RedirectOutput[F, PipedProcess[F, Out, Err, PN1Out, PN1, PN2, IRS, NotRedirected, ERS], To, NewOut, NewOutResult] {
      override type Result = PipedProcess[F, NewOut, Err, PN1Out, PN1, PN2Redirected, IRS, Redirected, ERS]

      override def apply(process: PipedProcess[F, Out, Err, PN1Out, PN1, PN2, IRS, NotRedirected, ERS], to: To)
                        (implicit target: CanBeProcessOutputTarget.Aux[F, To, NewOut, NewOutResult]): Result =
        new PipedProcess[F, NewOut, Err, PN1Out, PN1, PN2Redirected, IRS, Redirected, ERS](
          process.from, process.createTo.andThen(redirectPN2Output(_, to)))
    }
}


/** Type class for redirecting the error channel of a process
  *
  * The redirection is encoded in the process type and can be performed only once.
  *
  * @tparam PN           The process to modify
  * @tparam To           Type of the output target to redirect to
  * @tparam NewErr       Error stream element type
  * @tparam NewErrResult Result of running the error stream
  */
trait RedirectError[F[_], PN <: ProcessNode[_, _, _, _, NotRedirected], To, NewErr, NewErrResult] {
  /** The result process type with the redirection encoded */
  type Result <: ProcessNode[_, NewErr, _, _, Redirected]

  /** Sets the given output target for the process
    *
    * @param process The process to modify
    * @param to      The output target to use
    * @return Returns the process with its error channel redirected
    */
  def apply(process: PN, to: To)(implicit target: CanBeProcessErrorTarget.Aux[F, To, NewErr, NewErrResult]): Result
}

object RedirectError {
  type Aux[F[_], PN <: ProcessNode[_, _, _, _, NotRedirected], To, NewErr, NewErrResult, Result0] =
    RedirectError[F, PN, To, NewErr, NewErrResult] {type Result = Result0}

  implicit def redirectProcessError[F[_], Out, Err, OutResult, ErrResult, IRS <: RedirectionState, ORS <: RedirectionState, To, NewErr, NewErrResult]:
  Aux[F, Process[F, Out, Err, OutResult, ErrResult, IRS, ORS, NotRedirected],
    To,
    NewErr, NewErrResult,
    Process[F, Out, NewErr, OutResult, NewErrResult, IRS, ORS, Redirected]] =
    new RedirectError[F, Process[F, Out, Err, OutResult, ErrResult, IRS, ORS, NotRedirected], To, NewErr, NewErrResult] {
      override type Result = Process[F, Out, NewErr, OutResult, NewErrResult, IRS, ORS, Redirected]

      override def apply(process: Process[F, Out, Err, OutResult, ErrResult, IRS, ORS, NotRedirected], to: To)
                        (implicit target: CanBeProcessErrorTarget.Aux[F, To, NewErr, NewErrResult]): Result =
        new Process(
          process.command,
          process.arguments,
          process.workingDirectory,
          process.inputSource,
          process.outputTarget,
          target(to),
          process.environmentVariables,
          process.removedEnvironmentVariables
        )
    }

  implicit def redirectPipedProcessError[
  F[_],
  Out, Err, PN1Out,
  PN1 <: ProcessNode[PN1Out, _, _, _, _],
  PN2 <: ProcessNode[_, _, _, _, NotRedirected],
  IRS <: RedirectionState, ORS <: RedirectionState,
  PN2Redirected <: ProcessNode[_, _, _, _, Redirected],
  To, NewErr, NewErrResult]
  (implicit redirectPN2Error: RedirectError.Aux[F, PN2, To, NewErr, NewErrResult, PN2Redirected]):
  Aux[F, PipedProcess[F, Out, Err, PN1Out, PN1, PN2, IRS, ORS, NotRedirected],
    To,
    NewErr, NewErrResult,
    PipedProcess[F, Out, NewErr, PN1Out, PN1, PN2Redirected, IRS, ORS, Redirected]] =
    new RedirectError[F, PipedProcess[F, Out, Err, PN1Out, PN1, PN2, IRS, ORS, NotRedirected], To, NewErr, NewErrResult] {
      override type Result = PipedProcess[F, Out, NewErr, PN1Out, PN1, PN2Redirected, IRS, ORS, Redirected]

      override def apply(process: PipedProcess[F, Out, Err, PN1Out, PN1, PN2, IRS, ORS, NotRedirected], to: To)
                        (implicit target: CanBeProcessErrorTarget.Aux[F, To, NewErr, NewErrResult]): Result =
        new PipedProcess[F, Out, NewErr, PN1Out, PN1, PN2Redirected, IRS, ORS, Redirected](
          process.from, process.createTo.andThen(redirectPN2Error(_, to)))
    }
}

/** Type class for piping one process to another
  *
  * @tparam PN1 First process type
  * @tparam PN2 Second process type
  */
trait Piping[F[_], PN1 <: ProcessNode[_, _, _, NotRedirected, _], PN2 <: ProcessNode[_, _, NotRedirected, _, _]] {
  /** The result type of piping the two processes together */
  type ResultProcess <: ProcessNode[_, _, _, _, _]

  /** Creates the piped process from the two source processes
    *
    * @param from The process to use as a source
    * @param to   The process to feed the source process' output to
    * @param via  The [[fs2.Pipe]] between the two processes
    * @return Returns the piped process
    */
  def apply(from: PN1, to: PN2, via: Pipe[F, Byte, Byte])
           (implicit concurrent: Concurrent[F]): ResultProcess
}


object Piping {
  type Aux[F[_], PN1 <: ProcessNode[_, _, _, NotRedirected, _], PN2 <: ProcessNode[_, _, NotRedirected, _, _], RP <: ProcessNode[_, _, _, _, _]] =
    Piping[F, PN1, PN2] {type ResultProcess = RP}

  implicit def pipeProcess[
  F[_] : Sync,
  PN1IRS <: RedirectionState, PN1ERS <: RedirectionState,
  PN2Out, PN2Err, PN2ORS <: RedirectionState, PN2ERS <: RedirectionState,
  PN1 <: ProcessNode[_, _, _, NotRedirected, _],
  PN2 <: ProcessNode[_, _, NotRedirected, _, _],
  PN1Redirected <: ProcessNode[_, _, _, Redirected, _],
  PN2Redirected <: ProcessNode[_, _, Redirected, _, _]]
  (implicit
   pn1SubTyping: PN1 <:< ProcessNode[Byte, _, PN1IRS, NotRedirected, PN1ERS],
   pn2SubTyping: PN2 <:< ProcessNode[PN2Out, PN2Err, NotRedirected, PN2ORS, PN2ERS],
   redirectPN1Output: RedirectOutput.Aux[F, PN1, Drain[F, Byte], Byte, Unit, PN1Redirected],
   redirectPN2Input: RedirectInput.Aux[F, PN2, PN2Redirected]):
  Aux[
    F,
    PN1,
    PN2,
    PipedProcess[F, PN2Out, PN2Err, Byte, PN1Redirected, PN2Redirected, PN1IRS, PN2ORS, PN2ERS]] =
    new Piping[F, PN1, PN2] {
      override type ResultProcess =
        PipedProcess[F, PN2Out, PN2Err, Byte,
          PN1Redirected,
          PN2Redirected,
          PN1IRS, PN2ORS, PN2ERS]

      override def apply(from: PN1, to: PN2, via: Pipe[F, Byte, Byte])
                        (implicit concurrent: Concurrent[F]): ResultProcess = {
        val channel = Drain(via)
        new PipedProcess(
          redirectPN1Output(from, channel),
          construction => redirectPN2Input(to, construction.outStream))
      }
    }
}

/** Helper class for customizing the pipe between two processes
  *
  * See the [[syntax]] object for an example.
  *
  * @param processNode The first process participating in the piping
  * @param via         The custom pipe
  * @tparam PN Type of the first process
  */
class PipeBuilder[F[_], PN <: ProcessNode[_, _, _, NotRedirected, _]](processNode: PN, via: Pipe[F, Byte, Byte]) {
  /** Constructs the piping by providing the target process
    *
    * @param to     The target process
    * @param piping The type class implementing the piping
    * @tparam PN2 Type of the second process
    * @tparam RP  Result process type of the piping
    * @return Returns the piped process
    */
  def to[PN2 <: ProcessNode[_, _, NotRedirected, _, _], RP <: ProcessNode[_, _, _, _, _]](to: PN2)
                                                                                         (implicit
                                                                                          concurrent: Concurrent[F],
                                                                                          piping: Piping.Aux[F, PN, PN2, RP]): RP =
    piping(processNode, to, via)
}


/** Implicit classes for working with simple and piped processes
  *
  * All the operations are implemented for both simple [[Process]] objects and [[PipedProcess]] objects as well. Most of
  * the operations encode information in the types of the processes as well.
  *
  * The operations are implemented by various type classes and exposed through extension methods defined in the
  * implicit classes in this object.
  *
  * == Examples ==
  *
  * Starting simple and piped processes:
  * {{{
  *   val echoProcess = Process("echo", List("This is an output"))
  *   val wordCountProcess = Process("wc", List("-w"))
  *   val combined = echoProcess | wordCountProcess
  *
  *   for {
  *     echo1 <- Process("echo", List("Hello world")).start
  *     runningProcs <- combined.start
  *     (echo2, wordCount) = runningProcs
  *   } yield ()
  * }}}
  *
  * Redirecting input, output and error channels:
  * {{{
  *   val p1 = Process("echo", List("Hello world")) > (home / "tmp" / "out.txt")
  *   val p2 = Process("cat") < (home / "something")
  *   val p3 = Process("make") errorTo (home / "errors.log")
  * }}}
  *
  * Piping:
  * {{{
  *   val echoProcess = Process("echo", List("This is an output"))
  *   val wordCountProcess = Process("wc", List("-w"))
  *   val combined1 = echoProcess | wordCountProcess
  *
  *   val customPipe: Pipe[IO, Byte, Byte] = ???
  *   val combined2 = echoProcess.via(customPipe).to(wordCountProcess)
  * }}}
  */
object syntax {

  implicit class ProcessNodeOutputRedirect[PN <: ProcessNode[_, _, _, NotRedirected, _]](processNode: PN) {
    /** Redirects the output channel of a process
      *
      * @param to             Redirection target
      * @param target         Type class for using To as a redirection target
      * @param redirectOutput Type class implementing the redirection
      * @tparam To           Type of the redirection target
      * @tparam NewOut       Output stream element type
      * @tparam NewOutResult Result type of running the output stream
      * @tparam Result       Type of the process with the redirection encoded
      * @return Returns the process with its output channel redirected
      */
    def >[F[_], To, NewOut, NewOutResult, Result <: ProcessNode[_, _, _, Redirected, _]]
    (to: To)
    (implicit
     contextOf: ContextOf.Aux[PN, F],
     target: CanBeProcessOutputTarget.Aux[F, To, NewOut, NewOutResult],
     redirectOutput: RedirectOutput.Aux[F, PN, To, NewOut, NewOutResult, Result]): Result = {
      redirectOutput(processNode, to)
    }

    /** Creates a piped process by redirecting the process' output to the other process' input
      *
      * @param to     Target process
      * @param piping Type class implementing the piping
      * @tparam PN2 Type of the target process
      * @tparam RP  Type of the piped result process
      * @return Returns a piped process
      */
    def |[F[_], PN2 <: ProcessNode[_, _, NotRedirected, _, _], RP <: ProcessNode[_, _, _, _, _]]
    (to: PN2)
    (implicit
     contextOf: ContextOf.Aux[PN, F],
     concurrent: Concurrent[F],
     piping: Piping.Aux[F, PN, PN2, RP]): RP =
      piping(processNode, to, identity[Stream[F, Byte]])

    /** Creates a piped process by providing a custom pipe
      *
      * @param via The custom pipe between the two process
      * @return Returns a [[PipeBuilder]] instance which can be used to complete the piping specification
      */
    def via[F[_]](via: Pipe[F, Byte, Byte])
                 (implicit contextOf: ContextOf.Aux[PN, F]): PipeBuilder[F, PN] =
      new PipeBuilder(processNode, via)
  }

  implicit class ProcessNodeInputRedirect[PN <: ProcessNode[_, _, NotRedirected, _, _]](processNode: PN) {
    /** Redirects the input channel of a process
      *
      * @param from          Redirection source
      * @param source        Type class for using From as a redirection source
      * @param redirectInput Type class implementing the redirection
      * @tparam From         Type of the redirection source
      * @tparam PNRedirected Type of the process with the redirection encoded
      * @return Returns the process with its input channel redirected
      */
    def <[F[_], From, PNRedirected <: ProcessNode[_, _, Redirected, _, _]]
    (from: From)
    (implicit
     contextOf: ContextOf.Aux[PN, F],
     source: CanBeProcessInputSource[F, From],
     redirectInput: RedirectInput.Aux[F, PN, PNRedirected]): PNRedirected = {
      redirectInput(processNode, from)
    }
  }

  implicit class ProcessNodeErrorRedirect[PN <: ProcessNode[_, _, _, _, NotRedirected]](processNode: PN) {
    /** Redirects the error channel of a process
      *
      * @param to            Redirection target
      * @param target        Type class for using To as a redirection target
      * @param redirectError Type class implementing the redirection
      * @tparam To           Type of the redirection target
      * @tparam NewErr       Error stream element type
      * @tparam NewErrResult Result type of running the error stream
      * @tparam Result       Type of the process with the redirection encoded
      * @return Returns the process with its error channel redirected
      */
    def redirectErrorTo[F[_], To, NewErr, NewErrResult, Result <: ProcessNode[_, _, _, _, Redirected]]
    (to: To)
    (implicit
     contextOf: ContextOf.Aux[PN, F],
     target: CanBeProcessErrorTarget.Aux[F, To, NewErr, NewErrResult],
     redirectError: RedirectError.Aux[F, PN, To, NewErr, NewErrResult, Result]): Result = {
      redirectError(processNode, to)
    }
  }

  implicit class ProcessOps[PN <: ProcessNode[_, _, _, _, _]](processNode: PN) {
    /** Starts the process
      *
      * @param start        Type class implementing the process starting
      * @param blocker      Execution context for the blocking stream IO
      * @param contextShift Context shifter to be used for the streams
      * @tparam RP Type encoding the [[RunningProcess]] instances
      * @return Returns the [[RunningProcess]] instances for the system processes which has been started
      */
    def start[F[_], RP](blocker: Blocker)(implicit
                                          contextOf: ContextOf.Aux[PN, F],
                                          start: Start.Aux[F, PN, RP, _],
                                          concurrent: Concurrent[F],
                                          contextShift: ContextShift[F]): F[RP] =
      start(processNode, dontStartOutput = false, blocker)

    /** Starts the process
      *
      * @param start        Type class implementing the process starting
      * @param blocker      Execution context for the blocking stream IO
      * @param contextShift Context shifter to be used for the streams
      * @tparam RPL Type encoding the [[RunningProcess]] instances in a [[shapeless.HList]]
      * @return Returns the HList of [[RunningProcess]] instances for the system processes which has been started
      */
    def startHL[F[_], RPL <: HList](blocker: Blocker)
                                   (implicit
                                    contextOf: ContextOf.Aux[PN, F],
                                    start: Start.Aux[F, PN, _, RPL],
                                    concurrent: Concurrent[F],
                                    contextShift: ContextShift[F]): F[RPL] =
      start.toHList(processNode, dontStartOutput = false, blocker)
  }

}
