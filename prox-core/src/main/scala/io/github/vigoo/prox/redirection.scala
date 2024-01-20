package io.github.vigoo.prox

import java.io
import java.nio.file.Path

trait RedirectionModule {
  this: Prox =>

  /** The capability to redirect the output of a process or a process group
    *
    * @tparam P
    *   Self type without the [[RedirectableOutput]] capability
    */
  trait RedirectableOutput[+P[_] <: ProcessLike] {

    /** The low level operation to attach an output to a process
      *
      * Use one of the other methods of this trait for convenience. This is the
      * place where the output type gets calculated with a helper type class
      * called [[OutputRedirectionType]] which implements the type level
      * computation for figuring out O.
      *
      * @param target
      *   Redirection target
      * @param outputRedirectionType
      *   Helper for dependent output type
      * @tparam R
      *   Output redirection type
      * @tparam O
      *   Output type
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def connectOutput[R <: OutputRedirection, O](target: R)(implicit
        outputRedirectionType: OutputRedirectionType.Aux[R, O]
    ): P[O]

    /** Redirects the output to a sink.
      *
      * The process output type will be [[Unit]]. An alias for [[toSink]]
      *
      * @param sink
      *   Target sink
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def >(sink: ProxSink[Byte]): P[Unit] =
      toSink(sink)

    /** Redirects the output to a sink.
      *
      * The process output type will be [[Unit]]. An alias for [[>]]
      *
      * @param sink
      *   Target sink
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def toSink(sink: ProxSink[Byte]): P[Unit] =
      connectOutput(OutputStreamToSink(sink))

    /** Redirects the output to a pipe and folds its output with a monoid
      * instance.
      *
      * The process output type will be the same as the pipe's output type. An
      * alias for [[toFoldMonoid]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe. Must have a monoid instance.
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def >#[O: ProxMonoid](pipe: ProxPipe[Byte, O]): P[O] =
      toFoldMonoid(pipe)

    /** Redirects the output to a pipe and folds its output with a monoid
      * instance.
      *
      * The process output type will be the same as the pipe's output type. An
      * alias for [[>#]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe. Must have a monoid instance.
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def toFoldMonoid[O: ProxMonoid](pipe: ProxPipe[Byte, O]): P[O] =
      connectOutput(
        OutputStreamThroughPipe(pipe, (s: ProxStream[O]) => s.foldMonoid)
      )

    /** Redirects the output to a pipe and collects its output to a vector
      *
      * The process output type will be a vector of the pipe's output type. An
      * alias for [[toVector]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def >?[O](pipe: ProxPipe[Byte, O]): P[Vector[O]] =
      toVector(pipe)

    /** Redirects the output to a pipe and collects its output to a vector
      *
      * The process output type will be a vector of the pipe's output type. An
      * alias for [[>?]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def toVector[O](pipe: ProxPipe[Byte, O]): P[Vector[O]] =
      connectOutput(
        OutputStreamThroughPipe(pipe, (s: ProxStream[O]) => s.toVector)
      )

    /** Redirects the output to a pipe and drains it regardless of its output
      * type.
      *
      * The process output type will be [[Unit]].
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def drainOutput[O](pipe: ProxPipe[Byte, O]): P[Unit] =
      connectOutput(
        OutputStreamThroughPipe(pipe, (s: ProxStream[O]) => s.drain)
      )

    /** Redirects the output to a pipe and folds it with a custom function.
      *
      * The process output type will be R.
      *
      * @param pipe
      *   Target pipe
      * @param init
      *   The initial value for the fold
      * @param fn
      *   The fold function
      * @tparam O
      *   Output type of the pipe
      * @tparam R
      *   Result type of the fold
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def foldOutput[O, R](
        pipe: ProxPipe[Byte, O],
        init: R,
        fn: (R, O) => R
    ): P[R] =
      connectOutput(
        OutputStreamThroughPipe(pipe, (s: ProxStream[O]) => s.fold(init, (fn)))
      )

    /** Redirects the output to a file natively
      *
      * An alias for [[toFile]]
      *
      * @param path
      *   Target file path
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def >(path: Path): P[Unit] =
      toFile(path)

    /** Redirects the output to a file natively
      *
      * An alias for [[>]]
      *
      * @param path
      *   Target file path
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def toFile(path: Path): P[Unit] =
      connectOutput(OutputFile(path, append = false))

    /** Redirects the output to a file natively in append mode
      *
      * An alias for [[appendToFile]]
      *
      * @param path
      *   Target file path
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def >>(path: Path): P[Unit] =
      appendToFile(path)

    /** Redirects the output to a file natively in append mode
      *
      * An alias for [[>>]]
      *
      * @param path
      *   Target file path
      * @return
      *   Returns a new process or process group with its output redirected and
      *   its output redirection capability removed.
      */
    def appendToFile(path: Path): P[Unit] =
      connectOutput(OutputFile(path, append = true))
  }

  /** The capability to redirect the error output of a process
    *
    * @tparam P
    *   Self type without the [[RedirectableError]] capability
    */
  trait RedirectableError[+P[_] <: Process[_, _]] {

    /** The low level operation to attach an error output to a process
      *
      * Use one of the other methods of this trait for convenience. This is the
      * place where the output type gets calculated with a helper type class
      * called [[OutputRedirectionType]] which implements the type level
      * computation for figuring out E.
      *
      * @param target
      *   Redirection target
      * @param outputRedirectionType
      *   Helper for dependent error output type
      * @tparam R
      *   Error output redirection type
      * @tparam E
      *   Error output type
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def connectError[R <: OutputRedirection, E](target: R)(implicit
        outputRedirectionType: OutputRedirectionType.Aux[R, E]
    ): P[E]

    /** Redirects the error output to a sink.
      *
      * The process error output type will be [[Unit]]. An alias for
      * [[errorToSink]]
      *
      * @param sink
      *   Target sink
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def !>(sink: ProxSink[Byte]): P[Unit] =
      errorToSink(sink)

    /** Redirects the error output to a sink.
      *
      * The process error output type will be [[Unit]]. An alias for [[!>]]
      *
      * @param sink
      *   Target sink
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def errorToSink(sink: ProxSink[Byte]): P[Unit] =
      connectError(OutputStreamToSink(sink))

    /** Redirects the error output to a pipe and folds its output with a monoid
      * instance.
      *
      * The process error output type will be the same as the pipe's output
      * type. An alias for [[errorToFoldMonoid]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe. Must have a monoid instance.
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def !>#[O: ProxMonoid](pipe: ProxPipe[Byte, O]): P[O] =
      errorToFoldMonoid(pipe)

    /** Redirects the error output to a pipe and folds its output with a monoid
      * instance.
      *
      * The process error output type will be the same as the pipe's output
      * type. An alias for [[!>#]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe. Must have a monoid instance.
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def errorToFoldMonoid[O: ProxMonoid](pipe: ProxPipe[Byte, O]): P[O] =
      connectError(
        OutputStreamThroughPipe(pipe, (s: ProxStream[O]) => s.foldMonoid)
      )

    /** Redirects the error output to a pipe and collects its output to a vector
      *
      * The process error output type will be a vector of the pipe's output
      * type. An alias for [[errorToVector]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def !>?[O](pipe: ProxPipe[Byte, O]): P[Vector[O]] =
      errorToVector(pipe)

    /** Redirects the error output to a pipe and collects its output to a vector
      *
      * The process error output type will be a vector of the pipe's output
      * type. An alias for [[!>?]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def errorToVector[O](pipe: ProxPipe[Byte, O]): P[Vector[O]] =
      connectError(
        OutputStreamThroughPipe(pipe, (s: ProxStream[O]) => s.toVector)
      )

    /** Redirects the error output to a pipe and drains it regardless of its
      * output type.
      *
      * The process error output type will be [[Unit]].
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def drainError[O](pipe: ProxPipe[Byte, O]): P[Unit] =
      connectError(OutputStreamThroughPipe(pipe, (s: ProxStream[O]) => s.drain))

    /** Redirects the error output to a pipe and folds it with a custom
      * function.
      *
      * The process error output type will be R.
      *
      * @param pipe
      *   Target pipe
      * @param init
      *   The initial value for the fold
      * @param fn
      *   The fold function
      * @tparam O
      *   Output type of the pipe
      * @tparam R
      *   Result type of the fold
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def foldError[O, R](
        pipe: ProxPipe[Byte, O],
        init: R,
        fn: (R, O) => R
    ): P[R] =
      connectError(
        OutputStreamThroughPipe(pipe, (s: ProxStream[O]) => s.fold(init, fn))
      )

    /** Redirects the error output to a file natively
      *
      * An alias for [[errorToFile]]
      *
      * @param path
      *   Target file path
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def !>(path: Path): P[Unit] =
      errorToFile(path)

    /** Redirects the error output to a file natively
      *
      * An alias for [[!>]]
      *
      * @param path
      *   Target file path
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def errorToFile(path: Path): P[Unit] =
      connectError(OutputFile(path, append = false))

    /** Redirects the error output to a file natively in append mode
      *
      * An alias for [[appendErrorToFile]]
      *
      * @param path
      *   Target file path
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def !>>(path: Path): P[Unit] =
      appendErrorToFile(path)

    /** Redirects the error output to a file natively in append mode
      *
      * An alias for [[!>>]]
      *
      * @param path
      *   Target file path
      * @return
      *   Returns a new process with its error output redirected and its error
      *   redirection capability removed.
      */
    def appendErrorToFile(path: Path): P[Unit] =
      connectError(OutputFile(path, append = true))
  }

  /** The capability to redirect all the error outputs simultaneously of a
    * process group
    *
    * @tparam P
    *   Self type without the [[RedirectableErrors]] capability
    */
  trait RedirectableErrors[+P[_] <: ProcessGroup[_, _]] {

    /** A more advanced interface for customizing the redirection per process */
    lazy val customizedPerProcess: RedirectableErrors.CustomizedPerProcess[P] =
      new RedirectableErrors.CustomizedPerProcess[P] {
        override def connectErrors[
            R <: GroupErrorRedirection,
            OR <: OutputRedirection,
            E
        ](target: R)(implicit
            groupErrorRedirectionType: GroupErrorRedirectionType.Aux[R, OR, E],
            outputRedirectionType: OutputRedirectionType.Aux[OR, E]
        ): P[E] =
          RedirectableErrors.this.connectErrors(target)
      }

    /** The low level operation to attach an error output to all the processes
      * in the group.
      *
      * Use one of the other methods of this trait or the advanced interface
      * represented by [[customizedPerProcess]] for convenience.
      *
      * This is the place where the process group's error output type gets
      * calculated using the [[GroupErrorRedirectionType]] and
      * [[OutputRedirectionType]] type classes.
      *
      * @param target
      *   Redirection target
      * @param groupErrorRedirectionType
      *   Helper for dependent error output type
      * @param outputRedirectionType
      *   Helper for dependent error output type
      * @tparam R
      *   Error output grouped redirection type
      * @tparam OR
      *   Error output redirection type
      * @tparam E
      *   Error output type
      * @return
      *   Returns a new process group with all the error streams redirected and
      *   the error redirection capability removed.
      */
    def connectErrors[R <: GroupErrorRedirection, OR <: OutputRedirection, E](
        target: R
    )(implicit
        groupErrorRedirectionType: GroupErrorRedirectionType.Aux[R, OR, E],
        outputRedirectionType: OutputRedirectionType.Aux[OR, E]
    ): P[E]

    /** Redirects the error outputs to a sink.
      *
      * The process error output type will be [[Unit]]. An alias for
      * [[errorsToSink]]
      *
      * @param sink
      *   Target sink
      * @return
      *   Returns a new process group with all the error streams redirected and
      *   the error redirection capability removed.
      */
    def !>(sink: ProxSink[Byte]): P[Unit] =
      errorsToSink(sink)

    /** Redirects the error outputs to a sink.
      *
      * The process error output type will be [[Unit]]. An alias for [[!>]]
      *
      * @param sink
      *   Target sink
      * @return
      *   Returns a new process group with all the error streams redirected and
      *   the error redirection capability removed.
      */
    def errorsToSink(sink: ProxSink[Byte]): P[Unit] =
      customizedPerProcess.errorsToSink(_ => sink)

    /** Redirects the error outputs to a pipe and folds its output with a monoid
      * instance.
      *
      * The process error output type will be the same as the pipe's output
      * type. An alias for [[errorsToFoldMonoid]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe. Must have a monoid instance.
      * @return
      *   Returns a new process group with all the error streams redirected and
      *   the error redirection capability removed.
      */
    def !>#[O: ProxMonoid](pipe: ProxPipe[Byte, O]): P[O] =
      errorsToFoldMonoid(pipe)

    /** Redirects the error outputs to a pipe and folds its output with a monoid
      * instance.
      *
      * The process error output type will be the same as the pipe's output
      * type. An alias for [[!>#]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe. Must have a monoid instance.
      * @return
      *   Returns a new process group with all the error streams redirected and
      *   the error redirection capability removed.
      */
    def errorsToFoldMonoid[O: ProxMonoid](pipe: ProxPipe[Byte, O]): P[O] =
      customizedPerProcess.errorsToFoldMonoid(_ => pipe)

    /** Redirects the error outputs to a pipe and collects its output to a
      * vector
      *
      * The process error output type will be a vector of the pipe's output
      * type. An alias for [[errorsToVector]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe
      * @return
      *   Returns a new process group with all the error streams redirected and
      *   the error redirection capability removed.
      */
    def !>?[O](pipe: ProxPipe[Byte, O]): P[Vector[O]] =
      errorsToVector(pipe)

    /** Redirects the error outputs to a pipe and collects its output to a
      * vector
      *
      * The process error output type will be a vector of the pipe's output
      * type. An alias for [[!>?]]
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe
      * @return
      *   Returns a new process group with all the error streams redirected and
      *   the error redirection capability removed.
      */
    def errorsToVector[O](pipe: ProxPipe[Byte, O]): P[Vector[O]] =
      customizedPerProcess.errorsToVector(_ => pipe)

    /** Redirects the error outputs to a pipe and drains it regardless of its
      * output type.
      *
      * The process error output type will be [[Unit]].
      *
      * @param pipe
      *   Target pipe
      * @tparam O
      *   Output type of the pipe
      * @return
      *   Returns a new process group with all the error streams redirected and
      *   the error redirection capability removed.
      */
    def drainErrors[O](pipe: ProxPipe[Byte, O]): P[Unit] =
      customizedPerProcess.drainErrors(_ => pipe)

    /** Redirects the error outputs to a pipe and folds it with a custom
      * function.
      *
      * The process error output type will be R.
      *
      * @param pipe
      *   Target pipe
      * @param init
      *   The initial value for the fold
      * @param fn
      *   The fold function
      * @tparam O
      *   Output type of the pipe
      * @tparam R
      *   Result type of the fold
      * @return
      *   Returns a new process group with all the error streams redirected and
      *   the error redirection capability removed.
      */
    def foldErrors[O, R](
        pipe: ProxPipe[Byte, O],
        init: R,
        fn: (R, O) => R
    ): P[R] =
      customizedPerProcess.foldErrors(_ => pipe, init, fn)
  }

  object RedirectableErrors {

    /** Advanced version of the [[RedirectableErrors]] interface enabling
      * per-process customizations.
      *
      * @tparam P
      *   Self type without [[RedirectableErrors]]
      */
    trait CustomizedPerProcess[+P[_] <: ProcessGroup[_, _]] {

      /** See [[RedirectableErrors.connectErrors]] */
      def connectErrors[R <: GroupErrorRedirection, OR <: OutputRedirection, E](
          target: R
      )(implicit
          groupErrorRedirectionType: GroupErrorRedirectionType.Aux[R, OR, E],
          outputRedirectionType: OutputRedirectionType.Aux[OR, E]
      ): P[E]

      /** Redirects the error outputs to a sink.
        *
        * The process error output type will be [[Unit]].
        *
        * @param sinkFn
        *   Function to get a sink for each process in the group
        * @return
        *   Returns a new process group with all the error streams redirected
        *   and the error redirection capability removed.
        */
      def errorsToSink(sinkFn: Process[_, _] => ProxSink[Byte]): P[Unit] =
        connectErrors(AllCapturedToSink(sinkFn))

      /** Redirects the error outputs to a pipe and folds its output with a
        * monoid instance.
        *
        * The process error output type will be the same as the pipe's output
        * type.
        *
        * @param pipeFn
        *   A function to get a pipe for each process in the group
        * @tparam O
        *   Output type of the pipe. Must have a monoid instance.
        * @return
        *   Returns a new process group with all the error streams redirected
        *   and the error redirection capability removed.
        */
      def errorsToFoldMonoid[O: ProxMonoid](
          pipeFn: Process[_, _] => ProxPipe[Byte, O]
      ): P[O] =
        connectErrors(
          AllCapturedThroughPipe(pipeFn, (s: ProxStream[O]) => s.foldMonoid)
        )

      /** Redirects the error outputs to a pipe and collects its output to a
        * vector
        *
        * The process error output type will be a vector of the pipe's output
        * type.
        *
        * @param pipeFn
        *   A function to get a pipe for each process in the group
        * @tparam O
        *   Output type of the pipe
        * @return
        *   Returns a new process group with all the error streams redirected
        *   and the error redirection capability removed.
        */
      def errorsToVector[O](
          pipeFn: Process[_, _] => ProxPipe[Byte, O]
      ): P[Vector[O]] =
        connectErrors(
          AllCapturedThroughPipe(pipeFn, (s: ProxStream[O]) => s.toVector)
        )

      /** Redirects the error outputs to a pipe and drains it regardless of its
        * output type.
        *
        * The process error output type will be [[Unit]].
        *
        * @param pipeFn
        *   A function to get a pipe for each process in the group
        * @tparam O
        *   Output type of the pipe
        * @return
        *   Returns a new process group with all the error streams redirected
        *   and the error redirection capability removed.
        */
      def drainErrors[O](pipeFn: Process[_, _] => ProxPipe[Byte, O]): P[Unit] =
        connectErrors(
          AllCapturedThroughPipe(pipeFn, (s: ProxStream[O]) => s.drain)
        )

      /** Redirects the error outputs to a pipe and folds it with a custom
        * function.
        *
        * The process error output type will be R.
        *
        * @param pipeFn
        *   A function to get a pipe for each process in the group
        * @param init
        *   The initial value for the fold
        * @param fn
        *   The fold function
        * @tparam O
        *   Output type of the pipe
        * @tparam R
        *   Result type of the fold
        * @return
        *   Returns a new process group with all the error streams redirected
        *   and the error redirection capability removed.
        */
      def foldErrors[O, R](
          pipeFn: Process[_, _] => ProxPipe[Byte, O],
          init: R,
          fn: (R, O) => R
      ): P[R] =
        connectErrors(
          AllCapturedThroughPipe(pipeFn, (s: ProxStream[O]) => s.fold(init, fn))
        )

      /** Redirects the error outputs to one file per process
        *
        * The process error output type will be [[Unit]].
        *
        * @param pathFn
        *   A function to get a file path for each process in the group
        * @return
        *   Returns a new process group with all the error streams redirected
        *   and the error redirection capability removed.
        */
      def errorsToFile(pathFn: Process[_, _] => Path): P[Unit] =
        connectErrors(AllToFile(pathFn, append = false))

      /** Redirects the error outputs to one file per process in append mode
        *
        * The process error output type will be [[Unit]].
        *
        * @param pathFn
        *   A function to get a file path for each process in the group
        * @return
        *   Returns a new process group with all the error streams redirected
        *   and the error redirection capability removed.
        */
      def appendErrorsToFile(pathFn: Process[_, _] => Path): P[Unit] =
        connectErrors(AllToFile(pathFn, append = true))
    }

  }

  /** The capability to redirect the input of a process or process group
    *
    * @tparam P
    */
  trait RedirectableInput[+P <: ProcessLike] {

    /** The low level method to attach an input to a process or process group.
      *
      * Use the other methods in this trait for convenience.
      *
      * @param source
      *   Redirection source
      * @return
      *   A new process or process group with the input redirected and the input
      *   redirection capability removed.
      */
    def connectInput(source: InputRedirection): P

    /** Feed the process input from a file natively.
      *
      * An alias for [[fromFile]].
      *
      * @param path
      *   Path to the file
      * @return
      *   A new process or process group with the input redirected and the input
      *   redirection capability removed.
      */
    def <(path: Path): P =
      fromFile(path)

    /** Feed the process input from a file natively.
      *
      * An alias for [[<]].
      *
      * @param path
      *   Path to the file
      * @return
      *   A new process or process group with the input redirected and the input
      *   redirection capability removed.
      */
    def fromFile(path: Path): P =
      connectInput(InputFile(path))

    /** Feed the process input from a byte stream.
      *
      * An alias for [[fromStream]].
      *
      * @param stream
      *   Input stream
      * @return
      *   A new process or process group with the input redirected and the input
      *   redirection capability removed.
      */
    def <(stream: ProxStream[Byte]): P =
      fromStream(stream, flushChunks = false)

    /** Feed the process input from a byte stream with flushing per chunks
      * enabled.
      *
      * An alias for [[fromStream]].
      *
      * @param stream
      *   Input stream
      * @return
      *   A new process or process group with the input redirected and the input
      *   redirection capability removed.
      */
    def !<(stream: ProxStream[Byte]): P =
      fromStream(stream, flushChunks = true)

    /** Feed the process input from a byte stream.
      *
      * An alias for [[<]] and [[!<]].
      *
      * @param stream
      *   Input stream
      * @param flushChunks
      *   Flush the process input stream after each chunk
      * @return
      *   A new process or process group with the input redirected and the input
      *   redirection capability removed.
      */
    def fromStream(stream: ProxStream[Byte], flushChunks: Boolean): P =
      connectInput(InputStream(stream, flushChunks))
  }

  /** Supported output redirection types. Should not be used directly, see the
    * redirection traits instead.
    */
  sealed trait OutputRedirection

  case class StdOut() extends OutputRedirection

  case class OutputFile(path: Path, append: Boolean) extends OutputRedirection

  case class OutputStreamThroughPipe[O, OR](
      pipe: ProxPipe[Byte, O],
      runner: ProxStream[O] => ProxIO[OR],
      chunkSize: Int = 8192
  ) extends OutputRedirection

  case class OutputStreamToSink(sink: ProxSink[Byte], chunkSize: Int = 8192)
      extends OutputRedirection

  /** Supported process group error redirection types. Should not be used
    * directly, see the redirection traits instead.
    */
  sealed trait GroupErrorRedirection

  case class AllToStdErr() extends GroupErrorRedirection

  case class AllToFile(pathFn: Process[_, _] => Path, append: Boolean)
      extends GroupErrorRedirection

  case class AllCapturedThroughPipe[O, OR](
      pipeFn: Process[_, _] => ProxPipe[Byte, O],
      runner: ProxStream[O] => ProxIO[OR],
      chunkSize: Int = 8192
  ) extends GroupErrorRedirection

  case class AllCapturedToSink(
      sinkFn: Process[_, _] => ProxSink[Byte],
      chunkSize: Int = 8192
  ) extends GroupErrorRedirection

  /** Supported input redirection types. Should not be used directly, see the
    * redirection traits instead.
    */
  sealed trait InputRedirection

  case class StdIn() extends InputRedirection

  case class InputFile(path: Path) extends InputRedirection

  case class InputStream(stream: ProxStream[Byte], flushChunks: Boolean)
      extends InputRedirection

  /** Helper type class for output and error redirection dependent typing */
  trait OutputRedirectionType[R] {
    type Out

    def runner(of: R)(nativeStream: java.io.InputStream): ProxIO[Out]
  }

  object OutputRedirectionType {
    type Aux[R, O] = OutputRedirectionType[R] {
      type Out = O
    }

    implicit def outputRedirectionTypeOfStdOut: Aux[StdOut, Unit] =
      new OutputRedirectionType[StdOut] {
        override type Out = Unit

        override def runner(of: StdOut)(
            nativeStream: java.io.InputStream
        ): ProxIO[Unit] = unit
      }

    implicit def outputRedirectionTypeOfFile: Aux[OutputFile, Unit] =
      new OutputRedirectionType[OutputFile] {
        override type Out = Unit

        override def runner(of: OutputFile)(
            nativeStream: java.io.InputStream
        ): ProxIO[Unit] = unit
      }

    implicit def outputRedirectionTypeOfStreamThroughPipe[O, OR]
        : Aux[OutputStreamThroughPipe[O, OR], OR] =
      new OutputRedirectionType[OutputStreamThroughPipe[O, OR]] {
        override type Out = OR

        override def runner(
            of: OutputStreamThroughPipe[O, OR]
        )(nativeStream: java.io.InputStream): ProxIO[OR] = {
          of.runner(
            fromJavaInputStream(nativeStream, of.chunkSize).through(of.pipe)
          )
        }
      }

    implicit def outputRedirectionTypeOfStreamToSink[O]
        : Aux[OutputStreamToSink, Unit] =
      new OutputRedirectionType[OutputStreamToSink] {
        override type Out = Unit

        override def runner(of: OutputStreamToSink)(
            nativeStream: io.InputStream
        ): ProxIO[Unit] =
          fromJavaInputStream(nativeStream, of.chunkSize).run(of.sink)
      }
  }

  /** Helper type class for process group error redirection dependent typing */
  trait GroupErrorRedirectionType[R] {
    type Out
    type OutputR <: OutputRedirection

    def toOutputRedirectionType(redir: R, process: Process[_, _]): OutputR
  }

  object GroupErrorRedirectionType {
    type Aux[R, OR, O] = GroupErrorRedirectionType[R] {
      type Out = O
      type OutputR = OR
    }

    implicit def groupErrorRedirectionTypeOfStdErr
        : Aux[AllToStdErr, StdOut, Unit] =
      new GroupErrorRedirectionType[AllToStdErr] {
        override type Out = Unit
        override type OutputR = StdOut

        override def toOutputRedirectionType(
            redir: AllToStdErr,
            process: Process[_, _]
        ): StdOut = StdOut()
      }

    implicit def groupErrorRedirectionTypeOfFile
        : Aux[AllToFile, OutputFile, Unit] =
      new GroupErrorRedirectionType[AllToFile] {
        override type Out = Unit
        override type OutputR = OutputFile

        override def toOutputRedirectionType(
            redir: AllToFile,
            process: Process[_, _]
        ): OutputFile = OutputFile(redir.pathFn(process), redir.append)
      }

    implicit def groupErrorRedirectionTypeOfStreamThroughPipe[O, OR]: Aux[
      AllCapturedThroughPipe[O, OR],
      OutputStreamThroughPipe[O, OR],
      OR
    ] = new GroupErrorRedirectionType[AllCapturedThroughPipe[O, OR]] {
      override type Out = OR
      override type OutputR = OutputStreamThroughPipe[O, OR]

      override def toOutputRedirectionType(
          redir: AllCapturedThroughPipe[O, OR],
          process: Process[_, _]
      ): OutputStreamThroughPipe[O, OR] =
        OutputStreamThroughPipe(
          redir.pipeFn(process),
          redir.runner,
          redir.chunkSize
        )
    }

    implicit val groupErrorRedirectionTypeOfStreamToSink
        : Aux[AllCapturedToSink, OutputStreamToSink, Unit] =
      new GroupErrorRedirectionType[AllCapturedToSink] {
        override type Out = Unit
        override type OutputR = OutputStreamToSink

        override def toOutputRedirectionType(
            redir: AllCapturedToSink,
            process: Process[_, _]
        ): OutputStreamToSink =
          OutputStreamToSink(redir.sinkFn(process), redir.chunkSize)
      }
  }

}
