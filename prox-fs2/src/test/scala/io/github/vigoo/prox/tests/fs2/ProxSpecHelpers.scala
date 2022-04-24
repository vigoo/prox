package io.github.vigoo.prox.tests.fs2

import java.io.File

import cats.effect.Blocker
import io.github.vigoo.prox.ProxFS2
import zio.interop.catz._
import zio.test.{TestResult, ZSpec, testM}
import zio.{Task, URIO}

trait ProxSpecHelpers {

  def proxTest[Nothing, Throwable](label: String)(assertion: ProxFS2[Task] => Task[TestResult]): ZSpec[Any, scala.Throwable] = {
    testM(label)(Blocker[Task].use { blocker =>
      val prox = ProxFS2[Task](blocker)
      assertion(prox)
    })
  }

  def withTempFile[A](inner: File => Task[A]): Task[A] =
    Task(File.createTempFile("test", "txt")).bracket(
      file => URIO(file.delete()),
      inner)

}
