package io.github.vigoo.prox.tests.fs2

import java.io.File

import io.github.vigoo.prox.ProxFS2
import zio.interop.catz._
import zio.test.{TestResult, ZSpec, testM}
import zio.{ZIO, Task, URIO, ZEnv}
import zio.RIO

trait ProxSpecHelpers {

  def proxTest(label: String)(assertion: ProxFS2[Task] => RIO[ZEnv, TestResult]): ZSpec[ZEnv, scala.Throwable] = {
    testM(label){
      ZIO.runtime[ZEnv].flatMap { implicit env =>
        assertion(ProxFS2[Task])
      }
    }
  }

  def withTempFile[R, A](inner: File => RIO[R, A]): RIO[R, A] =
    Task(File.createTempFile("test", "txt")).bracket(
      file => URIO(file.delete()),
      inner)

}
