package io.github.vigoo.prox

import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path

import cats.effect.IO
import fs2._

import scala.concurrent.ExecutionContext
import scala.language.{higherKinds, implicitConversions}

case class ProcessResult(exitCode: Int)

trait ProcessIO {
  def toRedirect: Redirect

  def connect(systemProcess: java.lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte]
}

case class PipeConstruction(outStream: Stream[IO, Byte], errStream: Stream[IO, Byte])

sealed trait RedirectionState

trait NotRedirected extends RedirectionState

trait Redirected extends RedirectionState

sealed trait ProcessNode[InputRedirectionState <: RedirectionState, OutputRedirectionState <: RedirectionState, ErrorRedirectionState <: RedirectionState] {
  type RedirectedOutput <: ProcessNode[_, _, _]
  type RedirectedInput <: ProcessNode[_, _, _]
  type RedirectedError <: ProcessNode[_, _, _]

  type OutputRedirected <: RedirectionState
  type ErrorRedirected <: RedirectionState

  private[prox] def unsafeChangeRedirectedOutput[To: CanBeProcessOutputTarget](to: To): RedirectedOutput

  private[prox] def unsafeChangeRedirectedError[To: CanBeProcessErrorTarget](to: To): RedirectedError

  private[prox] def unsafeChangeRedirectedInput[From: CanBeProcessInputSource](from: From): RedirectedInput
}

case class PipedProcess[PN1 <: ProcessNode[_, _, _], PN2 <: ProcessNode[_, _, _], InputRedirectionState <: RedirectionState, OutputRedirectionState <: RedirectionState, ErrorRedirectionState <: RedirectionState]
(from: PN1, createTo: PipeConstruction => PN2)
  extends ProcessNode[InputRedirectionState, OutputRedirectionState, ErrorRedirectionState] {

  override type RedirectedInput = PipedProcess[PN1#RedirectedInput, PN2, Redirected, OutputRedirectionState, ErrorRedirectionState]
  override type RedirectedOutput = PipedProcess[PN1, PN2#RedirectedOutput, InputRedirectionState, Redirected, ErrorRedirectionState]
  override type RedirectedError = PipedProcess[PN1, PN2#RedirectedError, InputRedirectionState, OutputRedirectionState, Redirected]

  override type OutputRedirected = OutputRedirectionState
  override type ErrorRedirected = ErrorRedirectionState

  def |[PN <: ProcessNode[_, _, _]](to: PN):
  PipedProcess[RedirectedOutput, PN#RedirectedInput, InputRedirectionState, PN#OutputRedirected, ErrorRedirectionState] = {
    val channel: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    PipedProcess(
      this.unsafeChangeRedirectedOutput(channel),
      construction => to.unsafeChangeRedirectedInput(construction.outStream))
  }

  private[prox] override def unsafeChangeRedirectedOutput[To: CanBeProcessOutputTarget](to: To): RedirectedOutput =
    PipedProcess(from, createTo.andThen(_.unsafeChangeRedirectedOutput(to)))

  private[prox] override def unsafeChangeRedirectedError[To: CanBeProcessErrorTarget](to: To): RedirectedError =
    PipedProcess(from, createTo.andThen(_.unsafeChangeRedirectedError(to)))

  private[prox] override def unsafeChangeRedirectedInput[From: CanBeProcessInputSource](from: From): RedirectedInput =
    PipedProcess(this.from.unsafeChangeRedirectedInput(from), createTo)
}

class Process[InputRedirectionState <: RedirectionState, OutputRedirectionState <: RedirectionState, ErrorRedirectionState <: RedirectionState]
(val command: String,
 val arguments: List[String] = List.empty,
 val workingDirectory: Option[Path] = None,
 val inputSource: ProcessInputSource = StdIn,
 val outputTarget: ProcessOutputTarget = StdOut,
 val errorTarget: ProcessErrorTarget = StdError)
  extends ProcessNode[InputRedirectionState, OutputRedirectionState, ErrorRedirectionState] {

  override type RedirectedInput = Process[Redirected, OutputRedirectionState, ErrorRedirectionState]
  override type RedirectedOutput = Process[InputRedirectionState, Redirected, ErrorRedirectionState]
  override type RedirectedError = Process[InputRedirectionState, OutputRedirectionState, Redirected]

  override type OutputRedirected = OutputRedirectionState
  override type ErrorRedirected = ErrorRedirectionState

  def |[PN <: ProcessNode[_, _, _]](to: PN):
  PipedProcess[Process[InputRedirectionState, Redirected, ErrorRedirectionState], PN#RedirectedInput, InputRedirectionState, PN#OutputRedirected, ErrorRedirectionState] = {
    val channel: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    PipedProcess(
      this.unsafeChangeRedirectedOutput(channel),
      construction => to.unsafeChangeRedirectedInput(construction.outStream))
  }

  private[prox] override def unsafeChangeRedirectedOutput[To: CanBeProcessOutputTarget](to: To): RedirectedOutput = {
    new Process[InputRedirectionState, Redirected, ErrorRedirectionState](
      command = command,
      arguments = arguments,
      workingDirectory = workingDirectory,
      inputSource = inputSource,
      outputTarget = implicitly[CanBeProcessOutputTarget[To]].target(to),
      errorTarget = errorTarget
    )
  }

  private[prox] override def unsafeChangeRedirectedInput[From: CanBeProcessInputSource](from: From): RedirectedInput = {
    new Process[Redirected, OutputRedirectionState, ErrorRedirectionState](
      command = command,
      arguments = arguments,
      workingDirectory = workingDirectory,
      inputSource = implicitly[CanBeProcessInputSource[From]].source(from),
      outputTarget = outputTarget,
      errorTarget = errorTarget)
  }

  private[prox] override def unsafeChangeRedirectedError[To: CanBeProcessErrorTarget](to: To): RedirectedError = {
    new Process[InputRedirectionState, OutputRedirectionState, Redirected](
      command = command,
      arguments = arguments,
      workingDirectory = workingDirectory,
      inputSource = inputSource,
      outputTarget = outputTarget,
      errorTarget = implicitly[CanBeProcessErrorTarget[To]].target(to),
    )
  }

  def in(workingDirectory: Path): Process[InputRedirectionState, OutputRedirectionState, ErrorRedirectionState] = {
    new Process[InputRedirectionState, OutputRedirectionState, ErrorRedirectionState](
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
            workingDirectory: Option[Path] = None): Process[NotRedirected, NotRedirected, NotRedirected] =
    new Process[NotRedirected, NotRedirected, NotRedirected](command, arguments, workingDirectory)
}

trait RunningProcess {
  def isAlive: IO[Boolean]

  def waitForExit(): IO[ProcessResult]

  def kill(): IO[ProcessResult]

  def terminate(): IO[ProcessResult]

  def input: Stream[IO, Byte]

  def output: Stream[IO, Byte]

  def error: Stream[IO, Byte]
}

class WrappedProcess(systemProcess: java.lang.Process,
                     val input: Stream[IO, Byte],
                     val output: Stream[IO, Byte],
                     val error: Stream[IO, Byte]) extends RunningProcess {

  override def isAlive: IO[Boolean] =
    IO {
      systemProcess.isAlive
    }

  override def waitForExit(): IO[ProcessResult] = {
    for {
      exitCode <- IO {
        systemProcess.waitFor()
      }
    } yield ProcessResult(exitCode)
  }

  override def kill(): IO[ProcessResult] = {
    for {
      _ <- IO {
        systemProcess.destroyForcibly()
      }
      exitCode <- waitForExit()
    } yield exitCode
  }

  override def terminate(): IO[ProcessResult] = {
    for {
      _ <- IO {
        systemProcess.destroy()
      }
      exitCode <- waitForExit()
    } yield exitCode
  }
}
