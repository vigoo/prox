package io.github.vigoo.prox

import java.lang.{Process => JvmProcess}

import cats.Applicative
import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect._
import cats.implicits._
import fs2._

import scala.concurrent.blocking
import scala.jdk.CollectionConverters._
import scala.language.higherKinds

trait ProcessRunner[F[_]] {
  implicit val concurrent: Concurrent[F]

  def startProcess[O, E](process: Process[F, O, E], blocker: Blocker): F[RunningProcess[F, O, E]]

  def start[O, E](process: Process[F, O, E], blocker: Blocker): Resource[F, Fiber[F, ProcessResult[O, E]]] = {
    val run = Concurrent[F].start(
      Sync[F].bracketCase(startProcess(process, blocker)) { runningProcess =>
        runningProcess.waitForExit()
      } {
        case (_, Completed) =>
          Applicative[F].unit
        case (_, Error(reason)) =>
          Sync[F].raiseError(reason)
        case (runningProcess, Canceled) =>
          runningProcess.terminate() >> Applicative[F].unit
      })

    Resource.make(run)(_.cancel)
  }

  def startProcessGroup[O, E](processGroup: ProcessGroup[F, O, E], blocker: Blocker): F[RunningProcessGroup[F, O, E]]

  def start[O, E](processGroup: ProcessGroup[F, O, E], blocker: Blocker): Resource[F, Fiber[F, ProcessGroupResult[F, O, E]]] = {
    val run =
      Concurrent[F].start(
        Sync[F].bracketCase(startProcessGroup(processGroup, blocker)) { runningProcess =>
          runningProcess.waitForExit()
        } {
          case (_, Completed) =>
            Applicative[F].unit
          case (_, Error(reason)) =>
            Sync[F].raiseError(reason)
          case (runningProcess, Canceled) =>
            runningProcess.terminate() >> Applicative[F].unit
        }
      )

    Resource.make(run)(_.cancel)
  }
}


class JVMRunningProcess[F[_] : Sync, O, E](val nativeProcess: JvmProcess,
                                           override val runningInput: Fiber[F, Unit],
                                           override val runningOutput: Fiber[F, O],
                                           override val runningError: Fiber[F, E])
  extends RunningProcess[F, O, E] {

  def isAlive: F[Boolean] =
    Sync[F].delay(nativeProcess.isAlive)

  def kill(): F[ProcessResult[O, E]] =
    Sync[F].delay(nativeProcess.destroyForcibly()) *> waitForExit()

  def terminate(): F[ProcessResult[O, E]] =
    Sync[F].delay(nativeProcess.destroy()) *> waitForExit()

  def waitForExit(): F[ProcessResult[O, E]] = {
    for {
      exitCode <- Sync[F].delay(nativeProcess.waitFor())
      _ <- runningInput.join
      output <- runningOutput.join
      error <- runningError.join
    } yield SimpleProcessResult(ExitCode(exitCode), output, error)
  }
}

class JVMRunningProcessGroup[F[_] : Sync, O, E](runningProcesses: Map[Process[F, Unit, Unit], RunningProcess[F, _, E]],
                                                override val runningOutput: Fiber[F, O])
  extends RunningProcessGroup[F, O, E] {

  def kill(): F[ProcessGroupResult[F, O, E]] =
    runningProcesses.values.toList.traverse(_.kill() *> Sync[F].unit) >> waitForExit()

  def terminate(): F[ProcessGroupResult[F, O, E]] =
    runningProcesses.values.toList.traverse(_.terminate() *> Sync[F].unit) >> waitForExit()

  def waitForExit(): F[ProcessGroupResult[F, O, E]] =
    for {
      results <- runningProcesses.toList.traverse { case (spec, rp) =>
        rp.waitForExit().map((result: ProcessResult[_, E]) => spec -> result)
      }
      lastOutput <- runningOutput.join
      exitCodes = results.map { case (proc, result) => proc -> result.exitCode }.toMap
      errors = results.map { case (proc, result) => proc -> result.error }.toMap
    } yield SimpleProcessGroupResult(exitCodes, lastOutput, errors)
}

class JVMProcessRunner[F[_]](implicit override val concurrent: Concurrent[F],
                             contextShift: ContextShift[F])
  extends ProcessRunner[F] {

  import JVMProcessRunner._

  override def startProcess[O, E](process: Process[F, O, E], blocker: Blocker): F[RunningProcess[F, O, E]] = {
    val builder = withEnvironmentVariables(process,
      withWorkingDirectory(process,
        new ProcessBuilder((process.command :: process.arguments).asJava)))

    builder.redirectOutput(ouptutRedirectionToNative(process.outputRedirection))
    builder.redirectError(ouptutRedirectionToNative(process.errorRedirection))
    builder.redirectInput(inputRedirectionToNative(process.inputRedirection))

    for {
      nativeProcess <- Sync[F].delay(builder.start())
      nativeOutputStream <- Sync[F].delay(nativeProcess.getInputStream)
      nativeErrorStream <- Sync[F].delay(nativeProcess.getErrorStream)

      inputStream = runInputStream(process, nativeProcess, blocker)
      runningInput <- Concurrent[F].start(inputStream)
      runningOutput <- Concurrent[F].start(process.runOutputStream(nativeOutputStream, blocker, implicitly[ContextShift[F]]))
      runningError <- Concurrent[F].start(process.runErrorStream(nativeErrorStream, blocker, implicitly[ContextShift[F]]))
    } yield new JVMRunningProcess(nativeProcess, runningInput, runningOutput, runningError)
  }

  private def connectAndStartProcesses[E](firstProcess: Process[F, Stream[F, Byte], E] with RedirectableInput[F, Process[F, Stream[F, Byte], E]],
                                          previousOutput: Stream[F, Byte],
                                          remainingProcesses: List[Process[F, Stream[F, Byte], E] with RedirectableInput[F, Process[F, Stream[F, Byte], E]]],
                                          blocker: Blocker,
                                          startedProcesses: List[RunningProcess[F, _, E]]): F[(List[RunningProcess[F, _, E]], Stream[F, Byte])] = {
    startProcess(firstProcess.connectInput(InputStream(previousOutput, flushChunks = false)), blocker).flatMap { first =>
      first.runningOutput.join.flatMap { firstOutput =>
        val updatedStartedProcesses = first :: startedProcesses
        remainingProcesses match {
          case nextProcess :: rest =>
            connectAndStartProcesses(nextProcess, firstOutput, rest, blocker, updatedStartedProcesses)
          case Nil =>
            Applicative[F].pure((updatedStartedProcesses.reverse, firstOutput))
        }
      }
    }
  }

  override def startProcessGroup[O, E](processGroup: ProcessGroup[F, O, E], blocker: Blocker): F[RunningProcessGroup[F, O, E]] =
    for {
      first <- startProcess(processGroup.firstProcess, blocker)
      firstOutput <- first.runningOutput.join
      innerResult <- if (processGroup.innerProcesses.isEmpty) {
        Sync[F].pure((List.empty, firstOutput))
      } else {
        val inner = processGroup.innerProcesses.reverse
        connectAndStartProcesses(inner.head, firstOutput, inner.tail, blocker, List.empty)
      }
      (inner, lastInput) = innerResult
      last <- startProcess(processGroup.lastProcess.connectInput(InputStream(lastInput, flushChunks = false)), blocker)
      runningProcesses = processGroup.originalProcesses.reverse.zip((first :: inner) :+ last).toMap
    } yield new JVMRunningProcessGroup[F, O, E](
      runningProcesses,
      last.runningOutput)

  private def runInputStream[O, E](process: Process[F, O, E], nativeProcess: JvmProcess, blocker: Blocker): F[Unit] = {
    process.inputRedirection match {
      case StdIn() => Applicative[F].unit
      case InputFile(_) => Applicative[F].unit
      case InputStream(stream, false) =>
        stream
          .observe(
            io.writeOutputStream[F](
              Sync[F].delay(nativeProcess.getOutputStream),
              closeAfterUse = true,
              blocker = blocker))
          .compile
          .drain
      case InputStream(stream, true) =>
        stream
          .observe(writeAndFlushOutputStream(nativeProcess.getOutputStream, blocker))
          .compile
          .drain
    }
  }
}

object JVMProcessRunner {
  def withWorkingDirectory[F[_], O, E](process: Process[F, O, E], builder: ProcessBuilder): ProcessBuilder =
    process.workingDirectory match {
      case Some(directory) => builder.directory(directory.toFile)
      case None => builder
    }

  def withEnvironmentVariables[F[_], O, E](process: Process[F, O, E], builder: ProcessBuilder): ProcessBuilder = {
    process.environmentVariables.foreach { case (name, value) =>
      builder.environment().put(name, value)
    }
    process.removedEnvironmentVariables.foreach { name =>
      builder.environment().remove(name)
    }
    builder
  }

  def ouptutRedirectionToNative[F[_]](outputRedirection: OutputRedirection[F]): ProcessBuilder.Redirect = {
    outputRedirection match {
      case StdOut() => ProcessBuilder.Redirect.INHERIT
      case OutputFile(path, false) => ProcessBuilder.Redirect.to(path.toFile)
      case OutputFile(path, true) => ProcessBuilder.Redirect.appendTo(path.toFile)
      case OutputStream(_, _, _) => ProcessBuilder.Redirect.PIPE
    }
  }

  def inputRedirectionToNative[F[_]](inputRedirection: InputRedirection[F]): ProcessBuilder.Redirect = {
    inputRedirection match {
      case StdIn() => ProcessBuilder.Redirect.INHERIT
      case InputFile(path) => ProcessBuilder.Redirect.from(path.toFile)
      case InputStream(_, _) => ProcessBuilder.Redirect.PIPE
    }
  }

  def writeAndFlushOutputStream[F[_] : Applicative : Sync](stream: java.io.OutputStream,
                                                           blocker: Blocker)
                                                          (implicit contextShift: ContextShift[F]): Pipe[F, Byte, Unit] =
    s => {
      Stream
        .bracket(Applicative[F].pure(stream))(os => Sync[F].delay(os.close()))
        .flatMap { os =>
          s.chunks.evalMap { chunk =>
            blocker.blockOn {
              Sync[F].delay {
                blocking {
                  os.write(chunk.toArray)
                  os.flush()
                }
              }
            }
          }
        }
    }
}
