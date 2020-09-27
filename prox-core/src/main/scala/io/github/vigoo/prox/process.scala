package io.github.vigoo.prox

import java.nio.file.Path

trait ProcessModule {
  this: ProxRuntime with CommonModule with ProcessRunnerModule with RedirectionModule =>

  /**
    * Result of a finished process
    *
    * @tparam O Output type
    * @tparam E Error output type
    */
  trait ProcessResult[+O, +E] {
    /** The exit code of the process */
    val exitCode: ProxExitCode

    /** Output value of the process, depends on what output redirection was applied */
    val output: O

    /** Error output value of the process, depends on what error redirection was applied */
    val error: E
  }

  /** Default implementation of [[ProcessResult]] */
  case class SimpleProcessResult[+O, +E](override val exitCode: ProxExitCode,
                                         override val output: O,
                                         override val error: E)
    extends ProcessResult[O, E]

  /**
    * Representation of a running process
    *
    * @tparam O    Output type
    * @tparam E    Error output type
    * @tparam Info Runner-specific process information
    */
  trait RunningProcess[O, E, +Info] {
    val runningInput: ProxFiber[Unit]
    val runningOutput: ProxFiber[O]
    val runningError: ProxFiber[E]

    /** Gets the runner-specific process information */
    val info: Info

    /** Checks whether the process is still running */
    def isAlive: ProxIO[Boolean]

    /** Forced termination of the process. Blocks until the process stops. */
    def kill(): ProxIO[ProcessResult[O, E]]

    /** Normal termination of the process. Blocks until the process stops. */
    def terminate(): ProxIO[ProcessResult[O, E]]

    /** Block until the process stops */
    def waitForExit(): ProxIO[ProcessResult[O, E]]

    def mapInfo[I2](f: Info => I2): RunningProcess[O, E, I2] =
      new RunningProcess[O, E, I2] {
        override val runningInput: ProxFiber[Unit] = RunningProcess.this.runningInput
        override val runningOutput: ProxFiber[O] = RunningProcess.this.runningOutput
        override val runningError: ProxFiber[E] = RunningProcess.this.runningError
        override val info: I2 = f(RunningProcess.this.info)

        override def isAlive: ProxIO[Boolean] = RunningProcess.this.isAlive

        override def kill(): ProxIO[ProcessResult[O, E]] = RunningProcess.this.kill()

        override def terminate(): ProxIO[ProcessResult[O, E]] = RunningProcess.this.terminate()

        override def waitForExit(): ProxIO[ProcessResult[O, E]] = RunningProcess.this.waitForExit()
      }
  }

  /**
    * Describes a system process to be executed
    *
    * This base trait is always extended with redirection and configuration capabilities represented by the
    * traits [[ProcessConfiguration]], [[RedirectableInput]], [[RedirectableOutput]] and [[RedirectableError]].
    *
    * To create a process use the constructor in the companion object [[Process.apply]].
    *
    * The process specification not only encodes the process to be started but also how its input, output and error
    * streams are redirected and executed. For this reason the effect type is also bound by the process, not just at
    * execution time.
    *
    * @tparam O Output type
    * @tparam E Error output type
    */
  trait Process[O, E] extends ProcessLike with ProcessConfiguration {
    override type Self <: Process[O, E]

    val command: String
    val arguments: List[String]
    val workingDirectory: Option[Path]
    val environmentVariables: Map[String, String]
    val removedEnvironmentVariables: Set[String]

    val outputRedirection: OutputRedirection
    val runOutputStream: java.io.InputStream => ProxIO[O]
    val errorRedirection: OutputRedirection
    val runErrorStream: java.io.InputStream => ProxIO[E]
    val inputRedirection: InputRedirection

    /**
      * Starts the process asynchronously and returns the [[RunningProcess]] interface for it
      *
      * This is the most advanced way to start processes. See [[start]] and [[run]] as alternatives.
      *
      * @param runner The process runner to be used
      * @tparam Info The runner-specific process info type
      * @return interface for handling the running process
      */
    def startProcess[Info]()(implicit runner: ProcessRunner[Info]): ProxIO[RunningProcess[O, E, Info]] =
      runner.startProcess(this)

    /**
      * Starts the process asynchronously and returns a closeable fiber representing it
      *
      * Joining the fiber waits for the process to be terminated. Canceling the fiber terminates
      * the process normally (with SIGTERM).
      *
      * @param runner  The process runner to be used
      * @return a managed fiber representing the running process
      */
    def start[Info]()(implicit runner: ProcessRunner[Info]): ProxResource[ProxFiber[ProcessResult[O, E]]] =
      runner.start(this)

    /**
      * Starts the process asynchronously and blocks the execution until it is finished
      *
      * @param runner  The process runner to be used
      * @return the result of the finished process
      */
    def run[Info]()(implicit runner: ProcessRunner[Info]): ProxIO[ProcessResult[O, E]] =
      start().use(_.join)
  }

  /**
    * The capability to configure process execution details
    *
    */
  trait ProcessConfiguration extends ProcessLikeConfiguration {
    this: Process[_, _] =>

    override type Self <: ProcessConfiguration

    override protected def applyConfiguration(workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): Self =
      selfCopy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)

    protected def selfCopy(command: String,
                           arguments: List[String],
                           workingDirectory: Option[Path],
                           environmentVariables: Map[String, String],
                           removedEnvironmentVariables: Set[String]): Self

    /**
      * Replaces the command
      *
      * @param newCommand new value for the command to be executed
      * @return returns a new process specification
      */
    def withCommand(newCommand: String): Self =
      selfCopy(newCommand, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)

    /**
      * Replaces the arguments
      *
      * @param newArguments new list of arguments
      * @return returns a new process specification
      */
    def withArguments(newArguments: List[String]): Self =
      selfCopy(command, newArguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  object Process {
    /** Process with unbound input stream */
    type UnboundIProcess[O, E] = Process[O, E] with RedirectableInput[Process[O, E]]

    /** Process with unbound output stream */
    type UnboundOProcess[E] = Process[Unit, E] with RedirectableOutput[Process[*, E]]

    /** Process with unbound error stream */
    type UnboundEProcess[O] = Process[O, Unit] with RedirectableError[Process[O, *]]

    /** Process with unbound input and output streams */
    type UnboundIOProcess[E] = Process[Unit, E]
      with RedirectableInput[UnboundOProcess[E]]
      with RedirectableOutput[UnboundIProcess[*, E]]

    /** Process with unbound input and error streams */
    type UnboundIEProcess[O] = Process[O, Unit]
      with RedirectableInput[UnboundEProcess[O]]
      with RedirectableError[UnboundIProcess[O, *]]

    /** Process with unbound output and error streams */
    type UnboundOEProcess = Process[Unit, Unit]
      with RedirectableOutput[UnboundEProcess[*]]
      with RedirectableError[UnboundOProcess[*]]

    /** Process with unbound input, output and error streams */
    type UnboundProcess = Process[Unit, Unit]
      with RedirectableInput[UnboundOEProcess]
      with RedirectableOutput[UnboundIEProcess[*]]
      with RedirectableError[UnboundIOProcess[*]]

    /** Process with bound input, output and error streams */
    case class ProcessImplIOE[O, E](override val command: String,
                                    override val arguments: List[String],
                                    override val workingDirectory: Option[Path],
                                    override val environmentVariables: Map[String, String],
                                    override val removedEnvironmentVariables: Set[String],
                                    override val outputRedirection: OutputRedirection,
                                    override val runOutputStream: java.io.InputStream => ProxIO[O],
                                    override val errorRedirection: OutputRedirection,
                                    override val runErrorStream: java.io.InputStream => ProxIO[E],
                                    override val inputRedirection: InputRedirection)
      extends Process[O, E] {

      override type Self = ProcessImplIOE[O, E]

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIOE[O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    /** Process with bound input and output streams */
    case class ProcessImplIO[O](override val command: String,
                                override val arguments: List[String],
                                override val workingDirectory: Option[Path],
                                override val environmentVariables: Map[String, String],
                                override val removedEnvironmentVariables: Set[String],
                                override val outputRedirection: OutputRedirection,
                                override val runOutputStream: java.io.InputStream => ProxIO[O],
                                override val errorRedirection: OutputRedirection,
                                override val runErrorStream: java.io.InputStream => ProxIO[Unit],
                                override val inputRedirection: InputRedirection)
      extends Process[O, Unit]
        with RedirectableError[ProcessImplIOE[O, *]] {

      override type Self = ProcessImplIO[O]

      override def connectError[R <: OutputRedirection, RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RE]): ProcessImplIOE[O, RE] =
        ProcessImplIOE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIO[O] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    /** Process with bound input and error streams */
    case class ProcessImplIE[E](override val command: String,
                                override val arguments: List[String],
                                override val workingDirectory: Option[Path],
                                override val environmentVariables: Map[String, String],
                                override val removedEnvironmentVariables: Set[String],
                                override val outputRedirection: OutputRedirection,
                                override val runOutputStream: java.io.InputStream => ProxIO[Unit],
                                override val errorRedirection: OutputRedirection,
                                override val runErrorStream: java.io.InputStream => ProxIO[E],
                                override val inputRedirection: InputRedirection)
      extends Process[Unit, E]
        with RedirectableOutput[ProcessImplIOE[*, E]] {

      override type Self = ProcessImplIE[E]

      override def connectOutput[R <: OutputRedirection, RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RO]): ProcessImplIOE[RO, E] =
        ProcessImplIOE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIE[E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    /** Process with bound output and error streams */
    case class ProcessImplOE[O, E](override val command: String,
                                   override val arguments: List[String],
                                   override val workingDirectory: Option[Path],
                                   override val environmentVariables: Map[String, String],
                                   override val removedEnvironmentVariables: Set[String],
                                   override val outputRedirection: OutputRedirection,
                                   override val runOutputStream: java.io.InputStream => ProxIO[O],
                                   override val errorRedirection: OutputRedirection,
                                   override val runErrorStream: java.io.InputStream => ProxIO[E],
                                   override val inputRedirection: InputRedirection)
      extends Process[O, E]
        with RedirectableInput[ProcessImplIOE[O, E]] {

      override type Self = ProcessImplOE[O, E]

      override def connectInput(source: InputRedirection): ProcessImplIOE[O, E] =
        ProcessImplIOE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplOE[O, E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    /** Process with bound output streams */
    case class ProcessImplO[O](override val command: String,
                               override val arguments: List[String],
                               override val workingDirectory: Option[Path],
                               override val environmentVariables: Map[String, String],
                               override val removedEnvironmentVariables: Set[String],
                               override val outputRedirection: OutputRedirection,
                               override val runOutputStream: java.io.InputStream => ProxIO[O],
                               override val errorRedirection: OutputRedirection,
                               override val runErrorStream: java.io.InputStream => ProxIO[Unit],
                               override val inputRedirection: InputRedirection)
      extends Process[O, Unit]
        with RedirectableError[ProcessImplOE[O, *]]
        with RedirectableInput[ProcessImplIO[O]] {

      override type Self = ProcessImplO[O]

      override def connectInput(source: InputRedirection): ProcessImplIO[O] =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override def connectError[R <: OutputRedirection, RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RE]): ProcessImplOE[O, RE] =
        ProcessImplOE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplO[O] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    /** Process with bound error streams */
    case class ProcessImplE[E](override val command: String,
                               override val arguments: List[String],
                               override val workingDirectory: Option[Path],
                               override val environmentVariables: Map[String, String],
                               override val removedEnvironmentVariables: Set[String],
                               override val outputRedirection: OutputRedirection,
                               override val runOutputStream: java.io.InputStream => ProxIO[Unit],
                               override val errorRedirection: OutputRedirection,
                               override val runErrorStream: java.io.InputStream => ProxIO[E],
                               override val inputRedirection: InputRedirection)
      extends Process[Unit, E]
        with RedirectableInput[ProcessImplIE[E]]
        with RedirectableOutput[ProcessImplOE[*, E]] {

      override type Self = ProcessImplE[E]

      override def connectInput(source: InputRedirection): ProcessImplIE[E] =
        ProcessImplIE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override def connectOutput[R <: OutputRedirection, RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RO]): ProcessImplOE[RO, E] =
        ProcessImplOE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplE[E] =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    /** Process with bound input streams */
    case class ProcessImplI(override val command: String,
                            override val arguments: List[String],
                            override val workingDirectory: Option[Path],
                            override val environmentVariables: Map[String, String],
                            override val removedEnvironmentVariables: Set[String],
                            override val outputRedirection: OutputRedirection,
                            override val runOutputStream: java.io.InputStream => ProxIO[Unit],
                            override val errorRedirection: OutputRedirection,
                            override val runErrorStream: java.io.InputStream => ProxIO[Unit],
                            override val inputRedirection: InputRedirection)
      extends Process[Unit, Unit]
        with RedirectableOutput[ProcessImplIO[*]]
        with RedirectableError[ProcessImplIE[*]] {

      override type Self = ProcessImplI

      def connectOutput[R <: OutputRedirection, RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RO]): ProcessImplIO[RO] =
        ProcessImplIO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override def connectError[R <: OutputRedirection, RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RE]): ProcessImplIE[RE] =
        ProcessImplIE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplI =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    /** Process with no streams bound */
    case class ProcessImpl(override val command: String,
                           override val arguments: List[String],
                           override val workingDirectory: Option[Path],
                           override val environmentVariables: Map[String, String],
                           override val removedEnvironmentVariables: Set[String],
                           override val outputRedirection: OutputRedirection,
                           override val runOutputStream: java.io.InputStream => ProxIO[Unit],
                           override val errorRedirection: OutputRedirection,
                           override val runErrorStream: java.io.InputStream => ProxIO[Unit],
                           override val inputRedirection: InputRedirection)
      extends Process[Unit, Unit]
        with RedirectableOutput[ProcessImplO[*]]
        with RedirectableError[ProcessImplE[*]]
        with RedirectableInput[ProcessImplI] {

      override type Self = ProcessImpl

      def connectOutput[R <: OutputRedirection, RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RO]): ProcessImplO[RO] =
        ProcessImplO(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          target,
          outputRedirectionType.runner(target),
          errorRedirection,
          runErrorStream,
          inputRedirection
        )

      override def connectError[R <: OutputRedirection, RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[R, RE]): ProcessImplE[RE] =
        ProcessImplE(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          target,
          outputRedirectionType.runner(target),
          inputRedirection
        )

      override def connectInput(source: InputRedirection): ProcessImplI =
        ProcessImplI(
          command,
          arguments,
          workingDirectory,
          environmentVariables,
          removedEnvironmentVariables,
          outputRedirection,
          runOutputStream,
          errorRedirection,
          runErrorStream,
          source
        )

      override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImpl =
        copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
    }

    /**
      * Defines a process to be executed
      *
      * The process by default uses the standard input, output and error streams.
      *
      * @param command   Command to be executed
      * @param arguments Arguments for the command
      * @return the process specification
      */
    def apply(command: String, arguments: List[String] = List.empty): ProcessImpl =
      ProcessImpl(
        command,
        arguments,
        workingDirectory = None,
        environmentVariables = Map.empty,
        removedEnvironmentVariables = Set.empty,

        outputRedirection = StdOut(),
        runOutputStream = _ => unit,
        errorRedirection = StdOut(),
        runErrorStream = _ => unit,
        inputRedirection = StdIn()
      )
  }

}