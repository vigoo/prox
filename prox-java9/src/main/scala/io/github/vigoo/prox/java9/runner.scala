package io.github.vigoo.prox.java9

import java.lang.{Process => JvmProcess}

import cats.effect.{Concurrent, ContextShift, Sync}
import io.github.vigoo.prox._

case class JVM9ProcessInfo(pid: Long) extends JVMProcessInfo

class JVM9ProcessRunner[F[_]](implicit concurrent: Concurrent[F], contextShift: ContextShift[F])
  extends JVMProcessRunnerBase[F, JVM9ProcessInfo] {

  override protected def getProcessInfo(process: JvmProcess): F[JVM9ProcessInfo] =
    Sync[F].delay(process.pid()).map(pid => JVM9ProcessInfo(pid))
}