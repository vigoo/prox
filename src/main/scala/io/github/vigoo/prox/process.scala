package io.github.vigoo.prox

import java.nio.file.Path

import cats.Applicative
import cats.effect._

import scala.language.higherKinds

trait ProcessResult[+O, +E] {
  val exitCode: ExitCode
  val output: O
  val error: E
}

case class SimpleProcessResult[+O, +E](override val exitCode: ExitCode,
                                       override val output: O,
                                       override val error: E)
  extends ProcessResult[O, E]


trait Process[F[_], O, E] {
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
}

object Process {

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

  case class ProcessImplIO[F[_], O, E](override val command: String,
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
      with RedirectableError[F, ProcessImplIOE[F, O, *]]
      with ProcessConfiguration[F, ProcessImplIO[F, O, E]] {

    override def connectError[R <: OutputRedirection[F], RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RE]): ProcessImplIOE[F, O, RE] =
      ProcessImplIOE[F, O, RE](
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIO[F, O, E] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  case class ProcessImplIE[F[_], O, E](override val command: String,
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
      with RedirectableOutput[F, ProcessImplIOE[F, *, E]]
      with ProcessConfiguration[F, ProcessImplIE[F, O, E]] {

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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplIE[F, O, E] =
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

  case class ProcessImplO[F[_], O, E](override val command: String,
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
      with RedirectableError[F, ProcessImplOE[F, O, *]]
      with RedirectableInput[F, ProcessImplIO[F, O, E]]
      with ProcessConfiguration[F, ProcessImplO[F, O, E]] {

    override def connectInput(source: InputRedirection[F]): ProcessImplIO[F, O, E] =
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
      ProcessImplOE[F, O, RE](
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplO[F, O, E] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  case class ProcessImplE[F[_], O, E](override val command: String,
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
      with RedirectableInput[F, ProcessImplIO[F, O, E]]
      with RedirectableOutput[F, ProcessImplOE[F, *, E]]
      with ProcessConfiguration[F, ProcessImplE[F, O, E]] {

    override def connectInput(source: InputRedirection[F]): ProcessImplIO[F, O, E] =
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplE[F, O, E] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  case class ProcessImplI[F[_], O, E](override val command: String,
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
      with RedirectableOutput[F, ProcessImplIO[F, *, E]]
      with RedirectableError[F, ProcessImplIE[F, O, *]]
      with ProcessConfiguration[F, ProcessImplI[F, O, E]] {

    def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessImplIO[F, RO, E] =
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

    override def connectError[R <: OutputRedirection[F], RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RE]): ProcessImplIE[F, O, RE] =
      ProcessImplIE[F, O, RE](
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImplI[F, O, E] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  case class ProcessImpl[F[_], O, E](override val command: String,
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
      with RedirectableOutput[F, ProcessImplO[F, *, E]]
      with RedirectableError[F, ProcessImplE[F, O, *]]
      with RedirectableInput[F, ProcessImplI[F, O, E]]
      with ProcessConfiguration[F, ProcessImpl[F, O, E]] {

    def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessImplO[F, RO, E] =
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

    override def connectError[R <: OutputRedirection[F], RE](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RE]): ProcessImplE[F, O, RE] =
      ProcessImplE[F, O, RE](
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

    override def connectInput(source: InputRedirection[F]): ProcessImplI[F, O, E] =
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

    override protected def selfCopy(command: String, arguments: List[String], workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): ProcessImpl[F, O, E] =
      copy(command, arguments, workingDirectory, environmentVariables, removedEnvironmentVariables)
  }

  def apply[F[_] : Sync : Concurrent](command: String, arguments: List[String]): ProcessImpl[F, Unit, Unit] =
    ProcessImpl[F, Unit, Unit](
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