package io.github.vigoo.prox

import zio.duration._
import zio.test._
import zio.test.Assertion._
import _root_.io.github.vigoo.prox.syntax._
import _root_.io.github.vigoo.prox.syntax.catsInterpolation._
import cats.effect._

import scala.concurrent.ExecutionContext

object InterpolatorSpecs {
  val testSuite = suite("Process interpolators")(
    suite("cats-effect process interpolator")(
      test("works with single-word process names") {
        implicit val cs: ContextShift[IO] = cats.effect.IO.contextShift(ExecutionContext.global)
        val process = proc"ls"

        assert(process.command, equalTo("ls")) && assert(process.arguments, isEmpty)
      },

      test("works with interpolated process name") {
        implicit val cs: ContextShift[IO] = cats.effect.IO.contextShift(ExecutionContext.global)
        val cmd = "ls"
        val process = proc"$cmd"

        assert(process.command, equalTo("ls")) && assert(process.arguments, isEmpty)
      },

      test("works with static parameters") {
        implicit val cs: ContextShift[IO] = cats.effect.IO.contextShift(ExecutionContext.global)
        val process = proc"ls -hal tmp"

        assert(process.command, equalTo("ls")) && assert(process.arguments, equalTo(List("-hal", "tmp")))
      },

      test("works with static parameters and interpolated process name") {
        implicit val cs: ContextShift[IO] = cats.effect.IO.contextShift(ExecutionContext.global)
        val cmd = "ls"
        val process = proc"$cmd -hal tmp"

        assert(process.command, equalTo("ls")) && assert(process.arguments, equalTo(List("-hal", "tmp")))
      },

      test("works with static process name and interpolated parameters") {
        implicit val cs: ContextShift[IO] = cats.effect.IO.contextShift(ExecutionContext.global)
        val p1 = "-hal"
        val p2 = "tmp"
        val process = proc"ls $p1 $p2"

        assert(process.command, equalTo("ls")) && assert(process.arguments, equalTo(List("-hal", "tmp")))
      },

      test("works with interpolated name and parameters") {
        implicit val cs: ContextShift[IO] = cats.effect.IO.contextShift(ExecutionContext.global)
        val cmd = "ls"
        val p1 = "-hal"
        val p2 = "tmp"
        val process = proc"$cmd $p1 $p2"

        assert(process.command, equalTo("ls")) && assert(process.arguments, equalTo(List("-hal", "tmp")))
      },

      test("works with mixed static and interpolated parameters") {
        implicit val cs: ContextShift[IO] = cats.effect.IO.contextShift(ExecutionContext.global)
        val p1 = "hello"
        val p2 = "dear visitor"
        val process = proc"echo $p1, $p2!!!"

        assert(process.command, equalTo("echo")) &&
          assert(process.arguments, equalTo(List("hello", ",", "dear visitor", "!!!")))
      }
    )
  )
}

object InterpolatorSpec extends DefaultRunnableSpec(
  InterpolatorSpecs.testSuite
)

