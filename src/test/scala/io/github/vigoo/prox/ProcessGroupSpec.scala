package io.github.vigoo.prox

import java.nio.file.Files

import cats.instances.string._
import zio._
import zio.console._
import zio.interop.catz._
import zio.test._
import zio.test.Assertion._
import zio.test.environment._
import cats.effect.{Blocker, ExitCode}
import io.github.vigoo.prox.syntax._

object ProcessGroupSpecs extends ProxSpecHelpers {
  implicit val runner: ProcessRunner[Task] = new JVMProcessRunner

  val testSuite =
    suite("Piping processes together")(
      proxTest("is possible with two") { blocker =>
        val processGroup = (Process[Task]("echo", List("This is a test string")) | Process[Task]("wc", List("-w"))) ># fs2.text.utf8Decode
        val program = processGroup.run(blocker).map(_.output.trim)

        assertM(program, equalTo("5"))
      },

      proxTest("is possible with multiple") { blocker =>
        val processGroup = (
          Process[Task]("echo", List("cat\ncat\ndog\napple")) |
            Process[Task]("sort") |
            Process[Task]("uniq", List("-c"))
          ) >? fs2.text.utf8Decode.andThen(_.through(fs2.text.lines))

        val program = processGroup.run(blocker).map(
          r => r.output.map(_.stripLineEnd.trim).filter(_.nonEmpty)
        )

        assertM(program, hasSameElements(List("1 apple", "2 cat", "1 dog")))
      },

      proxTest("can be fed with an input stream") { blocker =>
        val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
        val processGroup = (Process[Task]("cat") | Process[Task]("wc", List("-w"))) < stream ># fs2.text.utf8Decode
        val program = processGroup.run(blocker).map(_.output.trim)

        assertM(program, equalTo("5"))
      },

      proxTest("can be fed with an input file") { blocker =>
        withTempFile { tempFile =>
          val program = for {
            _ <- ZIO(Files.write(tempFile.toPath, "This is a test string".getBytes("UTF-8")))
            processGroup = (Process[Task]("cat") | Process[Task]("wc", List("-w"))) < tempFile.toPath ># fs2.text.utf8Decode
            result <- processGroup.run(blocker)
          } yield result.output.trim

          assertM(program, equalTo("5"))
        }
      },

      proxTest("is customizable with pipes") { blocker =>

        val customPipe: fs2.Pipe[Task, Byte, Byte] =
          (s: fs2.Stream[Task, Byte]) => s
            .through(fs2.text.utf8Decode)
            .through(fs2.text.lines)
            .map(_.split(' ').toVector)
            .map(v => v.map(_ + " !!!").mkString(" "))
            .intersperse("\n")
            .through(fs2.text.utf8Encode)

        val processGroup = (Process[Task]("echo", List("This is a test string")).via(customPipe).to(Process[Task]("wc", List("-w")))) ># fs2.text.utf8Decode
        val program = processGroup.run(blocker).map(_.output.trim)

        assertM(program, equalTo("11"))
      },

      proxTest("can redirect each error output to a stream") { blocker =>
        val p1 = Process[Task]("perl", List("-e", """print STDERR "Hello""""))
        val p2 = Process[Task]("perl", List("-e", """print STDERR "world""""))
        val processGroup = (p1 | p2) !># fs2.text.utf8Decode
        val program = processGroup.run(blocker)

        program.map { result =>
          assert(result.errors.get(p1), isSome(equalTo("Hello"))) &&
          assert(result.errors.get(p2), isSome(equalTo("world"))) &&
          assert(result.output, equalTo(())) &&
          assert(result.exitCodes.get(p1), isSome(equalTo(ExitCode(0)))) &&
          assert(result.exitCodes.get(p2), isSome(equalTo(ExitCode(0))))
        }
      },

      testM("bound process is not pipeable") {
        assertM(
          typeCheck("""val bad = (Process[Task]("echo", List("Hello world")) ># fs2.text.utf8Decode) | Process[Task]("wc", List("-w"))"""),
          isLeft(anything)
        )
      }
    )
}

object ProcessGroupSpec extends DefaultRunnableSpec(ProcessGroupSpecs.testSuite)
