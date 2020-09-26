package io.github.vigoo.prox

import zio.ZIO
import zio.test.Assertion._
import zio.test._

object InterpolatorSpecs extends DefaultRunnableSpec with ProxSpecHelpers {
  override val spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    suite("Process interpolators")(
      suite("cats-effect process interpolator")(
        proxTest("works with single-word process names") { prox =>
          import prox._
          val process = proc"ls"

          ZIO.succeed(
            assert(process.command)(equalTo("ls")) && assert(process.arguments)(isEmpty))
        },

        proxTest("works with interpolated process name") { prox =>
          import prox._
          val cmd = "ls"
          val process = proc"$cmd"

          ZIO.succeed(
            assert(process.command)(equalTo("ls")) && assert(process.arguments)(isEmpty))
        },

        proxTest("works with static parameters") { prox =>
          import prox._
          val process = proc"ls -hal tmp"

          ZIO.succeed(
            assert(process.command)(equalTo("ls")) && assert(process.arguments)(equalTo(List("-hal", "tmp"))))
        },

        proxTest("works with static parameters and interpolated process name") { prox =>
          import prox._
          val cmd = "ls"
          val process = proc"$cmd -hal tmp"

          ZIO.succeed(
            assert(process.command)(equalTo("ls")) && assert(process.arguments)(equalTo(List("-hal", "tmp"))))
        },

        proxTest("works with static process name and interpolated parameters") { prox =>
          import prox._
          val p1 = "-hal"
          val p2 = "tmp"
          val process = proc"ls $p1 $p2"

          ZIO.succeed(
            assert(process.command)(equalTo("ls")) && assert(process.arguments)(equalTo(List("-hal", "tmp"))))
        },

        proxTest("works with interpolated name and parameters") { prox =>
          import prox._
          val cmd = "ls"
          val p1 = "-hal"
          val p2 = "tmp"
          val process = proc"$cmd $p1 $p2"

          ZIO.succeed(
            assert(process.command)(equalTo("ls")) && assert(process.arguments)(equalTo(List("-hal", "tmp"))))
        },

        proxTest("works with mixed static and interpolated parameters") { prox =>
          import prox._
          val p1 = "hello"
          val p2 = "dear visitor"
          val process = proc"echo $p1, $p2!!!"

          ZIO.succeed(
            assert(process.command)(equalTo("echo")) &&
              assert(process.arguments)(equalTo(List("hello", ",", "dear visitor", "!!!"))))
        }
      )
    )
}
