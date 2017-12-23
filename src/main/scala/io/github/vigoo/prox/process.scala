package io.github.vigoo.prox

import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.effect.IO
import fs2._

import scala.concurrent.ExecutionContext
import scala.language.{higherKinds, implicitConversions}

/** Holds information about a terminated process
  *
  * See [[OutputStreamingTarget]] and [[ErrorStreamingTarget]] for more information
  * about the fullOutput and fullError fields.
  *
  * @param exitCode   Exit code the process returned with
  * @param fullOutput Depending on the output redirection, the result of running the output stream
  * @param fullError  Depending on the error redirection, the result of running the error stream
  * @tparam OutResult Result type of running the redirected output stream. [[Unit]] if there is no such result.
  * @tparam ErrResult Result type of running the redirected error stream. [[Unit]] if there is no such result.
  */
case class ProcessResult[OutResult, ErrResult](exitCode: Int, fullOutput: OutResult, fullError: ErrResult)

/** Base trait for redirection handlers
  *
  * @tparam O The redirection stream element type
  * @tparam R Result type we get by running the redirection stream
  */
trait ProcessIO[O, R] {
  def toRedirect: Redirect

  def connect(systemProcess: java.lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, O]

  def run(stream: Stream[IO, O])(implicit executionContext: ExecutionContext): IO[IO[R]]
}


/** Holds information required to start a piped process
  *
  * @param outStream Output stream of the first process, to be used as the input stream of the second one
  * @tparam Out Element type of the piping stream
  */
case class PipeConstruction[Out](outStream: Stream[IO, Out])


/** Phantom type representing the redirection state of a process */
sealed trait RedirectionState

/** Indicates that the given channel is not redirected yet */
trait NotRedirected extends RedirectionState

/** Indicates that the given channel has already been redirected */
trait Redirected extends RedirectionState


/** Base trait for simple and piped processes
  *
  * To work with processes, use the extension methods defined in the [[syntax]] object.
  *
  * @tparam Out Output stream element type
  * @tparam Err Error stream element type
  * @tparam IRS Input channel redirection state
  * @tparam ORS Output channel redirection state
  * @tparam ERS Error channel redirection state
  */
sealed trait ProcessNode[Out, Err, IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState] {
}

/** Represents two process piped together
  *
  * Do not construct [[PipedProcess]] directly. Use the [[syntax.ProcessNodeOutputRedirect.|]] or
  * [[syntax.ProcessNodeOutputRedirect.via]] methods.
  *
  * @param from     The first process participating in the piping
  * @param createTo Factory function that creates the second participating process once the first one has been constructed
  * @tparam Out    Output stream element type
  * @tparam Err    Error stream element type
  * @tparam PN1Out Output stream element type of the first process
  * @tparam PN1    Type of the first process
  * @tparam PN2    Type of the second process
  * @tparam IRS    Input channel redirection state
  * @tparam ORS    Output channel redirection state
  * @tparam ERS    Error channel redirection state
  */
class PipedProcess[Out, Err, PN1Out, PN1 <: ProcessNode[_, _, _, _, _], PN2 <: ProcessNode[_, _, _, _, _], IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState]
(val from: PN1, val createTo: PipeConstruction[PN1Out] => PN2)
  extends ProcessNode[Out, Err, IRS, ORS, ERS] {
}

/** Represents a single system process
  *
  * Do not construct [[Process]] directly. Use the companion object instead.
  *
  * @param command          The command to start the process with
  * @param arguments        List of arguments to be passed to the process
  * @param workingDirectory Working directory for the process
  * @param inputSource      Redirection of the input channel
  * @param outputTarget     Redirection of the output channel
  * @param errorTarget      Redirection of the error channel
  * @tparam Out       Output stream element type
  * @tparam Err       Error stream element type
  * @tparam OutResult Result type of running the output stream
  * @tparam ErrResult Result type of running the error stream
  * @tparam IRS       Input channel redirection state
  * @tparam ORS       Output channel redirection state
  * @tparam ERS       Error channel redirection state
  */
class Process[Out, Err, OutResult, ErrResult, IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState]
(val command: String,
 val arguments: List[String],
 val workingDirectory: Option[Path],
 val inputSource: ProcessInputSource,
 val outputTarget: ProcessOutputTarget[Out, OutResult],
 val errorTarget: ProcessErrorTarget[Err, ErrResult])
  extends ProcessNode[Out, Err, IRS, ORS, ERS] {

  /** Defines a process with an overwritten working directory
    *
    * Allows the following convenient syntax:
    * {{{
    *   val process = Process("ls", List("-hal")) in (home / "tmp")
    * }}}
    *
    * @param workingDirectory The working directory to run the process in
    * @return Returns a process with the working directory set
    */
  def in(workingDirectory: Path): Process[Out, Err, OutResult, ErrResult, IRS, ORS, ERS] = {
    new Process[Out, Err, OutResult, ErrResult, IRS, ORS, ERS](
      command = command,
      arguments = arguments,
      workingDirectory = Some(workingDirectory),
      inputSource = inputSource,
      outputTarget = outputTarget,
      errorTarget = errorTarget)
  }
}

/** Factory object for [[Process]] */
object Process {
  /**
    * Creates a process
    *
    * @param command          The command to start the process with
    * @param arguments        List of arguments to be passed to the process
    * @param workingDirectory Working directory for the process
    * @return Returns the specification of a system process
    */
  def apply(command: String,
            arguments: List[String] = List.empty,
            workingDirectory: Option[Path] = None): Process[Byte, Byte, Unit, Unit, NotRedirected, NotRedirected, NotRedirected] =
    new Process[Byte, Byte, Unit, Unit, NotRedirected, NotRedirected, NotRedirected](command, arguments, workingDirectory, StdIn, StdOut, StdError)
}


/** Interface to a running process
  *
  * Always represents a single running system process, in case of process piping each participant of the pipe
  * gets its own [[RunningProcess]] instance.
  *
  * All the operations are running in the [[IO]] monad.
  *
  * The result of the process can be acquied by either waiting for it to terminate or terminating it. The
  * result will be contained by a [[ProcessResult]] object.
  *
  * @tparam Out       Output stream element type
  * @tparam OutResult Result type of running the redirected output stream. [[Unit]] if there is no such result.
  * @tparam ErrResult Result type of running the redirected error stream. [[Unit]] if there is no such result.
  */
trait RunningProcess[Out, OutResult, ErrResult] {
  /** Checks whether the process is still running
    *
    * @return Returns true if the process is alive
    */
  def isAlive: IO[Boolean]

  /** Blocks the current thread until the process terminates
    *
    * @return Returns the result of the process
    */
  def waitForExit(): IO[ProcessResult[OutResult, ErrResult]]

  /** Forcibly terminates the process
    *
    * @return Returns the result of the process
    */
  def kill(): IO[ProcessResult[OutResult, ErrResult]]

  /** Terminates the process
    *
    * @return Returns the result of the process
    */
  def terminate(): IO[ProcessResult[OutResult, ErrResult]]

  /** Interface for getting the not yet started output stream of a process
    * during pipe construction.
    *
    * @return Returns the output stream for the piping
    */
  private[prox] def notStartedOutput: Option[Stream[IO, Out]]
}


/** Implements the [[RunningProcess]] trait by wrapping [[java.lang.Process]]
  *
  * It uses the destroy and destroyForcibly methods of [[java.lang.Process]] to implement terminate and kill.
  *
  * @param systemProcess    The wrapped system process
  * @param notStartedOutput Optional non-started output stream for process piping
  * @param runningInput     The running input stream
  * @param runningOutput    The running output stream
  * @param runningError     The running error stream
  * @tparam Out       Output stream element type
  * @tparam OutResult Result type of running the redirected output stream. [[Unit]] if there is no such result.
  * @tparam ErrResult Result type of running the redirected error stream. [[Unit]] if there is no such result.
  */
private[prox] class WrappedProcess[Out, OutResult, ErrResult](systemProcess: java.lang.Process,
                                                              val notStartedOutput: Option[Stream[IO, Out]],
                                                              runningInput: IO[Unit],
                                                              runningOutput: IO[OutResult],
                                                              runningError: IO[ErrResult])
  extends RunningProcess[Out, OutResult, ErrResult] {

  override def isAlive: IO[Boolean] =
    IO {
      systemProcess.isAlive
    }

  override def waitForExit(): IO[ProcessResult[OutResult, ErrResult]] = {
    for {
      exitCode <- IO(systemProcess.waitFor())
    } yield ProcessResult(exitCode, runningOutput.unsafeRunSync(), runningError.unsafeRunSync())
  }

  override def kill(): IO[ProcessResult[OutResult, ErrResult]] = {
    for {
      _ <- IO(systemProcess.destroyForcibly())
      result <- waitForExit()
    } yield result
  }

  override def terminate(): IO[ProcessResult[OutResult, ErrResult]] = {
    for {
      _ <- IO(systemProcess.destroy())
      result <- waitForExit()
    } yield result
  }
}
