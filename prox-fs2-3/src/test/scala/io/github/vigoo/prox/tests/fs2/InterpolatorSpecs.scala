package io.github.vigoo.prox.tests.fs2

import zio.test.*
import zio.{Scope, ZIO}

object InterpolatorSpecs extends ZIOSpecDefault with ProxSpecHelpers {
  override val spec: Spec[TestEnvironment & Scope, Any] =
    suite("Process interpolators")(
      suite("cats-effect process interpolator")(
        proxTest("works with single-word process names") { prox =>
          import prox.*

          val process = proc"ls"

          ZIO.succeed(
            assertTrue(
              process.command == "ls",
              process.arguments.isEmpty
            )
          )
        },
        proxTest("works with interpolated process name") { prox =>
          import prox.*

          val cmd = "ls"
          val process = proc"$cmd"

          ZIO.succeed(
            assertTrue(
              process.command == "ls",
              process.arguments.isEmpty
            )
          )
        },
        proxTest("works with static parameters") { prox =>
          import prox.*

          val process = proc"ls -hal tmp"

          ZIO.succeed(
            assertTrue(
              process.command == "ls",
              process.arguments == List("-hal", "tmp")
            )
          )
        },
        proxTest("works with static parameters and interpolated process name") {
          prox =>
            import prox.*

            val cmd = "ls"
            val process = proc"$cmd -hal tmp"

            ZIO.succeed(
              assertTrue(
                process.command == "ls",
                process.arguments == List("-hal", "tmp")
              )
            )
        },
        proxTest("works with static process name and interpolated parameters") {
          prox =>
            import prox.*

            val p1 = "-hal"
            val p2 = "tmp"
            val process = proc"ls $p1 $p2"

            ZIO.succeed(
              assertTrue(
                process.command == "ls",
                process.arguments == List("-hal", "tmp")
              )
            )
        },
        proxTest("works with interpolated name and parameters") { prox =>
          import prox.*

          val cmd = "ls"
          val p1 = "-hal"
          val p2 = "tmp"
          val process = proc"$cmd $p1 $p2"

          ZIO.succeed(
            assertTrue(
              process.command == "ls",
              process.arguments == List("-hal", "tmp")
            )
          )
        },
        proxTest("works with mixed static and interpolated parameters") {
          prox =>
            import prox.*

            val p1 = "hello"
            val p2 = "dear visitor"
            val process = proc"echo $p1, $p2!!!"

            ZIO.succeed(
              assertTrue(
                process.command == "echo",
                process.arguments == List("hello", ",", "dear visitor", "!!!")
              )
            )
        }
      )
    )
}
