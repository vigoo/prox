package io.github.vigoo.prox

import java.io.File

import cats.instances.string._
import zio._
import zio.console._
import zio.interop.catz._
import zio.test._
import zio.test.Assertion._
import zio.test.environment._
import cats.effect.{Blocker, ExitCode}

object ProcessSpecs extends ProxSpecHelpers {
  implicit val runner: ProcessRunner[Task] = new JVMProcessRunner

  val testSuite =
    suite("Executing a process")(
      proxTest("returns the exit code") { blocker =>
        val program = for {
          trueResult <- Process[Task]("true", List.empty).run(blocker)
          falseResult <- Process[Task]("false", List.empty).run(blocker)
        } yield (trueResult.exitCode, falseResult.exitCode)

        assertM(program, equalTo((ExitCode(0), ExitCode(1))))
      },

      proxTest("can redirect output to a file") { blocker =>
        withTempFile { tempFile =>
          val process = Process[Task]("echo", List("Hello world!")) > tempFile.toPath
          val program = for {
            _ <- process.run(blocker)
            contents <- fs2.io.file.readAll[Task](tempFile.toPath, blocker, 1024).through(fs2.text.utf8Decode).compile.foldMonoid
          } yield contents

          assertM(program, equalTo("Hello world!\n"))
        }
      },

      proxTest("can redirect output to stream") { blocker =>
        val process = Process[Task]("echo", List("Hello world!")) ># fs2.text.utf8Decode
        val program = process.run(blocker).map(_.output)

        assertM(program, equalTo("Hello world!\n"))
      },

      proxTest("can redirect output to a sink") { blocker =>
        val builder = new StringBuilder
        val target: fs2.Pipe[Task, Byte, Unit] = _.evalMap(byte => IO { builder.append(byte.toChar) }.unit)

        val process = Process[Task]("echo", List("Hello world!")) > target
        val program = process.run(blocker).map(_ => builder.toString)

        assertM(program, equalTo("Hello world!\n"))
      },

      proxTest("can redirect error to stream") { blocker =>
        val process = Process[Task]("perl", List("-e", """print STDERR "Hello"""")) !># fs2.text.utf8Decode
        val program = process.run(blocker).map(_.error)

        assertM(program, equalTo("Hello"))
      },

      proxTest("can redirect error to a sink") { blocker =>
        val builder = new StringBuilder
        val target: fs2.Pipe[Task, Byte, Unit] = _.evalMap(byte => IO { builder.append(byte.toChar) }.unit)

        val process = Process[Task]("perl", List("-e", """print STDERR "Hello"""")) !> target
        val program = process.run(blocker).map(_ => builder.toString)

        assertM(program, equalTo("Hello"))
      },

      proxTest("can use stream as input") { blocker =>
        val source = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
        val process = Process[Task]("wc", List("-w")) < source ># fs2.text.utf8Decode
        val program = process.run(blocker).map(_.output.trim)

        assertM(program, equalTo("5"))
      },

      testM("double output redirect is illegal") {
        assertM(
          typeCheck("""val bad = Process[Task]("echo", List("Hello world")) > new File("x").toPath > new File("y").toPath"""),
          isLeft(anything)
        )
      },
      testM("double error redirect is illegal") {
        assertM(
          typeCheck("""val bad = Process[Task]("echo", List("Hello world")) !> new File("x").toPath !> new File("y").toPath"""),
          isLeft(anything)
        )
      },
      testM("double input redirect is illegal") {
        assertM(
          typeCheck("""val bad = (Process[Task]("echo", List("Hello world")) < fs2.Stream("X").through(fs2.text.utf8Encode)) < fs2.Stream("Y").through(fs2.text.utf8Encode)"""),
          isLeft(anything)
        )
      }

  )
}

object ProcessSpec extends DefaultRunnableSpec(ProcessSpecs.testSuite)
