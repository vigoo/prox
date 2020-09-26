package io.github.vigoo.prox

import java.io.File

import zio._
import zio.blocking.Blocking

trait ProxSpecHelpers {

  def withTempFile[A](inner: File => ZIO[Blocking, ProxError, A]): ZIO[Blocking, ProxError, A] =
    ZIO.effect(File.createTempFile("test", "txt"))
      .mapError(UnknownProxError)
      .bracket(
        file => ZIO.effect(file.delete()).orDie,
      inner)

}
