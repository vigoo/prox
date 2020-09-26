package io.github.vigoo.prox

import java.nio.file.Path

import cats.{Applicative, Monad, Traverse}
import cats.effect._
import cats.instances.list._
import cats.syntax.functor._
import cats.syntax.flatMap._
import fs2._
import _root_.io.github.vigoo.prox.syntax._

/**
  * Result of an executed process group
  * @tparam F Effect type
  * @tparam O Output type
  * @tparam E Error output type
  */
trait ProcessGroupResult[F[_], +O, +E] {
  /** Per-process exit codes. The key is the original process passed to the piping operator */
  val exitCodes: Map[Process[F, Unit, Unit], ExitCode]

  /** Output of the last process in the group */
  val output: O

  /** Per-process error outputs. The key is the original process passed to the piping operator */
  val errors: Map[Process[F, Unit, Unit], E]
}

/** Default implementation of [[ProcessGroupResult]] */
case class SimpleProcessGroupResult[F[_], +O, +E](override val exitCodes: Map[Process[F, Unit, Unit], ExitCode],
                                                  override val output: O,
                                                  override val errors: Map[Process[F, Unit, Unit], E])
  extends ProcessGroupResult[F, O, E]

/**
  * Representation of a running process group
  * @tparam F Effect type
  * @tparam O Output type
  * @tparam E Error output type
  * @tparam Info Runner-specific per-process information type
  */
trait RunningProcessGroup[F[_], O, E, +Info] {
  val runningOutput: Fiber[F, O]

  /** Runner-specific information about each running process */
  val info: Map[Process[F, Unit, Unit], Info]

  /** Forcibly terminates all processes in the group. Blocks until it is done. */
  def kill(): F[ProcessGroupResult[F, O, E]]

  /** Terminates all processes in the group. Blocks until it is done. */
  def terminate(): F[ProcessGroupResult[F, O, E]]

  /** Blocks until the processes finish running */
  def waitForExit(): F[ProcessGroupResult[F, O, E]]

  def mapInfo[I2](f: (Process[F, Unit, Unit], Info) => I2): RunningProcessGroup[F, O, E, I2] =
    new RunningProcessGroup[F, O, E, I2] {
      override val runningOutput: Fiber[F, O] = RunningProcessGroup.this.runningOutput
      override val info: Map[Process[F, Unit, Unit], I2] = RunningProcessGroup.this.info.map { case (key, value) =>
        key -> f(key, value)
      }

      override def kill(): F[ProcessGroupResult[F, O, E]] = RunningProcessGroup.this.kill()
      override def terminate(): F[ProcessGroupResult[F, O, E]] = RunningProcessGroup.this.terminate()
      override def waitForExit(): F[ProcessGroupResult[F, O, E]] = RunningProcessGroup.this.waitForExit()
    }
}

/**
  * Process group is two or more processes attached to each other
  *
  * This implements a pipeline of processes. The input of the first process and the output of the last
  * process is redirectable with the [[RedirectableInput]] and [[RedirectableOutput]] traits. The processes
  * are attached to each other's input/output streams, the pipe between them is customizable.
  *
  * The error streams are also redirectable with the [[RedirectableErrors]] trait.
  *
  * @tparam F Effect type
  * @tparam O Output type
  * @tparam E Error output type
  */
trait ProcessGroup[F[_], O, E] extends ProcessLike[F] with ProcessGroupConfiguration[F, O, E] {
  implicit val concurrent: Concurrent[F]

  val firstProcess: Process[F, Stream[F, Byte], E]
  val innerProcesses: List[Process.UnboundIProcess[F, Stream[F, Byte], E]]
  val lastProcess: Process.UnboundIProcess[F, O, E]

  val originalProcesses: List[Process[F, Unit, Unit]]

  /**
    * Starts the process group asynchronously and returns the [[RunningProcessGroup]] interface for it
    *
    * This is the most advanced way to start process groups. See [[start]] and [[run]] as alternatives.
    *
    * @param blocker Execution context for blocking operations
    * @param runner The process runner to be used
    *
    * @tparam Info The runner-specific information about the started processes
    *
    * @return interface for handling the running process group
    */
  def startProcessGroup[Info](blocker: Blocker)(implicit runner: ProcessRunner[F, Info]): F[RunningProcessGroup[F, O, E, Info]] =
    runner.startProcessGroup(this, blocker)

  /**
    * Starts the process group asynchronously and returns a closeable fiber representing it
    *
    * Joining the fiber waits for the processes to be terminated. Canceling the fiber terminates
    * the processesnormally (with SIGTERM).
    *
    * @param blocker Execution context for blocking operations
    * @param runner The process runner to be used
    * @return a managed fiber representing the running processes
    */
  def start[Info](blocker: Blocker)(implicit runner: ProcessRunner[F, Info]): Resource[F, Fiber[F, ProcessGroupResult[F, O, E]]] =
    runner.start(this, blocker)

  /**
    * Starts the process group asynchronously and blocks the execution until it is finished
    *
    * @param blocker Execution context for blocking operations
    * @param runner The process runner to be used
    * @return the result of the finished processes
    */
  def run[Info](blocker: Blocker)(implicit runner: ProcessRunner[F, Info]): F[ProcessGroupResult[F, O, E]] =
    start(blocker).use(_.join)

  /**
    * Applies the given mapper to each process in the group
    * @param f process mapper
    * @return a new process group with all the processes altered by the mapper
    */
  def map(f: ProcessGroup.Mapper[F, O, E]): Self
}

trait ProcessGroupConfiguration[F[_], O, E] extends ProcessLikeConfiguration[F] {
  this: ProcessGroup[F, O, E] =>

  override type Self <: ProcessGroup[F, O, E]

  private val allProcesses = (firstProcess :: innerProcesses) :+ lastProcess

  override val workingDirectory: Option[Path] = {
    val allWorkingDirectories = allProcesses.map(_.workingDirectory).toSet
    if (allWorkingDirectories.size == 1) {
      allWorkingDirectories.head
    } else {
      None
    }
  }

  override val environmentVariables: Map[String, String] = {
    allProcesses.map(_.environmentVariables.toSet).reduce(_ intersect _).toMap
  }

  override val removedEnvironmentVariables: Set[String] = {
    allProcesses.map(_.removedEnvironmentVariables).reduce(_ intersect _)
  }

  override protected def applyConfiguration(workingDirectory: Option[Path], environmentVariables: Map[String, String], removedEnvironmentVariables: Set[String]): Self =
    map(new ProcessGroup.Mapper[F, O, E] {
      override def mapFirst[P <: Process[F, Stream[F, Byte], E]](process: P): P =
        ConfigApplication[P](process, workingDirectory, environmentVariables, removedEnvironmentVariables)

      override def mapInnerWithIdx[P <: Process.UnboundIProcess[F, Stream[F, Byte], E]](process: P, idx: Int): P =
        ConfigApplication[P](process, workingDirectory, environmentVariables, removedEnvironmentVariables)

      override def mapLast[P <: Process.UnboundIProcess[F, O, E]](process: P): P =
        ConfigApplication[P](process, workingDirectory, environmentVariables, removedEnvironmentVariables)
    })

  class ConfigApplication[P <: ProcessLikeConfiguration[F]] {
    // NOTE: Unfortunately we have no proof that P#Self == P so we cast

    private def applyWorkingDirectory(workingDirectory: Option[Path])(process: P): P =
      workingDirectory match {
        case Some(path) => (process in path).asInstanceOf[P]
        case None => process.inInheritedWorkingDirectory().asInstanceOf[P]
      }

    private def addEnvironmentVariables(environmentVariables: Seq[(String, String)])(process: P): P =
      environmentVariables.foldLeft(process) { case (proc, pair) => (proc `with` pair).asInstanceOf[P] }

    private def removeEnvironmentVariables(environmentVariables: Seq[String])(process: P): P  =
      environmentVariables.foldLeft(process) { case (proc, name) => (proc without name).asInstanceOf[P] }

    def apply(process: P,
              workingDirectory: Option[Path],
              environmentVariables: Map[String, String],
              removedEnvironmentVariables: Set[String]): P =
      (applyWorkingDirectory(workingDirectory) _  compose
      addEnvironmentVariables(environmentVariables.toSeq) compose
        removeEnvironmentVariables(removedEnvironmentVariables.toSeq))(process)
  }

  object ConfigApplication {
    def apply[P <: ProcessLikeConfiguration[F]]: ConfigApplication[P] = new ConfigApplication[P]
  }
}

object ProcessGroup {
  /** Mapper functions for altering a process group */
  trait Mapper[F[_], O, E] {
    def mapFirst[P <: Process[F, Stream[F, Byte], E]](process: P): P
    def mapInnerWithIdx[P <: Process.UnboundIProcess[F, Stream[F, Byte], E]](process: P, idx: Int): P
    def mapLast[P <: Process.UnboundIProcess[F, O, E]](process: P): P
  }

  /** Process group with bound input, output and error streams */
  case class ProcessGroupImplIOE[F[_], O, E](override val firstProcess: Process[F, Stream[F, Byte], E],
                                             override val innerProcesses: List[Process.UnboundIProcess[F, Stream[F, Byte], E]],
                                             override val lastProcess: Process.UnboundIProcess[F, O, E],
                                             override val originalProcesses: List[Process[F, Unit, Unit]])
                                            (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O, E] {

    override type Self = ProcessGroupImplIOE[F, O, E]

    def map(f: ProcessGroup.Mapper[F, O, E]): ProcessGroupImplIOE[F, O, E] = {
      copy(
        firstProcess = f.mapFirst(this.firstProcess),
        innerProcesses = this.innerProcesses.zipWithIndex.map { case (p, idx) => f.mapInnerWithIdx(p, idx + 1) },
        lastProcess = f.mapLast(this.lastProcess),
        originalProcesses
      )
    }
  }

  /** Process group with bound input and output streams */
  case class ProcessGroupImplIO[F[_], O](override val firstProcess: Process.UnboundEProcess[F, Stream[F, Byte]],
                                         override val innerProcesses: List[Process.UnboundIEProcess[F, Stream[F, Byte]]],
                                         override val lastProcess: Process.UnboundIEProcess[F, O],
                                         override val originalProcesses: List[Process[F, Unit, Unit]])
                                        (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O, Unit]
      with RedirectableErrors[F, ProcessGroupImplIOE[F, O, *]]{

    override type Self = ProcessGroupImplIO[F, O]

    def map(f: ProcessGroup.Mapper[F, O, Unit]): ProcessGroupImplIO[F, O] = {
      copy(
        firstProcess = f.mapFirst(this.firstProcess),
        innerProcesses = this.innerProcesses.zipWithIndex.map { case (p, idx) => f.mapInnerWithIdx(p, idx + 1) },
        lastProcess = f.mapLast(this.lastProcess),
        originalProcesses
      )
    }

    override def connectErrors[R <: GroupErrorRedirection[F], OR <: OutputRedirection[F], E](target: R)
                                                                                            (implicit groupErrorRedirectionType: GroupErrorRedirectionType.Aux[F, R, OR, E],
                                                                                             outputRedirectionType: OutputRedirectionType.Aux[F, OR, E]): ProcessGroupImplIOE[F, O, E] = {
      val origs = originalProcesses.reverse.toVector
      ProcessGroupImplIOE(
        firstProcess.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs.head)),
        innerProcesses.zipWithIndex.map { case (p, idx) => p.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs(idx + 1))) },
        lastProcess.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs.last)),
        originalProcesses
      )
    }
  }

  /** Process group with bound input and error streams */
  case class ProcessGroupImplIE[F[_], E](override val firstProcess: Process[F, Stream[F, Byte], E],
                                         override val innerProcesses: List[Process.UnboundIProcess[F, Stream[F, Byte], E]],
                                         override val lastProcess: Process.UnboundIOProcess[F, E],
                                         override val originalProcesses: List[Process[F, Unit, Unit]])
                                        (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, Unit, E]
      with RedirectableOutput[F, ProcessGroupImplIOE[F, *, E]] {

    override type Self = ProcessGroupImplIE[F, E]

    def map(f: ProcessGroup.Mapper[F, Unit, E]): ProcessGroupImplIE[F, E] = {
      copy(
        firstProcess = f.mapFirst(this.firstProcess),
        innerProcesses = this.innerProcesses.zipWithIndex.map { case (p, idx) => f.mapInnerWithIdx(p, idx + 1) },
        lastProcess = f.mapLast(this.lastProcess),
        originalProcesses
      )
    }

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessGroupImplIOE[F, RO, E] = {
      ProcessGroupImplIOE(
        firstProcess,
        innerProcesses,
        lastProcess.connectOutput(target),
        originalProcesses
      )
    }
  }

  /** Process group with bound output and error streams */
  case class ProcessGroupImplOE[F[_], O, E](override val firstProcess: Process.UnboundIProcess[F, Stream[F, Byte], E],
                                            override val innerProcesses: List[Process.UnboundIProcess[F, Stream[F, Byte], E]],
                                            override val lastProcess: Process.UnboundIProcess[F, O, E],
                                            override val originalProcesses: List[Process[F, Unit, Unit]])
                                           (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O, E]
      with RedirectableInput[F, ProcessGroupImplIOE[F, O, E]] {

    override type Self = ProcessGroupImplOE[F, O, E]

    def map(f: ProcessGroup.Mapper[F, O, E]): ProcessGroupImplOE[F, O, E] = {
      copy(
        firstProcess = f.mapFirst(this.firstProcess),
        innerProcesses = this.innerProcesses.zipWithIndex.map { case (p, idx) => f.mapInnerWithIdx(p, idx + 1) },
        lastProcess = f.mapLast(this.lastProcess),
        originalProcesses
      )
    }

    override def connectInput(source: InputRedirection[F]): ProcessGroupImplIOE[F, O, E] = {
      ProcessGroupImplIOE(
        firstProcess.connectInput(source),
        innerProcesses,
        lastProcess,
        originalProcesses
      )
    }
  }

  /** Process group with bound input stream */
  case class ProcessGroupImplI[F[_]](override val firstProcess: Process.UnboundEProcess[F, Stream[F, Byte]],
                                     override val innerProcesses: List[Process.UnboundIEProcess[F, Stream[F, Byte]]],
                                     override val lastProcess: Process.UnboundProcess[F],
                                     override val originalProcesses: List[Process[F, Unit, Unit]])
                                    (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, Unit, Unit]
      with RedirectableOutput[F, ProcessGroupImplIO[F, *]]
      with RedirectableErrors[F, ProcessGroupImplIE[F, *]] {

    override type Self = ProcessGroupImplI[F]

    def map(f: ProcessGroup.Mapper[F, Unit, Unit]): ProcessGroupImplI[F] = {
      copy(
        firstProcess = f.mapFirst(this.firstProcess),
        innerProcesses = this.innerProcesses.zipWithIndex.map { case (p, idx) => f.mapInnerWithIdx(p, idx + 1) },
        lastProcess = f.mapLast(this.lastProcess),
        originalProcesses
      )
    }

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessGroupImplIO[F, RO] = {
      ProcessGroupImplIO(
        firstProcess,
        innerProcesses,
        lastProcess.connectOutput(target),
        originalProcesses
      )
    }

    override def connectErrors[R <: GroupErrorRedirection[F], OR <: OutputRedirection[F], E](target: R)
                                                                                            (implicit groupErrorRedirectionType: GroupErrorRedirectionType.Aux[F, R, OR, E],
                                                                                             outputRedirectionType: OutputRedirectionType.Aux[F, OR, E]): ProcessGroupImplIE[F, E] = {
      val origs = originalProcesses.reverse.toVector
      ProcessGroupImplIE(
        firstProcess.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs.head)),
        innerProcesses.zipWithIndex.map { case (p, idx) => p.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs(idx + 1))) },
        lastProcess.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs.last)),
        originalProcesses
      )
    }
  }

  /** Process group with bound output stream */
  case class ProcessGroupImplO[F[_], O](override val firstProcess: Process.UnboundIEProcess[F, Stream[F, Byte]],
                                        override val innerProcesses: List[Process.UnboundIEProcess[F, Stream[F, Byte]]],
                                        override val lastProcess: Process.UnboundIEProcess[F, O],
                                        override val originalProcesses: List[Process[F, Unit, Unit]])
                                       (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, O, Unit]
      with RedirectableInput[F, ProcessGroupImplIO[F, O]]
      with RedirectableErrors[F, ProcessGroupImplOE[F, O, *]] {

    override type Self = ProcessGroupImplO[F, O]

    def map(f: ProcessGroup.Mapper[F, O, Unit]): ProcessGroupImplO[F, O] = {
      copy(
        firstProcess = f.mapFirst(this.firstProcess),
        innerProcesses = this.innerProcesses.zipWithIndex.map { case (p, idx) => f.mapInnerWithIdx(p, idx + 1) },
        lastProcess = f.mapLast(this.lastProcess),
        originalProcesses
      )
    }

    override def connectInput(source: InputRedirection[F]): ProcessGroupImplIO[F, O] = {
      ProcessGroupImplIO(
        firstProcess.connectInput(source),
        innerProcesses,
        lastProcess,
        originalProcesses
      )
    }

    override def connectErrors[R <: GroupErrorRedirection[F], OR <: OutputRedirection[F], E](target: R)
                                                                                            (implicit groupErrorRedirectionType: GroupErrorRedirectionType.Aux[F, R, OR, E],
                                                                                             outputRedirectionType: OutputRedirectionType.Aux[F, OR, E]): ProcessGroupImplOE[F, O, E] = {
      val origs = originalProcesses.reverse.toVector
      ProcessGroupImplOE(
        firstProcess.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs.head)),
        innerProcesses.zipWithIndex.map { case (p, idx) => p.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs(idx + 1))) },
        lastProcess.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs.last)),
        originalProcesses
      )
    }
  }

  /** Process group with bound error stream */
  case class ProcessGroupImplE[F[_], E](override val firstProcess: Process.UnboundIProcess[F, Stream[F, Byte], E],
                                        override val innerProcesses: List[Process.UnboundIProcess[F, Stream[F, Byte], E]],
                                        override val lastProcess: Process.UnboundIOProcess[F, E],
                                        override val originalProcesses: List[Process[F, Unit, Unit]])
                                       (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, Unit, E]
      with RedirectableOutput[F, ProcessGroupImplOE[F, *, E]]
      with RedirectableInput[F, ProcessGroupImplIE[F, E]] {

    override type Self = ProcessGroupImplE[F, E]

    def map(f: ProcessGroup.Mapper[F, Unit, E]): ProcessGroupImplE[F, E] = {
      copy(
        firstProcess = f.mapFirst(this.firstProcess),
        innerProcesses = this.innerProcesses.zipWithIndex.map { case (p, idx) => f.mapInnerWithIdx(p, idx + 1) },
        lastProcess = f.mapLast(this.lastProcess),
        originalProcesses
      )
    }

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessGroupImplOE[F, RO, E] = {
      ProcessGroupImplOE(
        firstProcess,
        innerProcesses,
        lastProcess.connectOutput(target),
        originalProcesses
      )
    }

    override def connectInput(source: InputRedirection[F]): ProcessGroupImplIE[F, E] = {
      ProcessGroupImplIE(
        firstProcess.connectInput(source),
        innerProcesses,
        lastProcess,
        originalProcesses
      )
    }
  }

  /** Process group with unbound input, output and error streams */
  case class ProcessGroupImpl[F[_]](override val firstProcess: Process.UnboundIEProcess[F, Stream[F, Byte]],
                                    override val innerProcesses: List[Process.UnboundIEProcess[F, Stream[F, Byte]]],
                                    override val lastProcess: Process.UnboundProcess[F],
                                    override val originalProcesses: List[Process[F, Unit, Unit]])
                                   (implicit override val concurrent: Concurrent[F])
    extends ProcessGroup[F, Unit, Unit]
      with RedirectableOutput[F, ProcessGroupImplO[F, *]]
      with RedirectableInput[F, ProcessGroupImplI[F]]
      with RedirectableErrors[F, ProcessGroupImplE[F, *]] {

    override type Self = ProcessGroupImpl[F]

    def pipeInto(other: Process.UnboundProcess[F],
                 channel: Pipe[F, Byte, Byte]): ProcessGroupImpl[F] = {
      val pl1 = lastProcess.connectOutput(OutputStream(channel, (stream: Stream[F, Byte]) => Applicative[F].pure(stream)))

      copy(
        innerProcesses = pl1 :: innerProcesses,
        lastProcess = other,
        originalProcesses = other :: originalProcesses
      )
    }

    def |(other: Process.UnboundProcess[F]): ProcessGroupImpl[F] =
      pipeInto(other, identity)


    def via(channel: Pipe[F, Byte, Byte]): PipeBuilderSyntax[F, ProcessGroupImpl[F]] =
      new PipeBuilderSyntax(new PipeBuilder[F, ProcessGroupImpl[F]] {
        override def build(other: Process.UnboundProcess[F], channel: Pipe[F, Byte, Byte]): ProcessGroupImpl[F] =
          ProcessGroupImpl.this.pipeInto(other, channel)
      }, channel)

    def map(f: ProcessGroup.Mapper[F, Unit, Unit]): ProcessGroupImpl[F] = {
      copy(
        firstProcess = f.mapFirst(this.firstProcess),
        innerProcesses = this.innerProcesses.zipWithIndex.map { case (p, idx) => f.mapInnerWithIdx(p, idx + 1) },
        lastProcess = f.mapLast(this.lastProcess),
        originalProcesses
      )
    }

    override def connectInput(source: InputRedirection[F]): ProcessGroupImplI[F] =
      ProcessGroupImplI(
        firstProcess.connectInput(source),
        innerProcesses,
        lastProcess,
        originalProcesses
      )

    override def connectErrors[R <: GroupErrorRedirection[F], OR <: OutputRedirection[F], E](target: R)
                                                                                            (implicit groupErrorRedirectionType: GroupErrorRedirectionType.Aux[F, R, OR, E],
                                                                                             outputRedirectionType: OutputRedirectionType.Aux[F, OR, E]): ProcessGroupImplE[F, E] = {
      val origs = originalProcesses.reverse.toVector
      ProcessGroupImplE(
        firstProcess.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs.head)),
        innerProcesses.zipWithIndex.map { case (p, idx) => p.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs(idx + 1))) },
        lastProcess.connectError(groupErrorRedirectionType.toOutputRedirectionType(target, origs.last)),
        originalProcesses
      )
    }

    override def connectOutput[R <: OutputRedirection[F], RO](target: R)(implicit outputRedirectionType: OutputRedirectionType.Aux[F, R, RO]): ProcessGroupImplO[F, RO] = {
      ProcessGroupImplO(
        firstProcess,
        innerProcesses,
        lastProcess.connectOutput(target),
        originalProcesses
      )
    }
  }

}
