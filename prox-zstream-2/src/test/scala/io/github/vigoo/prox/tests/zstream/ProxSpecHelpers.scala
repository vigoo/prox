package io.github.vigoo.prox.tests.zstream

import java.io.File

import io.github.vigoo.prox.{ProxError, UnknownProxError}
import zio.ZIO

trait ProxSpecHelpers {

  def withTempFile[A](inner: File => ZIO[Any, ProxError, A]): ZIO[Any, ProxError, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(File.createTempFile("test", "txt"))
      .mapError(UnknownProxError.apply)
    )(file => ZIO.attempt(file.delete()).orDie)(inner)
}
