package io.github.vigoo.prox

import java.nio.file.Path

import cats.Applicative
import cats.effect._

trait ProcessResult[+O, +E] {
  val exitCode: ExitCode
  val output: O
  val error: E
}

case class SimpleProcessResult[+O, +E](override val exitCode: ExitCode,
                                       override val output: O,
                                       override val error: E)
  extends ProcessResult[O, E]

trait ProcessLike[F[_]]

trait RunningProcess[F[_], O, E] {
  val runningInput: Fiber[F, Unit]
  val runningOutput: Fiber[F, O]
  val runningError: Fiber[F, E]

  def isAlive: F[Boolean]
  def kill(): F[ProcessResult[O, E]]
  def terminate(): F[ProcessResult[O, E]]
  def waitForExit(): F[ProcessResult[O, E]]
}

trait Process[F[_], O, E] extends ProcessLike[F] {
  implicit val concurrent: Concurrent[F]

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

  def withCommand(newCommand: String): Process[F, O, E]
  def withArguments(newArguments: List[String]): Process[F, O, E]

  def startProcess(blocker: Blocker)(implicit runner: ProcessRunner[F]): F[RunningProcess[F, O, E]] =
    runner.startProcess(this, blocker)

  def start(blocker: Blocker)(implicit runner: ProcessRunner[F]): Resource[F, Fiber[F, ProcessResult[O, E]]] =
    runner.start(this, blocker)

  def run(blocker: Blocker)(implicit runner: ProcessRunner[F]): F[ProcessResult[O, E]] =
    start(blocker).use(_.join)
}

trait ProcessConfiguration[F[_], +P <: Process[F, _, _]] {
  this: Process[F, _, _] =>

  protected def selfCopy(command: String,
                         arguments: List[String],
                         workingDirectory: Option[Path],
                         environmentVariables: Map[String, String],
                         removedEnvironmentVariables: Set[String]): P

  def in(workingDirectory: Path): P =
    selfCopy(command, arguments, workingDirectory = Some(workingDirectory), environmentVariables, removedEnvironmentVariables)

  def `with`(nameValuePair: (String, String)): P =
    selfCopy(command, arguments, workingDirectory, environmentVariables = environmentVariables + nameValuePair, removedEnvironmentVariables)

  def without(name: String): P =
    selfCopy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables = removedEnvironmentVariables + name)

  def withCommand(newCommand: String): P =
    selfCopy(newCommand, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)

  def withArguments(newArguments: List[String]): P =
    selfCopy(command, newArguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
}

object Process {
  type UnboundIProcess[F[_], O, E] = Process[F, O, E] with RedirectableInput[F, Process[F, O, E]]
  type UnboundOProcess[F[_], E] = Process[F, Unit, E] with RedirectableOutput[F, Process[F, *, E]]
  type UnboundEProcess[F[_], O] = Process[F, O, Unit] with RedirectableError[F, Process[F, O, *]]

  type UnboundIOProcess[F[_], E] = Process[F, Unit, E]
    with RedirectableInput[F, UnboundOProcess[F, E]]
    with RedirectableOutput[F, UnboundIProcess[F, *, E]]

  type UnboundIEProcess[F[_], O] = Process[F, O, Unit]
    with RedirectableInput[F, UnboundEProcess[F, O]]
    with RedirectableError[F, UnboundIProcess[F, O, *]]

  type UnboundOEProcess[F[_]] = Process[F, Unit, Unit]
    with RedirectableOutput[F, UnboundEProcess[F, *]]
    with RedirectableError[F, UnboundOProcess[F, *]]

  type UnboundProcess[F[_]] = Process[F, Unit, Unit]
    with RedirectableInput[F, UnboundOEProcess[F]]
    with RedirectableOutput[F, UnboundIEProcess[F, *]]
    with RedirectableError[F, UnboundIOProcess[F, *]]

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
    extends Process[F, O, E] with ProcessConfiguration[F, ProcessImplIOE[F, O, E]] {

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIOE[F, O, E] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

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
      with RedirectableError[F, ProcessImplIOE[F, O, *]]
      with ProcessConfiguration[F, ProcessImplIO[F, O]] {

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
      with RedirectableOutput[F, ProcessImplIOE[F, *, E]]
      with ProcessConfiguration[F, ProcessImplIE[F, E]] {

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
      with RedirectableInput[F, ProcessImplIOE[F, O, E]]
      with ProcessConfiguration[F, ProcessImplOE[F, O, E]] {

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
      with RedirectableInput[F, ProcessImplIO[F, O]]
      with ProcessConfiguration[F, ProcessImplO[F, O]] {

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
      with RedirectableOutput[F, ProcessImplOE[F, *, E]]
      with ProcessConfiguration[F, ProcessImplE[F, E]] {

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
      with RedirectableError[F, ProcessImplIE[F, *]]
      with ProcessConfiguration[F, ProcessImplI[F]] {

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
      with RedirectableInput[F, ProcessImplI[F]]
      with ProcessConfiguration[F, ProcessImpl[F]] {

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