package io.github.vigoo.prox.java9

import java.lang.{Process => JvmProcess}

import io.github.vigoo.prox.{FailedToQueryState, Prox}

trait Java9Module {
  this: Prox =>

  case class JVM9ProcessInfo(pid: Long) extends JVMProcessInfo

  class JVM9ProcessRunner()
    extends JVMProcessRunnerBase[JVM9ProcessInfo] {

    override protected def getProcessInfo(process: JvmProcess): IO[JVM9ProcessInfo] =
      effect(process.pid(), FailedToQueryState).map(pid => JVM9ProcessInfo(pid))
  }
}