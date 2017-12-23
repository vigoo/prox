package io.github.vigoo

/** Provides classes to work with system processes in a type safe way.
  *
  * == Overview ==
  * A system process to be started is represented by the [[io.github.vigoo.prox.Process]] case class,
  * taking a command and optionaly a list of arguments and a working directory:
  *
  * {{{
  *   import io.github.vigoo.prox._
  *   import io.github.vigoo.prox.syntax._
  *
  *   val process = Process("echo", List("Hello world"))
  * }}}
  *
  * To start the process, we can use the [[io.github.vigoo.prox.syntax.ProcessOps.start]] extension method,
  * defined in [[io.github.vigoo.prox.syntax.ProcessOps]], which creates a cats IO operation returning
  * a [[io.github.vigoo.prox.RunningProcess]] instance.
  *
  * Use the returned value to wait for the process to exit or to send a termination signal to it:
  *
  * {{{
  *   val program =
  *   for {
  *     echo <- Process("echo", List("Hello world")).start
  *     result <- echo.waitForExit()
  *   } yield result.exitCode
  *
  *   val exitCode = program.unsafeRunSync()
  * }}}
  *
  * == Redirection ==
  * The [[io.github.vigoo.prox.syntax.ProcessNodeInputRedirect]], [[io.github.vigoo.prox.syntax.ProcessNodeOutputRedirect]]
  * and [[io.github.vigoo.prox.syntax.ProcessNodeErrorRedirect]] classes define extension methods for processes to
  * redirect their inpout, output and error streams. The target of redirection can be anything that has the matching
  * type class defined for: [[io.github.vigoo.prox.CanBeProcessInputSource]], [[io.github.vigoo.prox.CanBeProcessOutputTarget]]
  * and [[io.github.vigoo.prox.CanBeProcessErrorTarget]].
  *
  * The three extension methods are [[io.github.vigoo.prox.syntax.ProcessNodeInputRedirect.<]],
  * [[io.github.vigoo.prox.syntax.ProcessNodeOutputRedirect.>]] and [[io.github.vigoo.prox.syntax.ProcessNodeErrorRedirect.redirectErrorTo]]
  *
  * The type system guarantees that each redirection can only happen once for a process.
  *
  * == Streams ==
  * The library provides type classes to redirect to/from fs2 streams: [[io.github.vigoo.prox.InputStreamingSource]] for
  * feeding a stream as a process' input, and [[io.github.vigoo.prox.OutputStreamingTarget]] and [[io.github.vigoo.prox.ErrorStreamingTarget]]
  * to feed a process' output and error to fs2 pipes.
  *
  * Three special wrapper types can be used to modify how the streams are executed when the IO operation is composed:
  *
  * * [[io.github.vigoo.prox.Ignore]] to run the stream with [[fs2.Stream.InvariantOps.run]], having a result of [[Unit]]
  * * [[io.github.vigoo.prox.Log]] to run the stream with [[fs2.Stream.InvariantOps.runLog]], having a result of [[Vector]]
  * * [[io.github.vigoo.prox.Fold]] to run the stream with [[fs2.Stream.InvariantOps.runFold]], having a custom fold result
  *
  * If none of the above wrappers is used, the default is to use [[fs2.Stream.InvariantOps.run]] for [[fs2.Sink]],
  * [[fs2.Stream.InvariantOps.runFoldMonoid]] if the pipe's output is a [[cats.Monoid]] and [[fs2.Stream.InvariantOps.runLog]]
  * otherwise.
  *
  * == Piping ==
  * The [[io.github.vigoo.prox.syntax.ProcessNodeOutputRedirect]] class defined two extension methods for piping one
  * process to another: [[io.github.vigoo.prox.syntax.ProcessNodeOutputRedirect.|]] for direct piping and
  * [[io.github.vigoo.prox.syntax.ProcessNodeOutputRedirect.via]] for piping through a custom fs2 [[fs2.Pipe]].
  *
  * The result of the piping operators is a [[io.github.vigoo.prox.PipedProcess]], which can be used exactly as a
  * simple process, but its [[io.github.vigoo.prox.syntax.ProcessOps.start]] method returns a tuple of
  * [[io.github.vigoo.prox.RunningProcess]] instances.
  *
  * @author Daniel Vigovszky
  */
package object prox {
}
