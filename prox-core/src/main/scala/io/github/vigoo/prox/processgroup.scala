package io.github.vigoo.prox

import java.nio.file.Path

trait ProcessGroupModule {
  this: Prox =>

  /** Result of an executed process group
    *
    * @tparam O
    *   Output type
    * @tparam E
    *   Error output type
    */
  trait ProcessGroupResult[+O, +E] {

    /** Per-process exit codes. The key is the original process passed to the
      * piping operator
      */
    val exitCodes: Map[Process[Unit, Unit], ProxExitCode]

    /** Output of the last process in the group */
    val output: O

    /** Per-process error outputs. The key is the original process passed to the
      * piping operator
      */
    val errors: Map[Process[Unit, Unit], E]
  }

  /** Default implementation of [[ProcessGroupResult]] */
  case class SimpleProcessGroupResult[+O, +E](
      override val exitCodes: Map[Process[Unit, Unit], ProxExitCode],
      override val output: O,
      override val errors: Map[Process[Unit, Unit], E]
  ) extends ProcessGroupResult[O, E]

  /** Representation of a running process group
    *
    * @tparam O
    *   Output type
    * @tparam E
    *   Error output type
    * @tparam Info
    *   Runner-specific per-process information type
    */
  trait RunningProcessGroup[O, E, +Info] {
    val runningOutput: ProxFiber[O]

    /** Runner-specific information about each running process */
    val info: Map[Process[Unit, Unit], Info]

    /** Forcibly terminates all processes in the group. Blocks until it is done.
      */
    def kill(): ProxIO[ProcessGroupResult[O, E]]

    /** Terminates all processes in the group. Blocks until it is done. */
    def terminate(): ProxIO[ProcessGroupResult[O, E]]

    /** Blocks until the processes finish running */
    def waitForExit(): ProxIO[ProcessGroupResult[O, E]]

    def mapInfo[I2](
        f: (Process[Unit, Unit], Info) => I2
    ): RunningProcessGroup[O, E, I2] =
      new RunningProcessGroup[O, E, I2] {
        override val runningOutput: ProxFiber[O] =
          RunningProcessGroup.this.runningOutput
        override val info: Map[Process[Unit, Unit], I2] =
          RunningProcessGroup.this.info.map { case (key, value) =>
            key -> f(key, value)
          }

        override def kill(): ProxIO[ProcessGroupResult[O, E]] =
          RunningProcessGroup.this.kill()

        override def terminate(): ProxIO[ProcessGroupResult[O, E]] =
          RunningProcessGroup.this.terminate()

        override def waitForExit(): ProxIO[ProcessGroupResult[O, E]] =
          RunningProcessGroup.this.waitForExit()
      }
  }

  /** Process group is two or more processes attached to each other
    *
    * This implements a pipeline of processes. The input of the first process
    * and the output of the last process is redirectable with the
    * [[RedirectableInput]] and [[RedirectableOutput]] traits. The processes are
    * attached to each other's input/output streams, the pipe between them is
    * customizable.
    *
    * The error streams are also redirectable with the [[RedirectableErrors]]
    * trait.
    *
    * @tparam O
    *   Output type
    * @tparam E
    *   Error output type
    */
  trait ProcessGroup[O, E]
      extends ProcessLike
      with ProcessGroupConfiguration[O, E] {
    val firstProcess: Process[ProxStream[Byte], E]
    val innerProcesses: List[Process.UnboundIProcess[ProxStream[Byte], E]]
    val lastProcess: Process.UnboundIProcess[O, E]

    val originalProcesses: List[Process[Unit, Unit]]

    /** Starts the process group asynchronously and returns the
      * [[RunningProcessGroup]] interface for it
      *
      * This is the most advanced way to start process groups. See [[start]] and
      * [[run]] as alternatives.
      *
      * @param runner
      *   The process runner to be used
      * @tparam Info
      *   The runner-specific information about the started processes
      * @return
      *   interface for handling the running process group
      */
    def startProcessGroup[Info]()(implicit
        runner: ProcessRunner[Info]
    ): ProxIO[RunningProcessGroup[O, E, Info]] =
      runner.startProcessGroup(this)

    /** Starts the process group asynchronously and returns a closeable fiber
      * representing it
      *
      * Joining the fiber waits for the processes to be terminated. Canceling
      * the fiber terminates the processesnormally (with SIGTERM).
      *
      * @param runner
      *   The process runner to be used
      * @return
      *   a managed fiber representing the running processes
      */
    def start[Info]()(implicit
        runner: ProcessRunner[Info]
    ): ProxResource[ProxFiber[ProcessGroupResult[O, E]]] =
      runner.start(this)

    /** Starts the process group asynchronously and blocks the execution until
      * it is finished
      *
      * @param runner
      *   The process runner to be used
      * @return
      *   the result of the finished processes
      */
    def run[Info]()(implicit
        runner: ProcessRunner[Info]
    ): ProxIO[ProcessGroupResult[O, E]] =
      start().use(_.join)

    /** Applies the given mapper to each process in the group
      *
      * @param f
      *   process mapper
      * @return
      *   a new process group with all the processes altered by the mapper
      */
    def map(f: ProcessGroup.Mapper[O, E]): Self
  }

  trait ProcessGroupConfiguration[O, E] extends ProcessLikeConfiguration {
    this: ProcessGroup[O, E] =>

    override type Self <: ProcessGroup[O, E]

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

    override protected def applyConfiguration(
        workingDirectory: Option[Path],
        environmentVariables: Map[String, String],
        removedEnvironmentVariables: Set[String]
    ): Self =
      map(new ProcessGroup.Mapper[O, E] {
        override def mapFirst[P <: Process[ProxStream[Byte], E]](
            process: P
        ): P =
          ConfigApplication[P](
            process,
            workingDirectory,
            environmentVariables,
            removedEnvironmentVariables
          )

        override def mapInnerWithIdx[
            P <: Process.UnboundIProcess[ProxStream[Byte], E]
        ](process: P, idx: Int): P =
          ConfigApplication[P](
            process,
            workingDirectory,
            environmentVariables,
            removedEnvironmentVariables
          )

        override def mapLast[P <: Process.UnboundIProcess[O, E]](
            process: P
        ): P =
          ConfigApplication[P](
            process,
            workingDirectory,
            environmentVariables,
            removedEnvironmentVariables
          )
      })

    class ConfigApplication[P <: ProcessLikeConfiguration] {
      // NOTE: Unfortunately we have no proof that P#Self == P so we cast

      private def applyWorkingDirectory(
          workingDirectory: Option[Path]
      )(process: P): P =
        workingDirectory match {
          case Some(path) => (process in path).asInstanceOf[P]
          case None => process.inInheritedWorkingDirectory().asInstanceOf[P]
        }

      private def addEnvironmentVariables(
          environmentVariables: Seq[(String, String)]
      )(process: P): P =
        environmentVariables.foldLeft(process) { case (proc, pair) =>
          (proc `with` pair).asInstanceOf[P]
        }

      private def removeEnvironmentVariables(
          environmentVariables: Seq[String]
      )(process: P): P =
        environmentVariables.foldLeft(process) { case (proc, name) =>
          (proc without name).asInstanceOf[P]
        }

      def apply(
          process: P,
          workingDirectory: Option[Path],
          environmentVariables: Map[String, String],
          removedEnvironmentVariables: Set[String]
      ): P =
        (applyWorkingDirectory(workingDirectory) _ compose
          addEnvironmentVariables(environmentVariables.toSeq) compose
          removeEnvironmentVariables(removedEnvironmentVariables.toSeq))(
          process
        )
    }

    object ConfigApplication {
      def apply[P <: ProcessLikeConfiguration]: ConfigApplication[P] =
        new ConfigApplication[P]
    }

  }

  object ProcessGroup {

    /** Mapper functions for altering a process group */
    trait Mapper[O, E] {
      def mapFirst[P <: Process[ProxStream[Byte], E]](process: P): P

      def mapInnerWithIdx[P <: Process.UnboundIProcess[ProxStream[Byte], E]](
          process: P,
          idx: Int
      ): P

      def mapLast[P <: Process.UnboundIProcess[O, E]](process: P): P
    }

    /** Process group with bound input, output and error streams */
    case class ProcessGroupImplIOE[O, E](
        override val firstProcess: Process[ProxStream[Byte], E],
        override val innerProcesses: List[
          Process.UnboundIProcess[ProxStream[Byte], E]
        ],
        override val lastProcess: Process.UnboundIProcess[O, E],
        override val originalProcesses: List[Process[Unit, Unit]]
    ) extends ProcessGroup[O, E] {

      override type Self = ProcessGroupImplIOE[O, E]

      def map(f: ProcessGroup.Mapper[O, E]): ProcessGroupImplIOE[O, E] = {
        copy(
          firstProcess = f.mapFirst(this.firstProcess),
          innerProcesses = this.innerProcesses.zipWithIndex.map {
            case (p, idx) => f.mapInnerWithIdx(p, idx + 1)
          },
          lastProcess = f.mapLast(this.lastProcess),
          originalProcesses
        )
      }
    }

    /** Process group with bound input and output streams */
    case class ProcessGroupImplIO[O](
        override val firstProcess: Process.UnboundEProcess[ProxStream[Byte]],
        override val innerProcesses: List[
          Process.UnboundIEProcess[ProxStream[Byte]]
        ],
        override val lastProcess: Process.UnboundIEProcess[O],
        override val originalProcesses: List[Process[Unit, Unit]]
    ) extends ProcessGroup[O, Unit]
        with RedirectableErrors[ProcessGroupImplIOE[O, *]] {

      override type Self = ProcessGroupImplIO[O]

      def map(f: ProcessGroup.Mapper[O, Unit]): ProcessGroupImplIO[O] = {
        copy(
          firstProcess = f.mapFirst(this.firstProcess),
          innerProcesses = this.innerProcesses.zipWithIndex.map {
            case (p, idx) => f.mapInnerWithIdx(p, idx + 1)
          },
          lastProcess = f.mapLast(this.lastProcess),
          originalProcesses
        )
      }

      override def connectErrors[
          R <: GroupErrorRedirection,
          OR <: OutputRedirection,
          E
      ](target: R)(implicit
          groupErrorRedirectionType: GroupErrorRedirectionType.Aux[R, OR, E],
          outputRedirectionType: OutputRedirectionType.Aux[OR, E]
      ): ProcessGroupImplIOE[O, E] = {
        val origs = originalProcesses.reverse.toVector
        ProcessGroupImplIOE(
          firstProcess.connectError(
            groupErrorRedirectionType
              .toOutputRedirectionType(target, origs.head)
          ),
          innerProcesses.zipWithIndex.map { case (p, idx) =>
            p.connectError(
              groupErrorRedirectionType
                .toOutputRedirectionType(target, origs(idx + 1))
            )
          },
          lastProcess.connectError(
            groupErrorRedirectionType
              .toOutputRedirectionType(target, origs.last)
          ),
          originalProcesses
        )
      }
    }

    /** Process group with bound input and error streams */
    case class ProcessGroupImplIE[E](
        override val firstProcess: Process[ProxStream[Byte], E],
        override val innerProcesses: List[
          Process.UnboundIProcess[ProxStream[Byte], E]
        ],
        override val lastProcess: Process.UnboundIOProcess[E],
        override val originalProcesses: List[Process[Unit, Unit]]
    ) extends ProcessGroup[Unit, E]
        with RedirectableOutput[ProcessGroupImplIOE[*, E]] {

      override type Self = ProcessGroupImplIE[E]

      def map(f: ProcessGroup.Mapper[Unit, E]): ProcessGroupImplIE[E] = {
        copy(
          firstProcess = f.mapFirst(this.firstProcess),
          innerProcesses = this.innerProcesses.zipWithIndex.map {
            case (p, idx) => f.mapInnerWithIdx(p, idx + 1)
          },
          lastProcess = f.mapLast(this.lastProcess),
          originalProcesses
        )
      }

      override def connectOutput[R <: OutputRedirection, RO](target: R)(implicit
          outputRedirectionType: OutputRedirectionType.Aux[R, RO]
      ): ProcessGroupImplIOE[RO, E] = {
        ProcessGroupImplIOE(
          firstProcess,
          innerProcesses,
          lastProcess.connectOutput(target),
          originalProcesses
        )
      }
    }

    /** Process group with bound output and error streams */
    case class ProcessGroupImplOE[O, E](
        override val firstProcess: Process.UnboundIProcess[ProxStream[Byte], E],
        override val innerProcesses: List[
          Process.UnboundIProcess[ProxStream[Byte], E]
        ],
        override val lastProcess: Process.UnboundIProcess[O, E],
        override val originalProcesses: List[Process[Unit, Unit]]
    ) extends ProcessGroup[O, E]
        with RedirectableInput[ProcessGroupImplIOE[O, E]] {

      override type Self = ProcessGroupImplOE[O, E]

      def map(f: ProcessGroup.Mapper[O, E]): ProcessGroupImplOE[O, E] = {
        copy(
          firstProcess = f.mapFirst(this.firstProcess),
          innerProcesses = this.innerProcesses.zipWithIndex.map {
            case (p, idx) => f.mapInnerWithIdx(p, idx + 1)
          },
          lastProcess = f.mapLast(this.lastProcess),
          originalProcesses
        )
      }

      override def connectInput(
          source: InputRedirection
      ): ProcessGroupImplIOE[O, E] = {
        ProcessGroupImplIOE(
          firstProcess.connectInput(source),
          innerProcesses,
          lastProcess,
          originalProcesses
        )
      }
    }

    /** Process group with bound input stream */
    case class ProcessGroupImplI(
        override val firstProcess: Process.UnboundEProcess[ProxStream[Byte]],
        override val innerProcesses: List[
          Process.UnboundIEProcess[ProxStream[Byte]]
        ],
        override val lastProcess: Process.UnboundProcess,
        override val originalProcesses: List[Process[Unit, Unit]]
    ) extends ProcessGroup[Unit, Unit]
        with RedirectableOutput[ProcessGroupImplIO[*]]
        with RedirectableErrors[ProcessGroupImplIE[*]] {

      override type Self = ProcessGroupImplI

      def map(f: ProcessGroup.Mapper[Unit, Unit]): ProcessGroupImplI = {
        copy(
          firstProcess = f.mapFirst(this.firstProcess),
          innerProcesses = this.innerProcesses.zipWithIndex.map {
            case (p, idx) => f.mapInnerWithIdx(p, idx + 1)
          },
          lastProcess = f.mapLast(this.lastProcess),
          originalProcesses
        )
      }

      override def connectOutput[R <: OutputRedirection, RO](target: R)(implicit
          outputRedirectionType: OutputRedirectionType.Aux[R, RO]
      ): ProcessGroupImplIO[RO] = {
        ProcessGroupImplIO(
          firstProcess,
          innerProcesses,
          lastProcess.connectOutput(target),
          originalProcesses
        )
      }

      override def connectErrors[
          R <: GroupErrorRedirection,
          OR <: OutputRedirection,
          E
      ](target: R)(implicit
          groupErrorRedirectionType: GroupErrorRedirectionType.Aux[R, OR, E],
          outputRedirectionType: OutputRedirectionType.Aux[OR, E]
      ): ProcessGroupImplIE[E] = {
        val origs = originalProcesses.reverse.toVector
        ProcessGroupImplIE(
          firstProcess.connectError(
            groupErrorRedirectionType
              .toOutputRedirectionType(target, origs.head)
          ),
          innerProcesses.zipWithIndex.map { case (p, idx) =>
            p.connectError(
              groupErrorRedirectionType
                .toOutputRedirectionType(target, origs(idx + 1))
            )
          },
          lastProcess.connectError(
            groupErrorRedirectionType
              .toOutputRedirectionType(target, origs.last)
          ),
          originalProcesses
        )
      }
    }

    /** Process group with bound output stream */
    case class ProcessGroupImplO[O](
        override val firstProcess: Process.UnboundIEProcess[ProxStream[Byte]],
        override val innerProcesses: List[
          Process.UnboundIEProcess[ProxStream[Byte]]
        ],
        override val lastProcess: Process.UnboundIEProcess[O],
        override val originalProcesses: List[Process[Unit, Unit]]
    ) extends ProcessGroup[O, Unit]
        with RedirectableInput[ProcessGroupImplIO[O]]
        with RedirectableErrors[ProcessGroupImplOE[O, *]] {

      override type Self = ProcessGroupImplO[O]

      def map(f: ProcessGroup.Mapper[O, Unit]): ProcessGroupImplO[O] = {
        copy(
          firstProcess = f.mapFirst(this.firstProcess),
          innerProcesses = this.innerProcesses.zipWithIndex.map {
            case (p, idx) => f.mapInnerWithIdx(p, idx + 1)
          },
          lastProcess = f.mapLast(this.lastProcess),
          originalProcesses
        )
      }

      override def connectInput(
          source: InputRedirection
      ): ProcessGroupImplIO[O] = {
        ProcessGroupImplIO(
          firstProcess.connectInput(source),
          innerProcesses,
          lastProcess,
          originalProcesses
        )
      }

      override def connectErrors[
          R <: GroupErrorRedirection,
          OR <: OutputRedirection,
          E
      ](target: R)(implicit
          groupErrorRedirectionType: GroupErrorRedirectionType.Aux[R, OR, E],
          outputRedirectionType: OutputRedirectionType.Aux[OR, E]
      ): ProcessGroupImplOE[O, E] = {
        val origs = originalProcesses.reverse.toVector
        ProcessGroupImplOE(
          firstProcess.connectError(
            groupErrorRedirectionType
              .toOutputRedirectionType(target, origs.head)
          ),
          innerProcesses.zipWithIndex.map { case (p, idx) =>
            p.connectError(
              groupErrorRedirectionType
                .toOutputRedirectionType(target, origs(idx + 1))
            )
          },
          lastProcess.connectError(
            groupErrorRedirectionType
              .toOutputRedirectionType(target, origs.last)
          ),
          originalProcesses
        )
      }
    }

    /** Process group with bound error stream */
    case class ProcessGroupImplE[E](
        override val firstProcess: Process.UnboundIProcess[ProxStream[Byte], E],
        override val innerProcesses: List[
          Process.UnboundIProcess[ProxStream[Byte], E]
        ],
        override val lastProcess: Process.UnboundIOProcess[E],
        override val originalProcesses: List[Process[Unit, Unit]]
    ) extends ProcessGroup[Unit, E]
        with RedirectableOutput[ProcessGroupImplOE[*, E]]
        with RedirectableInput[ProcessGroupImplIE[E]] {

      override type Self = ProcessGroupImplE[E]

      def map(f: ProcessGroup.Mapper[Unit, E]): ProcessGroupImplE[E] = {
        copy(
          firstProcess = f.mapFirst(this.firstProcess),
          innerProcesses = this.innerProcesses.zipWithIndex.map {
            case (p, idx) => f.mapInnerWithIdx(p, idx + 1)
          },
          lastProcess = f.mapLast(this.lastProcess),
          originalProcesses
        )
      }

      override def connectOutput[R <: OutputRedirection, RO](target: R)(implicit
          outputRedirectionType: OutputRedirectionType.Aux[R, RO]
      ): ProcessGroupImplOE[RO, E] = {
        ProcessGroupImplOE(
          firstProcess,
          innerProcesses,
          lastProcess.connectOutput(target),
          originalProcesses
        )
      }

      override def connectInput(
          source: InputRedirection
      ): ProcessGroupImplIE[E] = {
        ProcessGroupImplIE(
          firstProcess.connectInput(source),
          innerProcesses,
          lastProcess,
          originalProcesses
        )
      }
    }

    /** Process group with unbound input, output and error streams */
    case class ProcessGroupImpl(
        override val firstProcess: Process.UnboundIEProcess[ProxStream[Byte]],
        override val innerProcesses: List[
          Process.UnboundIEProcess[ProxStream[Byte]]
        ],
        override val lastProcess: Process.UnboundProcess,
        override val originalProcesses: List[Process[Unit, Unit]]
    ) extends ProcessGroup[Unit, Unit]
        with RedirectableOutput[ProcessGroupImplO[*]]
        with RedirectableInput[ProcessGroupImplI]
        with RedirectableErrors[ProcessGroupImplE[*]] {

      override type Self = ProcessGroupImpl

      def pipeInto(
          other: Process.UnboundProcess,
          channel: ProxPipe[Byte, Byte]
      ): ProcessGroupImpl = {
        val pl1 = lastProcess.connectOutput(
          OutputStreamThroughPipe(
            channel,
            (stream: ProxStream[Byte]) => pure(stream)
          )
        )

        copy(
          innerProcesses = pl1 :: innerProcesses,
          lastProcess = other,
          originalProcesses = other :: originalProcesses
        )
      }

      def |(other: Process.UnboundProcess): ProcessGroupImpl =
        pipeInto(other, identityPipe)

      def via(
          channel: ProxPipe[Byte, Byte]
      ): PipeBuilderSyntax[ProcessGroupImpl] =
        new PipeBuilderSyntax(
          new PipeBuilder[ProcessGroupImpl] {
            override def build(
                other: Process.UnboundProcess,
                channel: ProxPipe[Byte, Byte]
            ): ProcessGroupImpl =
              ProcessGroupImpl.this.pipeInto(other, channel)
          },
          channel
        )

      def map(f: ProcessGroup.Mapper[Unit, Unit]): ProcessGroupImpl = {
        copy(
          firstProcess = f.mapFirst(this.firstProcess),
          innerProcesses = this.innerProcesses.zipWithIndex.map {
            case (p, idx) => f.mapInnerWithIdx(p, idx + 1)
          },
          lastProcess = f.mapLast(this.lastProcess),
          originalProcesses
        )
      }

      override def connectInput(source: InputRedirection): ProcessGroupImplI =
        ProcessGroupImplI(
          firstProcess.connectInput(source),
          innerProcesses,
          lastProcess,
          originalProcesses
        )

      override def connectErrors[
          R <: GroupErrorRedirection,
          OR <: OutputRedirection,
          E
      ](target: R)(implicit
          groupErrorRedirectionType: GroupErrorRedirectionType.Aux[R, OR, E],
          outputRedirectionType: OutputRedirectionType.Aux[OR, E]
      ): ProcessGroupImplE[E] = {
        val origs = originalProcesses.reverse.toVector
        ProcessGroupImplE(
          firstProcess.connectError(
            groupErrorRedirectionType
              .toOutputRedirectionType(target, origs.head)
          ),
          innerProcesses.zipWithIndex.map { case (p, idx) =>
            p.connectError(
              groupErrorRedirectionType
                .toOutputRedirectionType(target, origs(idx + 1))
            )
          },
          lastProcess.connectError(
            groupErrorRedirectionType
              .toOutputRedirectionType(target, origs.last)
          ),
          originalProcesses
        )
      }

      override def connectOutput[R <: OutputRedirection, RO](target: R)(implicit
          outputRedirectionType: OutputRedirectionType.Aux[R, RO]
      ): ProcessGroupImplO[RO] = {
        ProcessGroupImplO(
          firstProcess,
          innerProcesses,
          lastProcess.connectOutput(target),
          originalProcesses
        )
      }
    }

  }

}
