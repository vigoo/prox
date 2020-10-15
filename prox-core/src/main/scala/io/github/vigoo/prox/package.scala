package io.github.vigoo

/** Provides classes to work with system processes in a type safe way.
  *
  * Refer to the <a href="/docs">user guide</a> for more information.
  *
  * A process to be executed is represented by the [[Process]] trait. Once it has finished running the results
  * are in [[ProcessResult]]. We call a group of processes attached together a process group, represented by
  * [[ProcessGroup]], its result is described by [[ProcessGroupResult]].
  *
  * Redirection of input, output and error is enabled by the [[RedirectableInput]], [[RedirectableOutput]] and
  * [[RedirectableError]] trait for single processes, and the [[RedirectableErrors]] trait for process groups.
  *
  * Processes and process groups are executed by a [[ProcessRunner]], the default implementation is called
  * [[JVMProcessRunner]].
  *
  * @author Daniel Vigovszky
  */
package object prox {
  trait Prox extends ProxRuntime
    with CommonModule
    with ProcessModule
    with ProcessGroupModule
    with RedirectionModule
    with ProcessRunnerModule
    with SyntaxModule
}
