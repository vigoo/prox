package io.github.vigoo.prox

trait SyntaxModule {
  this: ProxRuntime with ProcessModule with ProcessGroupModule with RedirectionModule =>

  /** Extension methods for unbound processes enabling the creation of process groups */
  implicit class ProcessPiping(process: Process.UnboundProcess) {

    /**
      * Attaches the output of this process to an other process' input
      *
      * Use the [[|]] or the [[via]] methods instead for more readability.
      *
      * @param other   The other process
      * @param channel Pipe between the two processes
      * @return Returns a [[ProcessGroup]]
      */
    def pipeInto(other: Process.UnboundProcess,
                 channel: ProxPipe[Byte, Byte]): ProcessGroup.ProcessGroupImpl = {

      val p1 = process.connectOutput(OutputStreamThroughPipe(channel, (stream: ProxStream[Byte]) => pure(stream)))

      ProcessGroup.ProcessGroupImpl(
        p1,
        List.empty,
        other,
        List(other, process)
      )
    }

    /**
      * Attaches the output of this process to an other process' input
      *
      * @param other The other process
      * @return Returns a [[ProcessGroup]]
      */
    def |(other: Process.UnboundProcess): ProcessGroup.ProcessGroupImpl =
      pipeInto(other, identityPipe)

    /**
      * Attaches the output of this process to an other process' input with a custom channel
      *
      * There is a syntax helper step to allow the following syntax:
      * {{{
      *   val processGroup = process1.via(channel).to(process2)
      * }}}
      *
      * @param channel Pipe between the two processes
      * @return Returns a syntax helper trait that has a [[PipeBuilderSyntax.to]] method to finish the construction
      */
    def via(channel: ProxPipe[Byte, Byte]): PipeBuilderSyntax[ProcessGroup.ProcessGroupImpl] =
      new PipeBuilderSyntax(new PipeBuilder[ProcessGroup.ProcessGroupImpl] {
        override def build(other: Process.UnboundProcess, channel: ProxPipe[Byte, Byte]): ProcessGroup.ProcessGroupImpl =
          process.pipeInto(other, channel)
      }, channel)
  }

  trait PipeBuilder[P] {
    def build(other: Process.UnboundProcess,
              channel: ProxPipe[Byte, Byte]): P
  }

  class PipeBuilderSyntax[P](builder: PipeBuilder[P], channel: ProxPipe[Byte, Byte]) {
    def to(other: Process.UnboundProcess): P =
      builder.build(other, channel)

  }

  /**
    * String interpolator for an alternative of [[Process.apply]]
    *
    * {{{
    * val process = proc"ls -hal $dir"
    * }}}
    */
  implicit class ProcessStringContextIO(ctx: StringContext) {

    def proc(args: Any*): Process.ProcessImpl = {
      val staticParts = ctx.parts.map(Left.apply)
      val injectedParts = args.map(Right.apply)
      val parts = staticParts.zipAll(injectedParts, Left(""), Right("")).flatMap { case (a, b) => List(a, b) }
      val words = parts.flatMap {
        case Left(value) => value.trim.split(' ')
        case Right(value) => List(value.toString)
      }.filter(_.nonEmpty).toList
      words match {
        case head :: remaining =>
          Process(head, remaining)
        case Nil =>
          throw new IllegalArgumentException(s"The proc interpolator needs at least a process name")
      }
    }
  }
}