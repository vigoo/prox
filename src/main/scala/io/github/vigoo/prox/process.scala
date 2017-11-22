package io.github.vigoo.prox

import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.effect.IO
import fs2._

import scala.concurrent.ExecutionContext
import scala.language.{higherKinds, implicitConversions}

case class ProcessResult[OutResult, ErrResult](exitCode: Int, fullOutput: OutResult, fullError: ErrResult)

trait ProcessIO[O, R] {
  def toRedirect: Redirect

  def connect(systemProcess: java.lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, O]
  def run(stream: Stream[IO, O])(implicit executionContext: ExecutionContext): IO[IO[R]]
}

case class PipeConstruction[Out](outStream: Stream[IO, Out])

sealed trait RedirectionState

trait NotRedirected extends RedirectionState

trait Redirected extends RedirectionState

sealed trait ProcessNode[Out, Err, IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState] {
}

class PipedProcess[Out, Err, PN1Out, PN1 <: ProcessNode[_, _, _, _, _], PN2 <: ProcessNode[_, _, _, _, _], IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState]
(val from: PN1, val createTo: PipeConstruction[PN1Out] => PN2)
  extends ProcessNode[Out, Err, IRS, ORS, ERS] {
}

class Process[Out, Err, OutResult, ErrResult, IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState]
(val command: String,
 val arguments: List[String],
 val workingDirectory: Option[Path],
 val inputSource: ProcessInputSource,
 val outputTarget: ProcessOutputTarget[Out, OutResult],
 val errorTarget: ProcessErrorTarget[Err, ErrResult])
  extends ProcessNode[Out, Err, IRS, ORS, ERS] {

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

object Process {
  def apply(command: String,
            arguments: List[String] = List.empty,
            workingDirectory: Option[Path] = None): Process[Byte, Byte, Unit, Unit, NotRedirected, NotRedirected, NotRedirected] =
    new Process[Byte, Byte, Unit, Unit, NotRedirected, NotRedirected, NotRedirected](command, arguments, workingDirectory, StdIn, StdOut, StdError)
}

trait RunningProcess[Out, OutResult, ErrResult] {
  def isAlive: IO[Boolean]

  def waitForExit(): IO[ProcessResult[OutResult, ErrResult]]

  def kill(): IO[ProcessResult[OutResult, ErrResult]]

  def terminate(): IO[ProcessResult[OutResult, ErrResult]]

  def notStartedOutput: Option[Stream[IO, Out]]
}

class WrappedProcess[Out, OutResult, ErrResult](systemProcess: java.lang.Process,
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
