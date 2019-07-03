package io.github.vigoo

import io.github.vigoo.prox._

/** Provides classes to work with system processes in a type safe way.
  *
  * == Overview ==
  * A system process to be started is represented by the [[Process]] case class,
  * taking a command and optionally a list of arguments and a working directory:
  *
  * {{{
  *   import io.github.vigoo.prox._
  *   import io.github.vigoo.prox.syntax._
  *
  *   val process = Process("echo", List("Hello world"))
  * }}}
  *
  * To start the process, we can use the [[syntax.ProcessOps.start]] extension method,
  * defined in [[syntax.ProcessOps]], which creates a cats IO operation returning
  * a [[RunningProcess]] instance.
  *
  * Use the returned value to wait for the process to exit or to send a termination signal to it:
  *
  * {{{
  *   val program = Blocker[IO].use { blocker =>
  *     for {
  *       echo <- Process("echo", List("Hello world")).start(blocker)
  *       result <- echo.waitForExit()
  *     } yield result.exitCode
  *   }
  *
  *   val exitCode = program.unsafeRunSync()
  * }}}
  *
  * == Redirection ==
  * The [[syntax.ProcessNodeInputRedirect]], [[syntax.ProcessNodeOutputRedirect]]
  * and [[syntax.ProcessNodeErrorRedirect]] classes define extension methods for processes to
  * redirect their inpout, output and error streams. The target of redirection can be anything that has the matching
  * type class defined for: [[CanBeProcessInputSource]], [[CanBeProcessOutputTarget]]
  * and [[CanBeProcessErrorTarget]].
  *
  * The three extension methods are [[syntax.ProcessNodeInputRedirect.<]],
  * [[syntax.ProcessNodeOutputRedirect.>]] and [[syntax.ProcessNodeErrorRedirect.redirectErrorTo]]
  *
  * The type system guarantees that each redirection can only happen once for a process.
  *
  * == Streams ==
  * The library provides type classes to redirect to/from fs2 streams: [[InputStreamingSource]] for
  * feeding a stream as a process' input, and [[OutputStreamingTarget]] and [[ErrorStreamingTarget]]
  * to feed a process' output and error to fs2 pipes.
  *
  * Three special wrapper types can be used to modify how the streams are executed when the IO operation is composed:
  *
  *  - [[Drain]] to run the stream with [[fs2.Stream.CompileOps.drain]], having a result of [[Unit]]
  *  - [[ToVector]] to run the stream with [[fs2.Stream.CompileOps.toVector]], having a result of [[Vector]]
  *  - [[Fold]] to run the stream with [[fs2.Stream.CompileOps.fold]], having a custom fold result
  *
  * If none of the above wrappers is used, the default is to use [[fs2.Stream.CompileOps.drain]] for [[fs2.Sink]],
  * [[fs2.Stream.CompileOps.foldMonoid]] if the pipe's output is a [[cats.Monoid]] and [[fs2.Stream.CompileOps.toVector]]
  * otherwise.
  *
  * == Piping ==
  * The [[syntax.ProcessNodeOutputRedirect]] class defined two extension methods for piping one
  * process to another: [[syntax.ProcessNodeOutputRedirect.|]] for direct piping and
  * [[syntax.ProcessNodeOutputRedirect.via]] for piping through a custom fs2 [[fs2.Pipe]].
  *
  * The result of the piping operators is a [[PipedProcess]], which can be used exactly as a
  * simple process, but its [[syntax.ProcessOps.start]] method returns a tuple of
  * [[RunningProcess]] instances.
  *
  * @author Daniel Vigovszky
  */
package object prox {
}
