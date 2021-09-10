package io.github.vigoo.prox

import java.lang.{Process => JvmProcess}

import scala.concurrent.blocking
import scala.jdk.CollectionConverters._

trait ProcessRunnerModule {
  this: Prox =>

  /**
    * Interface for running processes and process groups
    *
    * The default implementation is [[JVMProcessRunner]]
    *
    * @tparam Info The type of information provided for a started process
    */
  trait ProcessRunner[Info] {
    /**
      * Starts the process asynchronously and returns the [[RunningProcess]] interface for it
      *
      * @param process The process to be started
      * @tparam O Output type
      * @tparam E Error output type
      * @return interface for handling the running process
      */
    def startProcess[O, E](process: Process[O, E]): ProxIO[RunningProcess[O, E, Info]]

    /**
      * Starts the process asynchronously and returns a managed fiber representing it
      *
      * Joining the fiber means waiting until the process gets terminated.
      * Cancelling the fiber terminates the process.
      *
      * @param process The process to be started
      * @tparam O Output type
      * @tparam E Error output type
      * @return interface for handling the running process
      */
    def start[O, E](process: Process[O, E]): ProxResource[ProxFiber[ProcessResult[O, E]]] = {
      val run = startFiber(
        bracket(startProcess(process)) { runningProcess =>
          runningProcess.waitForExit()
        } {
          case (_, Completed) =>
            unit
          case (_, Failed(reason)) =>
            raiseError(reason.toSingleError)
          case (runningProcess, Canceled) =>
            runningProcess.terminate().map(_ => ())
        })

      makeResource(run, _.cancel)
    }

    /**
      * Starts a process group asynchronously and returns an interface for them
      *
      * @param processGroup The process group to start
      * @tparam O Output type
      * @tparam E Error output type
      * @return interface for handling the running process group
      */
    def startProcessGroup[O, E](processGroup: ProcessGroup[O, E]): ProxIO[RunningProcessGroup[O, E, Info]]

    /**
      * Starts the process group asynchronously and returns a managed fiber representing it
      *
      * Joining the fiber means waiting until the process gets terminated.
      * Cancelling the fiber terminates the process.
      *
      * @param processGroup The process group to be started
      * @tparam O Output type
      * @tparam E Error output type
      * @return interface for handling the running process
      */
    def start[O, E](processGroup: ProcessGroup[O, E]): ProxResource[ProxFiber[ProcessGroupResult[O, E]]] = {
      val run =
        startFiber(
          bracket(startProcessGroup(processGroup)) { runningProcess =>
            runningProcess.waitForExit()
          } {
            case (_, Completed) =>
              unit
            case (_, Failed(reason)) =>
              raiseError(reason.toSingleError)
            case (runningProcess, Canceled) =>
              runningProcess.terminate().map(_ => ())
          }
        )

      makeResource(run, _.cancel)
    }
  }

  class JVMProcessInfo()

  /** Default implementation of [[RunningProcess]] using the Java process API */
  class JVMRunningProcess[O, E, +Info <: JVMProcessInfo](val nativeProcess: JvmProcess,
                                                         override val runningInput: ProxFiber[Unit],
                                                         override val runningOutput: ProxFiber[O],
                                                         override val runningError: ProxFiber[E],
                                                         override val info: Info)
    extends RunningProcess[O, E, Info] {

    def isAlive: ProxIO[Boolean] =
      effect(nativeProcess.isAlive, FailedToQueryState.apply)

    def kill(): ProxIO[ProcessResult[O, E]] =
      effect(nativeProcess.destroyForcibly(), FailedToDestroy.apply).flatMap(_ => waitForExit())

    def terminate(): ProxIO[ProcessResult[O, E]] =
      effect(nativeProcess.destroy(), FailedToDestroy.apply).flatMap(_ => waitForExit())

    def waitForExit(): ProxIO[ProcessResult[O, E]] = {
      for {
        exitCode <- effect(nativeProcess.waitFor(), FailedToWaitForExit.apply)
        _ <- runningInput.join
        output <- runningOutput.join
        error <- runningError.join
      } yield SimpleProcessResult(exitCodeFromInt(exitCode), output, error)
    }
  }

  /** Default implementation of [[RunningProcessGroup]] using the Java process API */
  class JVMRunningProcessGroup[O, E, +Info <: JVMProcessInfo](runningProcesses: Map[Process[Unit, Unit], RunningProcess[_, E, Info]],
                                                              override val runningOutput: ProxFiber[O])
    extends RunningProcessGroup[O, E, Info] {

    override val info: Map[Process[Unit, Unit], Info] =
      runningProcesses.map { case (key, value) => (key, value.info) }

    def kill(): ProxIO[ProcessGroupResult[O, E]] =
      traverse(runningProcesses.values.toList)(_.kill().map(_ => ())).flatMap(_ => waitForExit())

    def terminate(): ProxIO[ProcessGroupResult[O, E]] =
      traverse(runningProcesses.values.toList)(_.terminate().map(_ => ())).flatMap(_ => waitForExit())

    def waitForExit(): ProxIO[ProcessGroupResult[O, E]] =
      for {
        results <- traverse(runningProcesses.toList) { case (spec, rp) =>
          rp.waitForExit().map((result: ProcessResult[_, E]) => spec -> result)
        }
        lastOutput <- runningOutput.join
        exitCodes = results.map { case (proc, result) => proc -> result.exitCode }.toMap
        errors = results.map { case (proc, result) => proc -> result.error }.toMap
      } yield SimpleProcessGroupResult(exitCodes, lastOutput, errors)
  }

  /** Default implementation of [[ProcessRunner]] using the Java process API */
  abstract class JVMProcessRunnerBase[Info <: JVMProcessInfo]
    extends ProcessRunner[Info] {

    import JVMProcessRunnerBase._

    override def startProcess[O, E](process: Process[O, E]): ProxIO[RunningProcess[O, E, Info]] = {
      val builder = withEnvironmentVariables(process,
        withWorkingDirectory(process,
          new ProcessBuilder((process.command :: process.arguments).asJava)))

      builder.redirectOutput(ouptutRedirectionToNative(process.outputRedirection))
      builder.redirectError(ouptutRedirectionToNative(process.errorRedirection))
      builder.redirectInput(inputRedirectionToNative(process.inputRedirection))

      for {
        _ <- effect(println("Before process start"), FailedToStartProcess.apply)
        nativeProcess <- effect(builder.start(), FailedToStartProcess.apply)
        _ <- effect(println("Process started"), FailedToStartProcess.apply)
        processInfo <- getProcessInfo(nativeProcess)
        _ <- effect(println(s"Got process info: $processInfo"), FailedToStartProcess.apply)
        nativeOutputStream <- effect(nativeProcess.getInputStream, UnknownProxError.apply)
        nativeErrorStream <- effect(nativeProcess.getErrorStream, UnknownProxError.apply)
        _ <- effect(println("Got output and error streams"), FailedToStartProcess.apply)

        inputStream = runInputStream(process, nativeProcess)
        _ <- effect(println("Defined input stream"), FailedToStartProcess.apply)
        runningInput <- startFiber(inputStream)
        _ <- effect(println("Started input stream"), FailedToStartProcess.apply)
        runningOutput <- startFiber(process.runOutputStream(nativeOutputStream))
        _ <- effect(println("Started output stream"), FailedToStartProcess.apply)
        runningError <- startFiber(process.runErrorStream(nativeErrorStream))
        _ <- effect(println("Started error stream"), FailedToStartProcess.apply)
      } yield new JVMRunningProcess(nativeProcess, runningInput, runningOutput, runningError, processInfo)
    }

    protected def getProcessInfo(process: JvmProcess): ProxIO[Info]

    private def connectAndStartProcesses[E](firstProcess: Process[ProxStream[Byte], E] with RedirectableInput[Process[ProxStream[Byte], E]],
                                            previousOutput: ProxStream[Byte],
                                            remainingProcesses: List[Process[ProxStream[Byte], E] with RedirectableInput[Process[ProxStream[Byte], E]]],
                                            startedProcesses: List[RunningProcess[_, E, Info]]): ProxIO[(List[RunningProcess[_, E, Info]], ProxStream[Byte])] = {
      startProcess(firstProcess.connectInput(InputStream(previousOutput, flushChunks = false))).flatMap { first =>
        first.runningOutput.join.flatMap { firstOutput =>
          val updatedStartedProcesses = first :: startedProcesses
          remainingProcesses match {
            case nextProcess :: rest =>
              connectAndStartProcesses(nextProcess, firstOutput, rest, updatedStartedProcesses)
            case Nil =>
              pure((updatedStartedProcesses.reverse, firstOutput))
          }
        }
      }
    }

    override def startProcessGroup[O, E](processGroup: ProcessGroup[O, E]): ProxIO[RunningProcessGroup[O, E, Info]] =
      for {
        first <- startProcess(processGroup.firstProcess)
        firstOutput <- first.runningOutput.join
        innerResult <- if (processGroup.innerProcesses.isEmpty) {
          pure((List.empty, firstOutput))
        } else {
          val inner = processGroup.innerProcesses.reverse
          connectAndStartProcesses(inner.head, firstOutput, inner.tail, List.empty)
        }
        (inner, lastInput) = innerResult
        last <- startProcess(processGroup.lastProcess.connectInput(InputStream(lastInput, flushChunks = false)))
        runningProcesses = processGroup.originalProcesses.reverse.zip((first :: inner) :+ last).toMap
      } yield new JVMRunningProcessGroup[O, E, Info](
        runningProcesses,
        last.runningOutput)

    private def runInputStream[O, E](process: Process[O, E], nativeProcess: JvmProcess): ProxIO[Unit] = {
      process.inputRedirection match {
        case StdIn() => unit
        case InputFile(_) => unit
        case InputStream(stream, flushChunks) =>
          drainToJavaOutputStream(stream, nativeProcess.getOutputStream, flushChunks)
      }
    }
  }

  object JVMProcessRunnerBase {
    def withWorkingDirectory[O, E](process: Process[O, E], builder: ProcessBuilder): ProcessBuilder =
      process.workingDirectory match {
        case Some(directory) => builder.directory(directory.toFile)
        case None => builder
      }

    def withEnvironmentVariables[O, E](process: Process[O, E], builder: ProcessBuilder): ProcessBuilder = {
      process.environmentVariables.foreach { case (name, value) =>
        builder.environment().put(name, value)
      }
      process.removedEnvironmentVariables.foreach { name =>
        builder.environment().remove(name)
      }
      builder
    }

    def ouptutRedirectionToNative(outputRedirection: OutputRedirection): ProcessBuilder.Redirect = {
      outputRedirection match {
        case StdOut() => ProcessBuilder.Redirect.INHERIT
        case OutputFile(path, false) => ProcessBuilder.Redirect.to(path.toFile)
        case OutputFile(path, true) => ProcessBuilder.Redirect.appendTo(path.toFile)
        case OutputStreamThroughPipe(_, _, _) => ProcessBuilder.Redirect.PIPE
        case OutputStreamToSink(_, _) => ProcessBuilder.Redirect.PIPE
      }
    }

    def inputRedirectionToNative(inputRedirection: InputRedirection): ProcessBuilder.Redirect = {
      inputRedirection match {
        case StdIn() => ProcessBuilder.Redirect.INHERIT
        case InputFile(path) => ProcessBuilder.Redirect.from(path.toFile)
        case InputStream(_, _) => ProcessBuilder.Redirect.PIPE
      }
    }
  }

  class JVMProcessRunner()
    extends JVMProcessRunnerBase[JVMProcessInfo] {

    override protected def getProcessInfo(process: JvmProcess): ProxIO[JVMProcessInfo] =
      effect(new JVMProcessInfo(), UnknownProxError.apply)
  }

}