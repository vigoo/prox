package io.github.vigoo.prox

import zio._
import zio.console._
import zio.test._
import zio.test.Assertion._
import zio.test.environment._

import TypedRedirection._

object ProcessSpec extends DefaultRunnableSpec(
  suite("Executing a process")(
    testM("returns the exit code") {
      assertM(ZIO.succeed(0), equalTo(1))
    }
  )
)
