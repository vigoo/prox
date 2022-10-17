package io.github.vigoo.prox.tests.fs2

import java.nio.file.Files

import cats.effect.ExitCode
import zio.clock.Clock
import zio.duration._
import zio.test.Assertion.{anything, equalTo, hasSameElements, isLeft}
import zio.test.TestAspect._
import zio.test._
import zio.{IO, Task, ZIO}

object ProcessSpecs extends DefaultRunnableSpec with ProxSpecHelpers {
  override val spec =
    suite("Executing a process")(
      proxTest("returns the exit code") { prox =>
        import prox._

        implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

        val program = for {
          trueResult <- Process("true").run()
          falseResult <- Process("false").run()
        } yield (trueResult.exitCode, falseResult.exitCode)

        assertM(program)(equalTo((ExitCode(0), ExitCode(1))))
      },

      suite("Output redirection")(
        proxTest("can redirect output to a file") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          withTempFile { tempFile =>
            val process = Process("echo", List("Hello world!")) > tempFile.toPath
            val program = for {
              _ <- process.run()
              contents <- fs2.io.file.readAll[Task](tempFile.toPath, prox.blocker, 1024).through(fs2.text.utf8Decode).compile.foldMonoid
            } yield contents

            assertM(program)(equalTo("Hello world!\n"))
          }
        },

        proxTest("can redirect output to append a file") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          withTempFile { tempFile =>
            val process1 = Process("echo", List("Hello")) > tempFile.toPath
            val process2 = Process("echo", List("world")) >> tempFile.toPath
            val program = for {
              _ <- process1.run()
              _ <- process2.run()
              contents <- fs2.io.file.readAll[Task](tempFile.toPath, prox.blocker, 1024).through(fs2.text.utf8Decode).compile.foldMonoid
            } yield contents

            assertM(program)(equalTo("Hello\nworld\n"))
          }
        },

        proxTest("can redirect output to stream") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("echo", List("Hello world!")) ># fs2.text.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world!\n"))
        },

        proxTest("can redirect output to stream folding monoid") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("echo", List("Hello\nworld!")) ># fs2.text.utf8Decode.andThen(fs2.text.lines)
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Helloworld!"))
        },

        proxTest("can redirect output to stream collected to vector") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          case class StringLength(value: Int)

          val stream = fs2.text.utf8Decode[Task]
            .andThen(fs2.text.lines)
            .andThen(_.map(s => StringLength(s.length)))
          val process = Process("echo", List("Hello\nworld!")) >? stream
          val program = process.run().map(_.output)

          assertM(program)(hasSameElements(List(StringLength(5), StringLength(6), StringLength(0))))
        },

        proxTest("can redirect output to stream and ignore it's result") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("echo", List("Hello\nworld!")).drainOutput(fs2.text.utf8Decode.andThen(fs2.text.lines))
          val program = process.run().map(_.output)

          assertM(program)(equalTo(()))
        },

        proxTest("can redirect output to stream and fold it") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("echo", List("Hello\nworld!")).foldOutput(
            fs2.text.utf8Decode.andThen(fs2.text.lines),
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = process.run().map(_.output)

          assertM(program)(equalTo(Vector(Some('H'), Some('w'), None)))
        },

        proxTest("can redirect output to a sink") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val builder = new StringBuilder
          val target: fs2.Pipe[Task, Byte, Unit] = _.evalMap(byte => IO {
            builder.append(byte.toChar)
          }.unit)

          val process = Process("echo", List("Hello world!")) > target
          val program = process.run().map(_ => builder.toString)

          assertM(program)(equalTo("Hello world!\n"))
        },
      ),
      suite("Error redirection")(
        proxTest("can redirect error to a file") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          withTempFile { tempFile =>
            val process = Process("perl", List("-e", "print STDERR 'Hello world!'")) !> tempFile.toPath
            val program = for {
              _ <- process.run()
              contents <- fs2.io.file.readAll[Task](tempFile.toPath, prox.blocker, 1024).through(fs2.text.utf8Decode).compile.foldMonoid
            } yield contents

            assertM(program)(equalTo("Hello world!"))
          }
        },

        proxTest("can redirect error to append a file") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          withTempFile { tempFile =>
            val process1 = Process("perl", List("-e", "print STDERR Hello")) !> tempFile.toPath
            val process2 = Process("perl", List("-e", "print STDERR world")) !>> tempFile.toPath
            val program = for {
              _ <- process1.run()
              _ <- process2.run()
              contents <- fs2.io.file.readAll[Task](tempFile.toPath, prox.blocker, 1024).through(fs2.text.utf8Decode).compile.foldMonoid
            } yield contents

            assertM(program)(equalTo("Helloworld"))
          }
        },

        proxTest("can redirect error to stream") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("perl", List("-e", """print STDERR "Hello"""")) !># fs2.text.utf8Decode
          val program = process.run().map(_.error)

          assertM(program)(equalTo("Hello"))
        },

        proxTest("can redirect error to stream folding monoid") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")) !># fs2.text.utf8Decode.andThen(fs2.text.lines)
          val program = process.run().map(_.error)

          assertM(program)(equalTo("Helloworld!"))
        },

        proxTest("can redirect error to stream collected to vector") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          case class StringLength(value: Int)

          val stream = fs2.text.utf8Decode[Task]
            .andThen(fs2.text.lines)
            .andThen(_.map(s => StringLength(s.length)))
          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")) !>? stream
          val program = process.run().map(_.error)

          assertM(program)(hasSameElements(List(StringLength(5), StringLength(6))))
        },

        proxTest("can redirect error to stream and ignore it's result") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")).drainError(fs2.text.utf8Decode.andThen(fs2.text.lines))
          val program = process.run().map(_.error)

          assertM(program)(equalTo(()))
        },

        proxTest("can redirect error to stream and fold it") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")).foldError(
            fs2.text.utf8Decode.andThen(fs2.text.lines),
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = process.run().map(_.error)

          assertM(program)(equalTo(Vector(Some('H'), Some('w'))))
        },

        proxTest("can redirect error to a sink") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val builder = new StringBuilder
          val target: fs2.Pipe[Task, Byte, Unit] = _.evalMap(byte => IO {
            builder.append(byte.toChar)
          }.unit)

          val process = Process("perl", List("-e", """print STDERR "Hello"""")) !> target
          val program = process.run().map(_ => builder.toString)

          assertM(program)(equalTo("Hello"))
        },
      ),

      suite("Redirection ordering")(
        proxTest("can redirect first input and then error to stream") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val source = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val process = Process("perl", List("-e", """my $str = <>; print STDERR "$str"""".stripMargin)) < source !># fs2.text.utf8Decode
          val program = process.run().map(_.error)

          assertM(program)(equalTo("This is a test string"))
        },

        proxTest("can redirect error first then output to stream") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = (Process("perl", List("-e", """print STDOUT Hello; print STDERR World""".stripMargin)) !># fs2.text.utf8Decode) ># fs2.text.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertM(program)(equalTo("HelloWorld"))
        },

        proxTest("can redirect output first then error to stream") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = (Process("perl", List("-e", """print STDOUT Hello; print STDERR World""".stripMargin)) ># fs2.text.utf8Decode) !># fs2.text.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertM(program)(equalTo("HelloWorld"))
        },

        proxTest("can redirect output first then error finally input to stream") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val source = fs2.Stream("Hello").through(fs2.text.utf8Encode)
          val process = ((Process("perl", List("-e", """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin))
            ># fs2.text.utf8Decode)
            !># fs2.text.utf8Decode) < source
          val program = process.run().map(r => r.output + r.error)

          assertM(program)(equalTo("HelloWorld"))
        },

        proxTest("can redirect output first then input finally error to stream") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val source = fs2.Stream("Hello").through(fs2.text.utf8Encode)
          val process = ((Process("perl", List("-e", """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin))
            ># fs2.text.utf8Decode)
            < source) !># fs2.text.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertM(program)(equalTo("HelloWorld"))
        },

        proxTest("can redirect input first then error finally output to stream") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val source = fs2.Stream("Hello").through(fs2.text.utf8Encode)
          val process = ((Process("perl", List("-e", """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin))
            < source)
            !># fs2.text.utf8Decode) ># fs2.text.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertM(program)(equalTo("HelloWorld"))
        },
      ),

      suite("Input redirection")(
        proxTest("can use stream as input") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val source = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val process = Process("wc", List("-w")) < source ># fs2.text.utf8Decode
          val program = process.run().map(_.output.trim)

          assertM(program)(equalTo("5"))
        },

        proxTest("can use stream as input flushing after each chunk") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val source = fs2.Stream("This ", "is a test", " string").through(fs2.text.utf8Encode)
          val process = (Process("wc", List("-w")) !< source) ># fs2.text.utf8Decode
          val program = process.run().map(_.output.trim)

          assertM(program)(equalTo("5"))
        },
      ),

      suite("Termination")(
        proxTest("can be terminated with cancellation") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0"""))
          val program = process.start().use { fiber => ZIO(Thread.sleep(250)) *> fiber.cancel }

          assertM(program)(equalTo(()))
        } @@ TestAspect.timeout(5.seconds) @@ TestAspect.ignore,


        proxTest("can be terminated by releasing the resource") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0"""))
          val program = process.start().use { _ => ZIO(Thread.sleep(250)) }

          assertM(program)(equalTo(()))
        } @@ TestAspect.timeout(5.seconds) @@ TestAspect.ignore,

        proxTest[Clock, Throwable]("can be terminated") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0"""))
          val program = for {
            runningProcess <- process.startProcess()
            _ <- ZIO(Thread.sleep(250))
            result <- runningProcess.terminate()
          } yield result.exitCode

          assertM(program)(equalTo(ExitCode(1)))
        },

        proxTest("can be killed") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("perl", List("-e", """$SIG{TERM} = 'IGNORE'; sleep 30; exit 2"""))
          val program = for {
            runningProcess <- process.startProcess()
            _ <- ZIO(Thread.sleep(250))
            result <- runningProcess.kill()
          } yield result.exitCode

          assertM(program)(equalTo(ExitCode(137)))
        },

        proxTest("can be checked if is alive") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = Process("sleep", List("10"))
          val program = for {
            runningProcess <- process.startProcess()
            isAliveBefore <- runningProcess.isAlive
            _ <- runningProcess.terminate()
            isAliveAfter <- runningProcess.isAlive
          } yield (isAliveBefore, isAliveAfter)

          assertM(program)(equalTo((true, false)))
        },
      ),

      suite("Customization")(
        proxTest("can change the command") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("something", List("Hello", "world")) ># fs2.text.utf8Decode
          val p2 = p1.withCommand("echo")
          val program = p2.run().map(_.output)

          assertM(program)(equalTo("Hello world\n"))
        },

        proxTest("can change the arguments") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("echo") ># fs2.text.utf8Decode
          val p2 = p1.withArguments(List("Hello", "world"))
          val program = p2.run().map(_.output)

          assertM(program)(equalTo("Hello world\n"))
        },

        proxTest("respects the working directory") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          ZIO(Files.createTempDirectory("prox")).flatMap { tempDirectory =>
            val process = (Process("pwd") in tempDirectory) ># fs2.text.utf8Decode
            val program = process.run().map(_.output.trim)

            assertM(program)(equalTo(tempDirectory.toString) || equalTo(s"/private${tempDirectory}"))
          }
        },

        proxTest("is customizable with environment variables") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = (Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\""))
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># fs2.text.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        proxTest("is customizable with excluded environment variables") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = (Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\""))
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")
            `without` "TEST1") ># fs2.text.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello ! I am prox!\n"))
        },

        proxTest("is customizable with environment variables output is bound") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = (Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")) ># fs2.text.utf8Decode
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        proxTest("is customizable with environment variables if input is bound") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val source = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val process = ((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># fs2.text.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        proxTest("is customizable with environment variables if error is bound") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val source = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val process = ((Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")) !># fs2.text.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># fs2.text.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        proxTest("is customizable with environment variables if input and output are bound") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val source = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val process = (((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source) ># fs2.text.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },


        proxTest("is customizable with environment variables if input and error are bound") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val source = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val process = (((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source) !># fs2.text.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># fs2.text.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        proxTest("is customizable with environment variables if output and error are bound") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val process = (((Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")) !># fs2.text.utf8Decode) ># fs2.text.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        proxTest("is customizable with environment variables if everything is bound") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val source = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val process = ((((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source) !># fs2.text.utf8Decode) ># fs2.text.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },
      ),

      testM("double output redirect is illegal") {
        assertM(
          typeCheck("""val bad = Process("echo", List("Hello world")) > new File("x").toPath > new File("y").toPath"""))(
          isLeft(anything)
        )
      },
      testM("double error redirect is illegal") {
        assertM(
          typeCheck("""val bad = Process("echo", List("Hello world")) !> new File("x").toPath !> new File("y").toPath"""))(
          isLeft(anything)
        )
      },
      testM("double input redirect is illegal") {
        assertM(
          typeCheck("""val bad = (Process("echo", List("Hello world")) < fs2.Stream("X").through(fs2.text.utf8Encode)) < fs2.Stream("Y").through(fs2.text.utf8Encode)"""))(
          isLeft(anything)
        )
      }
    ) @@ timeout(60.seconds) @@ sequential
}
