package io.github.vigoo.prox

import cats.instances.string._
import zio._
import zio.console._
import zio.interop.catz._
import zio.test._
import zio.test.Assertion._
import zio.test.environment._
import cats.effect.{Blocker, ExitCode}

object ProcessGroupSpecs extends ProxSpecHelpers {
  implicit val runner: ProcessRunner[Task] = new JVMProcessRunner

  val testSuite =
    suite("Piping processes together")(
      proxTest("is possible with two") { blocker =>
        // TODO: processgroup output redirection

//        val processGroup = (Process[Task]("echo", List("This is a test string")) | Process[Task]("wc", List("-w"))) // ># fs2.text.utf8Decode
//        val program = processGroup.run(blocker).map(_.output.trim)
//
//        assertM(program, equalTo("5"))
        assertM(ZIO.succeed(0), equalTo(1))
      },

      proxTest("is possible with multiple") { blocker =>
        // TODO: processgroup output redirection

//        val processGroup = (Process[Task]("echo", List("cat\ncat\ndog\napple")) | Process[Task]("sort") | (Process[Task]("uniq", List("-c")) // ># fs2.text.utf8Decode
//        val program = processGroup.run(blocker).map(_.output..map(_.trim))))
//
//        assertM(program, equalTo(List("1 apple", "2 cat", "1 dog")))

        assertM(ZIO.succeed(0), equalTo(1))
      },

      proxTest("is customizable with pipes") { blocker =>
        // TODO: piping customization support
        // TODO: processgroup output redirection
//
//        val customPipe: fs2.Pipe[Task, Byte, Byte] =
//          (s: fs2.Stream[Task, Byte]) => s
//            .through(fs2.text.utf8Decode)
//            .through(fs2.text.lines)
//            .map(_.split(' ').toVector)
//            .map(v => v.map(_ + " !!!").mkString(" "))
//            .intersperse("\n")
//            .through(fs2.text.utf8Encode)
//
//        val program = (Process[Task]("echo", List("This is a test string")).via(customPipe).to(Process[Task]("wc", List("-w")) // ># fs2.text.utf8Decode
//        val program = processGroup.run(blocker).map(_.output.trim)

//        assertM(program, equalTo("11"))
        assertM(ZIO.succeed(0), equalTo(1))
      }
    )
}

object ProcessGroupSpec extends DefaultRunnableSpec(ProcessGroupSpecs.testSuite)
