package io.github.vigoo.prox

import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.util.concurrent.Executors

import cats.effect.IO
import fs2._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.language.{higherKinds, implicitConversions}
import FixList._

case class ProcessResult(exitCode: Int)

trait ProcessIO {
  def toRedirect: Redirect
  def connect(systemProcess: java.lang.Process)(implicit executionContext: ExecutionContext): Stream[IO, Byte]
}

case class PipeConstruction(outStream: Stream[IO, Byte], errStream: Stream[IO, Byte])

sealed trait ProcessNode {
  type RunningProcesses <: FixList[RunningProcess]
  type RedirectedOutput <: ProcessNode
  type RedirectedInput <: ProcessNode

  def >[To : CanBeProcessOutputTarget](to: To): RedirectedOutput
  def <[From: CanBeProcessInputSource](from: From): RedirectedInput

  def start()(implicit executionContext: ExecutionContext): IO[RunningProcesses]
}

case class PipedProcess[PN1 <: ProcessNode, PN2 <: ProcessNode](from: PN1, createTo: PipeConstruction => PN2)
  extends ProcessNode {

  override type RunningProcesses = Concatenated[RunningProcess, PN1#RunningProcesses, PN2#RunningProcesses]
  override type RedirectedInput = PipedProcess[PN1#RedirectedInput, PN2]
  override type RedirectedOutput = PipedProcess[PN1, PN2#RedirectedOutput]

  override def start()(implicit executionContext: ExecutionContext): IO[RunningProcesses] = {
    from.start().flatMap { runningSourceProcesses =>
      runningSourceProcesses.last match {
        case Some(runningFrom) =>
          val to = createTo(PipeConstruction(runningFrom.output, runningFrom.error))
          to.start().flatMap { runningTargetProcesses =>
            runningTargetProcesses.first match {
              case Some(runningTo) =>
                runningTo.input.run.map { _ =>
                  runningSourceProcesses.append(runningTargetProcesses)
                }
              case None =>
                IO.raiseError(new IllegalStateException("Invalid piped process construction"))
            }
          }

        case None =>
          IO.raiseError(new IllegalStateException("Invalid piped process construction"))
      }
    }
  }

  override def >[To: CanBeProcessOutputTarget](to: To): RedirectedOutput =
    PipedProcess(from, createTo.andThen(_ > to))

  override def <[From: CanBeProcessInputSource](from: From): RedirectedInput =
    PipedProcess(this.from < from, createTo)
}

case class Process(command: String,
                   arguments: List[String] = List.empty,
                   workingDirectory: Option[Path] = None,
                   inputSource: ProcessInputSource = StdIn,
                   outputTarget: ProcessOutputTarget = StdOut,
                   errorTarget: ProcessErrorTarget = StdError)
  extends ProcessNode {

  override type RunningProcesses = RunningProcess :|: FixNil[RunningProcess]
  override type RedirectedInput = Process
  override type RedirectedOutput = Process

  override def start()(implicit executionContext: ExecutionContext): IO[RunningProcesses] = {
    def withWorkingDirectory(builder: ProcessBuilder): ProcessBuilder =
      workingDirectory match {
        case Some(directory) => builder.directory(directory.toFile)
        case None => builder
      }

    val builder = withWorkingDirectory(new ProcessBuilder((command :: arguments).asJava))
    builder.redirectInput(inputSource.toRedirect)
    builder.redirectOutput(outputTarget.toRedirect)
    builder.redirectError(outputTarget.toRedirect)
    for {
      proc <- IO { builder.start }
      inputStream = inputSource.connect(proc)
      outputStream = outputTarget.connect(proc)
      errorStream = errorTarget.connect(proc)
    } yield new WrappedProcess(proc, inputStream, outputStream, errorStream) :|: FixNil[RunningProcess]
  }

  def |[PN <: ProcessNode](to: PN): PipedProcess[Process, PN#RedirectedInput] = {
    import implicits._
    val channel: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    PipedProcess[Process, PN#RedirectedInput](
      this > channel,
      construction => to < construction.outStream)
  }

  override def >[To : CanBeProcessOutputTarget](to: To): RedirectedOutput = {
    this.copy(
      outputTarget = implicitly[CanBeProcessOutputTarget[To]].target(to)
    )
  }

  override def <[From: CanBeProcessInputSource](from: From): RedirectedInput = {
    this.copy(
      inputSource = implicitly[CanBeProcessInputSource[From]].source(from)
    )
  }

  def in(workingDirectory: Path): Process = {
    this.copy(workingDirectory = Some(workingDirectory))
  }
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
    IO { systemProcess.isAlive }

  override def waitForExit(): IO[ProcessResult] = {
    for {
      exitCode <- IO { systemProcess.waitFor() }
    } yield ProcessResult(exitCode)
  }

  override def kill(): IO[ProcessResult] = {
    for {
      _ <- IO { systemProcess.destroyForcibly() }
      exitCode <- waitForExit()
    } yield exitCode
  }

  override def terminate(): IO[ProcessResult] = {
    for {
      _ <- IO { systemProcess.destroy() }
      exitCode <- waitForExit()
    } yield exitCode
  }
}
