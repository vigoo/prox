package io.github.vigoo.prox.tests.zstream

import java.io.File

import io.github.vigoo.prox.{ProxError, UnknownProxError}
import zio.ZIO
import zio.blocking.Blocking

trait ProxSpecHelpers {

  def withTempFile[A](inner: File => ZIO[Blocking, ProxError, A]): ZIO[Blocking, ProxError, A] =
    ZIO.effect(File.createTempFile("test", "txt"))
      .mapError(UnknownProxError.apply)
      .bracket(
        file => ZIO.effect(file.delete()).orDie,
        inner)

}
