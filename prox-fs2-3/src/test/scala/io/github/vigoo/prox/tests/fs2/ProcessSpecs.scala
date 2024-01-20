package io.github.vigoo.prox.tests.fs2

import cats.effect.ExitCode
import fs2.io.file.{Files, Flags}
import zio.interop.catz.*
import zio.test.Assertion.{anything, equalTo, hasSameElements, isLeft}
import zio.test.TestAspect.*
import zio.test.*
import zio.{Scope, Task, ZIO, durationInt}

object ProcessSpecs extends ZIOSpecDefault with ProxSpecHelpers {
  override val spec: Spec[TestEnvironment & Scope, Any] =
    suite("Executing a process")(
      proxTest("returns the exit code") { prox =>
        import prox.*

        implicit val processRunner: ProcessRunner[JVMProcessInfo] =
          new JVMProcessRunner

        val program = for {
          trueResult <- Process("true").run()
          falseResult <- Process("false").run()
        } yield (trueResult.exitCode, falseResult.exitCode)

        program.map(r => assertTrue(r == (ExitCode(0), ExitCode(1))))
      },
      suite("Output redirection")(
        proxTest("can redirect output to a file") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          withTempFile { tempFile =>
            val process =
              Process("echo", List("Hello world!")) > tempFile.toPath
            val program = for {
              _ <- process.run()
              contents <- Files
                .forAsync[Task]
                .readAll(
                  fs2.io.file.Path.fromNioPath(tempFile.toPath),
                  1024,
                  Flags.Read
                )
                .through(fs2.text.utf8.decode)
                .compile
                .foldMonoid
            } yield contents

            program.map(r => assertTrue(r == "Hello world!\n"))
          }
        },
        proxTest("can redirect output to append a file") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          withTempFile { tempFile =>
            val process1 = Process("echo", List("Hello")) > tempFile.toPath
            val process2 = Process("echo", List("world")) >> tempFile.toPath
            val program = for {
              _ <- process1.run()
              _ <- process2.run()
              contents <- Files
                .forAsync[Task]
                .readAll(
                  fs2.io.file.Path.fromNioPath(tempFile.toPath),
                  1024,
                  Flags.Read
                )
                .through(fs2.text.utf8.decode)
                .compile
                .foldMonoid
            } yield contents

            program.map(r => assertTrue(r == "Hello\nworld\n"))
          }
        },
        proxTest("can redirect output to stream") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process =
            Process("echo", List("Hello world!")) ># fs2.text.utf8.decode
          val program = process.run().map(_.output)

          program.map(r => assertTrue(r == "Hello world!\n"))
        },
        proxTest("can redirect output to stream folding monoid") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = Process(
            "echo",
            List("Hello\nworld!")
          ) ># fs2.text.utf8.decode.andThen(fs2.text.lines)
          val program = process.run().map(_.output)

          program.map(r => assertTrue(r == "Helloworld!"))
        },
        proxTest("can redirect output to stream collected to vector") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          case class StringLength(value: Int)

          val stream = fs2.text.utf8
            .decode[Task]
            .andThen(fs2.text.lines)
            .andThen(_.map(s => StringLength(s.length)))
          val process = Process("echo", List("Hello\nworld!")) >? stream
          val program = process.run().map(_.output)

          program.map(r =>
            assert(r)(
              hasSameElements(
                List(StringLength(5), StringLength(6), StringLength(0))
              )
            )
          )
        },
        proxTest("can redirect output to stream and ignore it's result") {
          prox =>
            import prox.*

            implicit val processRunner: ProcessRunner[JVMProcessInfo] =
              new JVMProcessRunner

            val process = Process("echo", List("Hello\nworld!")).drainOutput(
              fs2.text.utf8.decode.andThen(fs2.text.lines)
            )
            val program = process.run().map(_.output)

            program.as(assertCompletes)
        },
        proxTest("can redirect output to stream and fold it") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = Process("echo", List("Hello\nworld!")).foldOutput(
            fs2.text.utf8.decode.andThen(fs2.text.lines),
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = process.run().map(_.output)

          program.map(r => assertTrue(r == Vector(Some('H'), Some('w'), None)))
        },
        proxTest("can redirect output to a sink") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val builder = new StringBuilder
          val target: fs2.Pipe[Task, Byte, Unit] = _.evalMap(byte =>
            ZIO.attempt {
              builder.append(byte.toChar)
            }.unit
          )

          val process = Process("echo", List("Hello world!")) > target
          val program = process.run().as(builder.toString)

          program.map(r => assertTrue(r == "Hello world!\n"))
        }
      ),
      suite("Error redirection")(
        proxTest("can redirect error to a file") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          withTempFile { tempFile =>
            val process = Process(
              "perl",
              List("-e", "print STDERR 'Hello world!'")
            ) !> tempFile.toPath
            val program = for {
              _ <- process.run()
              contents <- Files
                .forAsync[Task]
                .readAll(
                  fs2.io.file.Path.fromNioPath(tempFile.toPath),
                  1024,
                  Flags.Read
                )
                .through(fs2.text.utf8.decode)
                .compile
                .foldMonoid
            } yield contents

            program.map(r => assertTrue(r == "Hello world!"))
          }
        },
        proxTest("can redirect error to append a file") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          withTempFile { tempFile =>
            val process1 = Process(
              "perl",
              List("-e", "print STDERR Hello")
            ) !> tempFile.toPath
            val process2 = Process(
              "perl",
              List("-e", "print STDERR world")
            ) !>> tempFile.toPath
            val program = for {
              _ <- process1.run()
              _ <- process2.run()
              contents <- Files
                .forAsync[Task]
                .readAll(
                  fs2.io.file.Path.fromNioPath(tempFile.toPath),
                  1024,
                  Flags.Read
                )
                .through(fs2.text.utf8.decode)
                .compile
                .foldMonoid
            } yield contents

            program.map(r => assertTrue(r == "Helloworld"))
          }
        },
        proxTest("can redirect error to stream") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = Process(
            "perl",
            List("-e", """print STDERR "Hello"""")
          ) !># fs2.text.utf8.decode
          val program = process.run().map(_.error)

          program.map(r => assertTrue(r == "Hello"))
        },
        proxTest("can redirect error to stream folding monoid") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = Process(
            "perl",
            List("-e", "print STDERR 'Hello\nworld!'")
          ) !># fs2.text.utf8.decode.andThen(fs2.text.lines)
          val program = process.run().map(_.error)

          program.map(r => assertTrue(r == "Helloworld!"))
        },
        proxTest("can redirect error to stream collected to vector") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          case class StringLength(value: Int)

          val stream = fs2.text.utf8
            .decode[Task]
            .andThen(fs2.text.lines)
            .andThen(_.map(s => StringLength(s.length)))
          val process = Process(
            "perl",
            List("-e", "print STDERR 'Hello\nworld!'")
          ) !>? stream
          val program = process.run().map(_.error)

          program.map(r =>
            assert(r)(hasSameElements(List(StringLength(5), StringLength(6))))
          )
        },
        proxTest("can redirect error to stream and ignore it's result") {
          prox =>
            import prox.*

            implicit val processRunner: ProcessRunner[JVMProcessInfo] =
              new JVMProcessRunner

            val process =
              Process("perl", List("-e", "print STDERR 'Hello\nworld!'"))
                .drainError(fs2.text.utf8.decode.andThen(fs2.text.lines))
            val program = process.run().map(_.error)

            program.as(assertCompletes)
        },
        proxTest("can redirect error to stream and fold it") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = Process(
            "perl",
            List("-e", "print STDERR 'Hello\nworld!'")
          ).foldError(
            fs2.text.utf8.decode.andThen(fs2.text.lines),
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = process.run().map(_.error)

          program.map(r => assertTrue(r == Vector(Some('H'), Some('w'))))
        },
        proxTest("can redirect error to a sink") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val builder = new StringBuilder
          val target: fs2.Pipe[Task, Byte, Unit] = _.evalMap(byte =>
            ZIO.attempt {
              builder.append(byte.toChar)
            }.unit
          )

          val process =
            Process("perl", List("-e", """print STDERR "Hello"""")) !> target
          val program = process.run().as(builder.toString)

          program.map(r => assertTrue(r == "Hello"))
        }
      ),
      suite("Redirection ordering")(
        proxTest("can redirect first input and then error to stream") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val source =
            fs2.Stream("This is a test string").through(fs2.text.utf8.encode)
          val process = Process(
            "perl",
            List("-e", """my $str = <>; print STDERR "$str"""".stripMargin)
          ) < source !># fs2.text.utf8.decode
          val program = process.run().map(_.error)

          program.map(r => assertTrue(r == "This is a test string"))
        },
        proxTest("can redirect error first then output to stream") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = (Process(
            "perl",
            List("-e", """print STDOUT Hello; print STDERR World""".stripMargin)
          ) !># fs2.text.utf8.decode) ># fs2.text.utf8.decode
          val program = process.run().map(r => r.output + r.error)

          program.map(r => assertTrue(r == "HelloWorld"))
        },
        proxTest("can redirect output first then error to stream") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = (Process(
            "perl",
            List("-e", """print STDOUT Hello; print STDERR World""".stripMargin)
          ) ># fs2.text.utf8.decode) !># fs2.text.utf8.decode
          val program = process.run().map(r => r.output + r.error)

          program.map(r => assertTrue(r == "HelloWorld"))
        },
        proxTest(
          "can redirect output first then error finally input to stream"
        ) { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val source = fs2.Stream("Hello").through(fs2.text.utf8.encode)
          val process = ((Process(
            "perl",
            List(
              "-e",
              """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin
            )
          )
            ># fs2.text.utf8.decode)
            !># fs2.text.utf8.decode) < source
          val program = process.run().map(r => r.output + r.error)

          program.map(r => assertTrue(r == "HelloWorld"))
        },
        proxTest(
          "can redirect output first then input finally error to stream"
        ) { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val source = fs2.Stream("Hello").through(fs2.text.utf8.encode)
          val process = ((Process(
            "perl",
            List(
              "-e",
              """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin
            )
          )
            ># fs2.text.utf8.decode)
            < source) !># fs2.text.utf8.decode
          val program = process.run().map(r => r.output + r.error)

          program.map(r => assertTrue(r == "HelloWorld"))
        },
        proxTest(
          "can redirect input first then error finally output to stream"
        ) { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val source = fs2.Stream("Hello").through(fs2.text.utf8.encode)
          val process = ((Process(
            "perl",
            List(
              "-e",
              """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin
            )
          )
            < source)
            !># fs2.text.utf8.decode) ># fs2.text.utf8.decode
          val program = process.run().map(r => r.output + r.error)

          program.map(r => assertTrue(r == "HelloWorld"))
        }
      ),
      suite("Input redirection")(
        proxTest("can use stream as input") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val source =
            fs2.Stream("This is a test string").through(fs2.text.utf8.encode)
          val process =
            Process("wc", List("-w")) < source ># fs2.text.utf8.decode
          val program = process.run().map(_.output.trim)

          program.map(r => assertTrue(r == "5"))
        },
        proxTest("can use stream as input flushing after each chunk") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val source = fs2
            .Stream("This ", "is a test", " string")
            .through(fs2.text.utf8.encode)
          val process =
            (Process("wc", List("-w")) !< source) ># fs2.text.utf8.decode
          val program = process.run().map(_.output.trim)

          program.map(r => assertTrue(r == "5"))
        }
      ),
      suite("Termination")(
        proxTest("can be terminated with cancellation") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = Process(
            "perl",
            List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0""")
          )
          val program = process.start().use { fiber =>
            fiber.cancel.delay(250.millis)
          }

          program.as(assertCompletes)
        } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
        proxTest("can be terminated by releasing the resource") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = Process(
            "perl",
            List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0""")
          )
          val program = process.start().use { _ => ZIO.sleep(250.millis) }

          program.as(assertCompletes)
        } @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.seconds),
        proxTest("can be terminated") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = Process(
            "perl",
            List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0""")
          )
          val program = for {
            runningProcess <- process.startProcess()
            _ <- ZIO.sleep(250.millis)
            result <- runningProcess.terminate()
          } yield result.exitCode

          program.map(r => assertTrue(r == ExitCode(1)))
        } @@ TestAspect.withLiveClock,
        proxTest("can be killed") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = Process(
            "perl",
            List("-e", """$SIG{TERM} = 'IGNORE'; sleep 30; exit 2""")
          )
          val program = for {
            runningProcess <- process.startProcess()
            _ <- ZIO.sleep(250.millis)
            result <- runningProcess.kill()
          } yield result.exitCode

          program.map(r => assertTrue(r == ExitCode(137)))
        } @@ TestAspect.withLiveClock,
        proxTest("can be checked if is alive") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = Process("sleep", List("10"))
          val program = for {
            runningProcess <- process.startProcess()
            isAliveBefore <- runningProcess.isAlive
            _ <- runningProcess.terminate()
            isAliveAfter <- runningProcess.isAlive
          } yield (isAliveBefore, isAliveAfter)

          program.map(r => assertTrue(r == (true, false)))
        }
      ),
      suite("Customization")(
        proxTest("can change the command") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val p1 =
            Process("something", List("Hello", "world")) ># fs2.text.utf8.decode
          val p2 = p1.withCommand("echo")
          val program = p2.run().map(_.output)

          program.map(r => assertTrue(r == "Hello world\n"))
        },
        proxTest("can change the arguments") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val p1 = Process("echo") ># fs2.text.utf8.decode
          val p2 = p1.withArguments(List("Hello", "world"))
          val program = p2.run().map(_.output)

          program.map(r => assertTrue(r == "Hello world\n"))
        },
        proxTest("respects the working directory") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          ZIO.attempt(java.nio.file.Files.createTempDirectory("prox")).flatMap {
            tempDirectory =>
              val process =
                (Process("pwd") in tempDirectory) ># fs2.text.utf8.decode
              val program = process.run().map(_.output.trim)

              program.map(r =>
                assert(r)(
                  equalTo(tempDirectory.toString) || equalTo(
                    s"/private${tempDirectory}"
                  )
                )
              )
          }
        },
        proxTest("is customizable with environment variables") { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process =
            (Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\""))
              `with` ("TEST1" -> "world")
              `with` ("TEST2" -> "prox")) ># fs2.text.utf8.decode
          val program = process.run().map(_.output)

          program.map(r => assertTrue(r == "Hello world! I am prox!\n"))
        },
        proxTest("is customizable with excluded environment variables") {
          prox =>
            import prox.*

            implicit val processRunner: ProcessRunner[JVMProcessInfo] =
              new JVMProcessRunner

            val process =
              (Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\""))
                `with` ("TEST1" -> "world")
                `with` ("TEST2" -> "prox")
                `without` "TEST1") ># fs2.text.utf8.decode
            val program = process.run().map(_.output)

            program.map(r => assertTrue(r == "Hello ! I am prox!\n"))
        },
        proxTest("is customizable with environment variables output is bound") {
          prox =>
            import prox.*

            implicit val processRunner: ProcessRunner[JVMProcessInfo] =
              new JVMProcessRunner

            val process = (Process(
              "sh",
              List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")
            ) ># fs2.text.utf8.decode
              `with` ("TEST1" -> "world")
              `with` ("TEST2" -> "prox"))
            val program = process.run().map(_.output)

            program.map(r => assertTrue(r == "Hello world! I am prox!\n"))
        },
        proxTest(
          "is customizable with environment variables if input is bound"
        ) { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val source =
            fs2.Stream("This is a test string").through(fs2.text.utf8.encode)
          val process = ((Process(
            "sh",
            List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")
          ) < source)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># fs2.text.utf8.decode
          val program = process.run().map(_.output)

          program.map(r => assertTrue(r == "Hello world! I am prox!\n"))
        },
        proxTest(
          "is customizable with environment variables if error is bound"
        ) { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = ((Process(
            "sh",
            List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")
          ) !># fs2.text.utf8.decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># fs2.text.utf8.decode
          val program = process.run().map(_.output)

          program.map(r => assertTrue(r == "Hello world! I am prox!\n"))
        },
        proxTest(
          "is customizable with environment variables if input and output are bound"
        ) { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val source =
            fs2.Stream("This is a test string").through(fs2.text.utf8.encode)
          val process = (((Process(
            "sh",
            List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")
          ) < source) ># fs2.text.utf8.decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          program.map(r => assertTrue(r == "Hello world! I am prox!\n"))
        },
        proxTest(
          "is customizable with environment variables if input and error are bound"
        ) { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val source =
            fs2.Stream("This is a test string").through(fs2.text.utf8.encode)
          val process = (((Process(
            "sh",
            List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")
          ) < source) !># fs2.text.utf8.decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># fs2.text.utf8.decode
          val program = process.run().map(_.output)

          program.map(r => assertTrue(r == "Hello world! I am prox!\n"))
        },
        proxTest(
          "is customizable with environment variables if output and error are bound"
        ) { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val process = (((Process(
            "sh",
            List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")
          ) !># fs2.text.utf8.decode) ># fs2.text.utf8.decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          program.map(r => assertTrue(r == "Hello world! I am prox!\n"))
        },
        proxTest(
          "is customizable with environment variables if everything is bound"
        ) { prox =>
          import prox.*

          implicit val processRunner: ProcessRunner[JVMProcessInfo] =
            new JVMProcessRunner

          val source =
            fs2.Stream("This is a test string").through(fs2.text.utf8.encode)
          val process = ((((Process(
            "sh",
            List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")
          ) < source) !># fs2.text.utf8.decode) ># fs2.text.utf8.decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          program.map(r => assertTrue(r == "Hello world! I am prox!\n"))
        }
      ),
      test("double output redirect is illegal") {
        typeCheck(
          """val bad = Process("echo", List("Hello world")) > new File("x").toPath > new File("y").toPath"""
        ).map(r =>
          assert(r)(
            isLeft(anything)
          )
        )
      },
      test("double error redirect is illegal") {
        typeCheck(
          """val bad = Process("echo", List("Hello world")) !> new File("x").toPath !> new File("y").toPath"""
        ).map(r =>
          assert(r)(
            isLeft(anything)
          )
        )
      },
      test("double input redirect is illegal") {
        typeCheck(
          """val bad = (Process("echo", List("Hello world")) < fs2.Stream("X").through(fs2.text.utf8.encode)) < fs2.Stream("Y").through(fs2.text.utf8.encode)"""
        ).map(r =>
          assert(r)(
            isLeft(anything)
          )
        )
      }
    ) @@ timeout(60.seconds) @@ sequential
}
