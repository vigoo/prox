package io.github.vigoo.prox

import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.effect.IO
import fs2._

import scala.concurrent.ExecutionContext
import scala.language.{higherKinds, implicitConversions}

case class ProcessResult[Out, Err](exitCode: Int, fullOutput: Vector[Out], fullError: Vector[Err])

trait ProcessIO[O] {
  def toRedirect: Redirect
  def connect(systemProcess: java.lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, O]
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

class Process[Out, Err, IRS <: RedirectionState, ORS <: RedirectionState, ERS <: RedirectionState]
(val command: String,
 val arguments: List[String],
 val workingDirectory: Option[Path],
 val inputSource: ProcessInputSource,
 val outputTarget: ProcessOutputTarget[Out],
 val errorTarget: ProcessErrorTarget[Err])
  extends ProcessNode[Out, Err, IRS, ORS, ERS] {

  def in(workingDirectory: Path): Process[Out, Err, IRS, ORS, ERS] = {
    new Process[Out, Err, IRS, ORS, ERS](
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
            workingDirectory: Option[Path] = None): Process[Byte, Byte, NotRedirected, NotRedirected, NotRedirected] =
    new Process[Byte, Byte, NotRedirected, NotRedirected, NotRedirected](command, arguments, workingDirectory, StdIn, StdOut, StdError)
}

trait RunningProcess[Out, Err] {
  def isAlive: IO[Boolean]

  def waitForExit(): IO[ProcessResult[Out, Err]]

  def kill(): IO[ProcessResult[Out, Err]]

  def terminate(): IO[ProcessResult[Out, Err]]

  def notStartedOutput: Option[Stream[IO, Out]]
}

class WrappedProcess[Out, Err](systemProcess: java.lang.Process,
                               val notStartedOutput: Option[Stream[IO, Out]],
                               runningInput: IO[Unit],
                               runningOutput: IO[Vector[Out]],
                               runningError: IO[Vector[Err]])
  extends RunningProcess[Out, Err] {

  override def isAlive: IO[Boolean] =
    IO {
      systemProcess.isAlive
    }

  override def waitForExit(): IO[ProcessResult[Out, Err]] = {
    for {
      exitCode <- IO(systemProcess.waitFor())
    } yield ProcessResult(exitCode, runningOutput.unsafeRunSync(), runningError.unsafeRunSync())
  }

  override def kill(): IO[ProcessResult[Out, Err]] = {
    for {
      _ <- IO(systemProcess.destroyForcibly())
      exitCode <- waitForExit()
    } yield exitCode
  }

  override def terminate(): IO[ProcessResult[Out, Err]] = {
    for {
      _ <- IO(systemProcess.destroy())
      exitCode <- waitForExit()
    } yield exitCode
  }
}
