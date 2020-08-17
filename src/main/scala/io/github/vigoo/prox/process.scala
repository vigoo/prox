package io.github.vigoo.prox

import java.nio.file.Path

import cats.Applicative
import cats.effect._

/**
  * Result of a finished process
  * @tparam O Output type
  * @tparam E Error output type
  */
trait ProcessResult[+O, +E] {
  /** The exit code of the process */
  val exitCode: ExitCode

  /** Output value of the process, depends on what output redirection was applied */
  val output: O

  /** Error output value of the process, depends on what error redirection was applied */
  val error: E
}

/** Default implementation of [[ProcessResult]] */
case class SimpleProcessResult[+O, +E](override val exitCode: ExitCode,
                                       override val output: O,
                                       override val error: E)
  extends ProcessResult[O, E]

/**
  * Representation of a running process
  * @tparam F Effect type
  * @tparam O Output type
  * @tparam E Error output type
  * @tparam Info Runner-specific process information
  */
trait RunningProcess[F[_], O, E, +Info] {
  val runningInput: Fiber[F, Unit]
  val runningOutput: Fiber[F, O]
  val runningError: Fiber[F, E]

  /** Gets the runner-specific process information */
  val info: Info

  /** Checks whether the process is still running */
  def isAlive: F[Boolean]

  /** Forced termination of the process. Blocks until the process stops. */
  def kill(): F[ProcessResult[O, E]]

  /** Normal termination of the process. Blocks until the process stops. */
  def terminate(): F[ProcessResult[O, E]]

  /** Block until the process stops */
  def waitForExit(): F[ProcessResult[O, E]]

  def mapInfo[I2](f: Info => I2): RunningProcess[F, O, E, I2] =
    new RunningProcess[F, O, E, I2] {
      override val runningInput: Fiber[F, Unit] = RunningProcess.this.runningInput
      override val runningOutput: Fiber[F, O] = RunningProcess.this.runningOutput
      override val runningError: Fiber[F, E] = RunningProcess.this.runningError
      override val info: I2 = f(RunningProcess.this.info)

      override def isAlive: F[Boolean] = RunningProcess.this.isAlive
      override def kill(): F[ProcessResult[O, E]] = RunningProcess.this.kill()
      override def terminate(): F[ProcessResult[O, E]] = RunningProcess.this.terminate()
      override def waitForExit(): F[ProcessResult[O, E]] = RunningProcess.this.waitForExit()
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
  * @tparam F Effect type
  * @tparam O Output type
  * @tparam E Error output type
  */
trait Process[F[_], O, E] extends ProcessLike[F] with ProcessConfiguration[F] {
  implicit val concurrent: Concurrent[F]

  override type Self <: Process[F, O, E]

  val command: String
  val arguments: List[String]
  val workingDirectory: Option[Path]
  val environmentVariables: Map[String, String]
  val removedEnvironmentVariables: Set[String]

  val outputRedirection: OutputRedirection[F]
  val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O]
  val errorRedirection: OutputRedirection[F]
  val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E]
  val inputRedirection: InputRedirection[F]

  /**
    * Starts the process asynchronously and returns the [[RunningProcess]] interface for it
    *
    * This is the most advanced way to start processes. See [[start]] and [[run]] as alternatives.
    *
    * @param blocker Execution context for blocking operations
    * @param runner The process runner to be used
    *
    * @tparam Info The runner-specific process info type
    *
    * @return interface for handling the running process
    */
  def startProcess[Info](blocker: Blocker)(implicit runner: ProcessRunner[F, Info]): F[RunningProcess[F, O, E, Info]] =
    runner.startProcess(this, blocker)

  /**
    * Starts the process asynchronously and returns a closeable fiber representing it
    *
    * Joining the fiber waits for the process to be terminated. Canceling the fiber terminates
    * the process normally (with SIGTERM).
    *
    * @param blocker Execution context for blocking operations
    * @param runner The process runner to be used
    * @return a managed fiber representing the running process
    */
  def start[Info](blocker: Blocker)(implicit runner: ProcessRunner[F, Info]): Resource[F, Fiber[F, ProcessResult[O, E]]] =
    runner.start(this, blocker)

  /**
    * Starts the process asynchronously and blocks the execution until it is finished
    *
    * @param blocker Execution context for blocking operations
    * @param runner The process runner to be used
    * @return the result of the finished process
    */
  def run[Info](blocker: Blocker)(implicit runner: ProcessRunner[F, Info]): F[ProcessResult[O, E]] =
    start(blocker).use(_.join)
}

/**
  * The capability to configure process execution details
  * @tparam F Effect type
  */
trait ProcessConfiguration[F[_]] extends ProcessLikeConfiguration[F] {
  this: Process[F, _, _] =>

  override type Self <: ProcessConfiguration[F]

  override protected def applyConfiguration(workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): Self =
    selfCopy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)

  protected def selfCopy(command: String,
                         arguments: List[String],
                         workingDirectory: Option[Path],
                         environmentVariables: Map[String, String],
                         removedEnvironmentVariables: Set[String]): Self

  /**
    * Replaces the command
    * @param newCommand new value for the command to be executed
    * @return returns a new process specification
    */
  def withCommand(newCommand: String): Self =
    selfCopy(newCommand, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)

  /**
    * Replaces the arguments
    * @param newArguments new list of arguments
    * @return returns a new process specification
    */
  def withArguments(newArguments: List[String]): Self =
    selfCopy(command, newArguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
}

object Process {
  /** Process with unbound input stream */
  type UnboundIProcess[F[_], O, E] = Process[F, O, E] with RedirectableInput[F, Process[F, O, E]]

  /** Process with unbound output stream */
  type UnboundOProcess[F[_], E] = Process[F, Unit, E] with RedirectableOutput[F, Process[F, *, E]]

  /** Process with unbound error stream */
  type UnboundEProcess[F[_], O] = Process[F, O, Unit] with RedirectableError[F, Process[F, O, *]]

  /** Process with unbound input and output streams */
  type UnboundIOProcess[F[_], E] = Process[F, Unit, E]
    with RedirectableInput[F, UnboundOProcess[F, E]]
    with RedirectableOutput[F, UnboundIProcess[F, *, E]]

  /** Process with unbound input and error streams */
  type UnboundIEProcess[F[_], O] = Process[F, O, Unit]
    with RedirectableInput[F, UnboundEProcess[F, O]]
    with RedirectableError[F, UnboundIProcess[F, O, *]]

  /** Process with unbound output and error streams */
  type UnboundOEProcess[F[_]] = Process[F, Unit, Unit]
    with RedirectableOutput[F, UnboundEProcess[F, *]]
    with RedirectableError[F, UnboundOProcess[F, *]]

  /** Process with unbound input, output and error streams */
  type UnboundProcess[F[_]] = Process[F, Unit, Unit]
    with RedirectableInput[F, UnboundOEProcess[F]]
    with RedirectableOutput[F, UnboundIEProcess[F, *]]
    with RedirectableError[F, UnboundIOProcess[F, *]]

  /** Process with bound input, output and error streams */
  case class ProcessImplIOE[F[_], O, E](override val command: String,
                                        override val arguments: List[String],
                                        override val workingDirectory: Option[Path],
                                        override val environmentVariables: Map[String, String],
                                        override val removedEnvironmentVariables: Set[String],
                                        override val outputRedirection: OutputRedirection[F],
                                        override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                        override val errorRedirection: OutputRedirection[F],
                                        override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                        override val inputRedirection: InputRedirection[F])
                                       (override implicit val concurrent: Concurrent[F])
    extends Process[F, O, E] {

    override type Self = ProcessImplIOE[F, O, E]

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIOE[F, O, E] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  /** Process with bound input and output streams */
  case class ProcessImplIO[F[_], O](override val command: String,
                                       override val arguments: List[String],
                                       override val workingDirectory: Option[Path],
                                       override val environmentVariables: Map[String, String],
                                       override val removedEnvironmentVariables: Set[String],
                                       override val outputRedirection: OutputRedirection[F],
                                       override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                       override val errorRedirection: OutputRedirection[F],
                                       override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[Unit],
                                       override val inputRedirection: InputRedirection[F])
                                      (override implicit val concurrent: Concurrent[F])
    extends Process[F, O, Unit]
      with RedirectableError[F, ProcessImplIOE[F, O, *]] {

    override type Self = ProcessImplIO[F, O]

    override def connectError[R <: OutputRedirection[F], RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RE]): ProcessImplIOE[F, O, RE] =
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIO[F, O] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  /** Process with bound input and error streams */
  case class ProcessImplIE[F[_], E](override val command: String,
                                       override val arguments: List[String],
                                       override val workingDirectory: Option[Path],
                                       override val environmentVariables: Map[String, String],
                                       override val removedEnvironmentVariables: Set[String],
                                       override val outputRedirection: OutputRedirection[F],
                                       override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[Unit],
                                       override val errorRedirection: OutputRedirection[F],
                                       override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                       override val inputRedirection: InputRedirection[F])
                                      (override implicit val concurrent: Concurrent[F])
    extends Process[F, Unit, E]
      with RedirectableOutput[F, ProcessImplIOE[F, *, E]] {

    override type Self = ProcessImplIE[F, E]

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessImplIOE[F, RO, E] =
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIE[F, E] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  /** Process with bound output and error streams */
  case class ProcessImplOE[F[_], O, E](override val command: String,
                                       override val arguments: List[String],
                                       override val workingDirectory: Option[Path],
                                       override val environmentVariables: Map[String, String],
                                       override val removedEnvironmentVariables: Set[String],
                                       override val outputRedirection: OutputRedirection[F],
                                       override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                       override val errorRedirection: OutputRedirection[F],
                                       override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                       override val inputRedirection: InputRedirection[F])
                                      (override implicit val concurrent: Concurrent[F])
    extends Process[F, O, E]
      with RedirectableInput[F, ProcessImplIOE[F, O, E]] {

    override type Self = ProcessImplOE[F, O, E]

    override def connectInput(source: InputRedirection[F]): ProcessImplIOE[F, O, E] =
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplOE[F, O, E] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  /** Process with bound output streams */
  case class ProcessImplO[F[_], O](override val command: String,
                                      override val arguments: List[String],
                                      override val workingDirectory: Option[Path],
                                      override val environmentVariables: Map[String, String],
                                      override val removedEnvironmentVariables: Set[String],
                                      override val outputRedirection: OutputRedirection[F],
                                      override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[O],
                                      override val errorRedirection: OutputRedirection[F],
                                      override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[Unit],
                                      override val inputRedirection: InputRedirection[F])
                                     (override implicit val concurrent: Concurrent[F])
    extends Process[F, O, Unit]
      with RedirectableError[F, ProcessImplOE[F, O, *]]
      with RedirectableInput[F, ProcessImplIO[F, O]] {

    override type Self = ProcessImplO[F, O]

    override def connectInput(source: InputRedirection[F]): ProcessImplIO[F, O] =
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

    override def connectError[R <: OutputRedirection[F], RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RE]): ProcessImplOE[F, O, RE] =
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplO[F, O] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  /** Process with bound error streams */
  case class ProcessImplE[F[_], E](override val command: String,
                                      override val arguments: List[String],
                                      override val workingDirectory: Option[Path],
                                      override val environmentVariables: Map[String, String],
                                      override val removedEnvironmentVariables: Set[String],
                                      override val outputRedirection: OutputRedirection[F],
                                      override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[Unit],
                                      override val errorRedirection: OutputRedirection[F],
                                      override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[E],
                                      override val inputRedirection: InputRedirection[F])
                                     (override implicit val concurrent: Concurrent[F])
    extends Process[F, Unit, E]
      with RedirectableInput[F, ProcessImplIE[F, E]]
      with RedirectableOutput[F, ProcessImplOE[F, *, E]] {

    override type Self = ProcessImplE[F, E]

    override def connectInput(source: InputRedirection[F]): ProcessImplIE[F, E] =
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

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessImplOE[F, RO, E] =
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplE[F, E] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  /** Process with bound input streams */
  case class ProcessImplI[F[_]](override val command: String,
                                      override val arguments: List[String],
                                      override val workingDirectory: Option[Path],
                                      override val environmentVariables: Map[String, String],
                                      override val removedEnvironmentVariables: Set[String],
                                      override val outputRedirection: OutputRedirection[F],
                                      override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[Unit],
                                      override val errorRedirection: OutputRedirection[F],
                                      override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[Unit],
                                      override val inputRedirection: InputRedirection[F])
                                     (override implicit val concurrent: Concurrent[F])
    extends Process[F, Unit, Unit]
      with RedirectableOutput[F, ProcessImplIO[F, *]]
      with RedirectableError[F, ProcessImplIE[F, *]] {

    override type Self = ProcessImplI[F]

    def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessImplIO[F, RO] =
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

    override def connectError[R <: OutputRedirection[F], RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RE]): ProcessImplIE[F, RE] =
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplI[F] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  /** Process with no streams bound */
  case class ProcessImpl[F[_]](override val command: String,
                                     override val arguments: List[String],
                                     override val workingDirectory: Option[Path],
                                     override val environmentVariables: Map[String, String],
                                     override val removedEnvironmentVariables: Set[String],
                                     override val outputRedirection: OutputRedirection[F],
                                     override val runOutputStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[Unit],
                                     override val errorRedirection: OutputRedirection[F],
                                     override val runErrorStream: (java.io.InputStream, Blocker, ContextShift[F]) => F[Unit],
                                     override val inputRedirection: InputRedirection[F])
                                    (override implicit val concurrent: Concurrent[F])
    extends Process[F, Unit, Unit]
      with RedirectableOutput[F, ProcessImplO[F, *]]
      with RedirectableError[F, ProcessImplE[F, *]]
      with RedirectableInput[F, ProcessImplI[F]] {

    override type Self = ProcessImpl[F]

    def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessImplO[F, RO] =
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

    override def connectError[R <: OutputRedirection[F], RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RE]): ProcessImplE[F, RE] =
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

    override def connectInput(source: InputRedirection[F]): ProcessImplI[F] =
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImpl[F] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  /**
    * Defines a process to be executed
    *
    * The process by default uses the standard input, output and error streams.
    *
    * @param command Command to be executed
    * @param arguments Arguments for the command
    * @tparam F Effect type
    * @return the process specification
    */
  def apply[F[_] : Sync : Concurrent](command: String, arguments: List[String] = List.empty): ProcessImpl[F] =
    ProcessImpl[F](
      command,
      arguments,
      workingDirectory = None,
      environmentVariables = Map.empty,
      removedEnvironmentVariables = Set.empty,

      outputRedirection = StdOut[F](),
      runOutputStream = (_, _, _) => Applicative[F].unit,
      errorRedirection = StdOut[F](),
      runErrorStream = (_, _, _) => Applicative[F].unit,
      inputRedirection = StdIn[F]()
    )
}