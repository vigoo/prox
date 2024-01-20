package io.github.vigoo.prox.tests.fs2

import io.github.vigoo.prox.ProxFS2
import zio.interop.catz.*
import zio.test.*
import zio.{Task, ZIO}

import java.io.File

trait ProxSpecHelpers {

  def proxTest(label: String)(
      assertion: ProxFS2[Task] => ZIO[Any, Throwable, TestResult]
  ): Spec[Any, scala.Throwable] = {
    test(label) {
      ZIO.runtime[Any].flatMap { implicit env =>
        assertion(ProxFS2[Task])
      }
    }
  }

  def withTempFile[A](
      inner: File => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(File.createTempFile("test", "txt"))
    )(file => ZIO.attempt(file.delete()).orDie)(inner)

}
