package io.github.vigoo.prox.tests.zstream

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import io.github.vigoo.prox.{ProxError, UnknownProxError, zstream}
import io.github.vigoo.prox.zstream._
import zio._
import zio.stream.{ZSink, ZStream, ZPipeline}
import zio.test.Assertion.{anything, equalTo, hasSameElements, isLeft}
import zio.test.TestAspect._
import zio.test._
import zio.{ExitCode, ZIO}

object ProcessSpecs extends ZIOSpecDefault with ProxSpecHelpers {
  implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

  override val spec =
    suite("Executing a process")(
      test("returns the exit code") {
        val program = for {
          trueResult <- Process("true").run()
          falseResult <- Process("false").run()
        } yield (trueResult.exitCode, falseResult.exitCode)

        assertZIO(program)(equalTo((ExitCode(0), ExitCode(1))))
      },

      suite("Output redirection")(
        test("can redirect output to a file") {
          withTempFile { tempFile =>
            val process = Process("echo", List("Hello world!")) > tempFile.toPath
            val program = for {
              _ <- process.run()
              contents <- ZStream.fromFile(tempFile, 1024).via(ZPipeline.utf8Decode).runFold("")(_ + _).mapError(UnknownProxError.apply)
            } yield contents

            assertZIO(program)(equalTo("Hello world!\n"))
          }
        },

        test("can redirect output to append a file") {
          withTempFile { tempFile =>
            val process1 = Process("echo", List("Hello")) > tempFile.toPath
            val process2 = Process("echo", List("world")) >> tempFile.toPath
            val program = for {
              _ <- process1.run()
              _ <- process2.run()
              contents <- ZStream.fromFile(tempFile, 1024).via(ZPipeline.utf8Decode).runFold("")(_ + _).mapError(UnknownProxError.apply)
            } yield contents

            assertZIO(program)(equalTo("Hello\nworld\n"))
          }
        },

        test("can redirect output to stream") {
          val process = Process("echo", List("Hello world!")) ># ZPipeline.utf8Decode
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo("Hello world!\n"))
        },

        test("can redirect output to stream folding monoid") {
          val process = Process("echo", List("Hello\nworld!")) ># (ZPipeline.utf8Decode >>> ZPipeline.splitLines)
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo("Helloworld!"))
        },

        test("can redirect output to stream collected to vector") {
          case class StringLength(value: Int)

          val stream =
            ZPipeline.utf8Decode >>>
              ZPipeline.splitLines >>>
              ZPipeline.map[String, StringLength](s => StringLength(s.length))

          val process = Process("echo", List("Hello\nworld!")) >? stream
          val program = process.run().map(_.output)

          assertZIO(program)(hasSameElements(List(StringLength(5), StringLength(6))))
        },

        test("can redirect output to stream and ignore it's result") {
          val process = Process("echo", List("Hello\nworld!")).drainOutput(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo(()))
        },

        test("can redirect output to stream and fold it") {
          val process = Process("echo", List("Hello\nworld!")).foldOutput(
            ZPipeline.utf8Decode >>> ZPipeline.splitLines,
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo(Vector(Some('H'), Some('w'))))
        },

        test("can redirect output to a sink") {
          val builder = new StringBuilder
          val target: zstream.ProxSink[Byte] = ZSink.foreach((byte: Byte) => ZIO.attempt(builder.append(byte.toChar)).mapError(UnknownProxError.apply))

          val process = Process("echo", List("Hello world!")) > target
          val program = process.run().as(builder.toString)

          assertZIO(program)(equalTo("Hello world!\n"))
        },
      ),
      suite("Error redirection")(
        test("can redirect error to a file") {
          withTempFile { tempFile =>
            val process = Process("perl", List("-e", "print STDERR 'Hello world!'")) !> tempFile.toPath
            val program = for {
              _ <- process.run()
              contents <- ZStream.fromFile(tempFile, 1024).via(ZPipeline.utf8Decode).runFold("")(_ + _).mapError(UnknownProxError.apply)
            } yield contents

            assertZIO(program)(equalTo("Hello world!"))
          }
        },

        test("can redirect error to append a file") {
          withTempFile { tempFile =>
            val process1 = Process("perl", List("-e", "print STDERR Hello")) !> tempFile.toPath
            val process2 = Process("perl", List("-e", "print STDERR world")) !>> tempFile.toPath
            val program = for {
              _ <- process1.run()
              _ <- process2.run()
              contents <- ZStream.fromFile(tempFile, 1024).via(ZPipeline.utf8Decode).runFold("")(_ + _).mapError(UnknownProxError.apply)
            } yield contents

            assertZIO(program)(equalTo("Helloworld"))
          }
        },

        test("can redirect error to stream") {
          val process = Process("perl", List("-e", """print STDERR "Hello"""")) !># ZPipeline.utf8Decode
          val program = process.run().map(_.error)

          assertZIO(program)(equalTo("Hello"))
        },

        test("can redirect error to stream folding monoid") {
          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")) !># (ZPipeline.utf8Decode >>> ZPipeline.splitLines)
          val program = process.run().map(_.error)

          assertZIO(program)(equalTo("Helloworld!"))
        },

        test("can redirect error to stream collected to vector") {
          case class StringLength(value: Int)

          val stream =
            ZPipeline.utf8Decode >>>
              ZPipeline.splitLines >>>
              ZPipeline.map[String, StringLength](s => StringLength(s.length))

          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")) !>? stream
          val program = process.run().map(_.error)

          assertZIO(program)(hasSameElements(List(StringLength(5), StringLength(6))))
        },

        test("can redirect error to stream and ignore it's result") {
          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")).drainError(ZPipeline.utf8Decode >>> ZPipeline.splitLines)
          val program = process.run().map(_.error)

          assertZIO(program)(equalTo(()))
        },

        test("can redirect error to stream and fold it") {
          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")).foldError(
            ZPipeline.utf8Decode >>> ZPipeline.splitLines,
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = process.run().map(_.error)

          assertZIO(program)(equalTo(Vector(Some('H'), Some('w'))))
        },

        test("can redirect error to a sink") {
          val builder = new StringBuilder
          val target: zstream.ProxSink[Byte] = ZSink.foreach((byte: Byte) => ZIO.attempt(builder.append(byte.toChar)).mapError(UnknownProxError.apply))

          val process = Process("perl", List("-e", """print STDERR "Hello"""")) !> target
          val program = process.run().as(builder.toString)

          assertZIO(program)(equalTo("Hello"))
        },
      ),

      suite("Redirection ordering")(
        test("can redirect first input and then error to stream") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = Process("perl", List("-e", """my $str = <>; print STDERR "$str"""".stripMargin)) < source !># ZPipeline.utf8Decode
          val program = process.run().map(_.error)

          assertZIO(program)(equalTo("This is a test string"))
        },

        test("can redirect error first then output to stream") {
          val process = (Process("perl", List("-e", """print STDOUT Hello; print STDERR World""".stripMargin)) !># ZPipeline.utf8Decode) ># ZPipeline.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertZIO(program)(equalTo("HelloWorld"))
        },

        test("can redirect output first then error to stream") {
          val process = (Process("perl", List("-e", """print STDOUT Hello; print STDERR World""".stripMargin)) ># ZPipeline.utf8Decode) !># ZPipeline.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertZIO(program)(equalTo("HelloWorld"))
        },

        test("can redirect output first then error finally input to stream") {
          val source = ZStream("Hello").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = ((Process("perl", List("-e", """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin))
            ># ZPipeline.utf8Decode)
            !># ZPipeline.utf8Decode) < source
          val program = process.run().map(r => r.output + r.error)

          assertZIO(program)(equalTo("HelloWorld"))
        },

        test("can redirect output first then input finally error to stream") {
          val source = ZStream("Hello").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = ((Process("perl", List("-e", """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin))
            ># ZPipeline.utf8Decode)
            < source) !># ZPipeline.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertZIO(program)(equalTo("HelloWorld"))
        },

        test("can redirect input first then error finally output to stream") {
          val source = ZStream("Hello").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = ((Process("perl", List("-e", """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin))
            < source)
            !># ZPipeline.utf8Decode) ># ZPipeline.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertZIO(program)(equalTo("HelloWorld"))
        },
      ),

      suite("Input redirection")(
        test("can use stream as input") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = Process("wc", List("-w")) < source ># ZPipeline.utf8Decode
          val program = process.run().map(_.output.trim)

          assertZIO(program)(equalTo("5"))
        },

        test("can use stream as input flushing after each chunk") {
          val source = ZStream("This ", "is a test", " string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = (Process("wc", List("-w")) !< source) ># ZPipeline.utf8Decode
          val program = process.run().map(_.output.trim)

          assertZIO(program)(equalTo("5"))
        },
      ),

      suite("Termination")(
        test("can be terminated with cancellation") {
          val process = Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0"""))
          val program = ZIO.scoped { process.start().flatMap { fiber => fiber.interrupt.unit } }

          assertZIO(program)(equalTo(()))
        } @@ TestAspect.timeout(5.seconds),

        test("can be terminated") {
          val process = Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0"""))
          val program = for {
            runningProcess <- process.startProcess()
            _ <- ZIO.sleep(250.millis)
            result <- runningProcess.terminate()
          } yield result.exitCode

          assertZIO(program)(equalTo(ExitCode(1)))
        } @@ withLiveClock,

        test("can be killed") {
          val process = Process("perl", List("-e", """$SIG{TERM} = 'IGNORE'; sleep 30; exit 2"""))
          val program = for {
            runningProcess <- process.startProcess()
            _ <- ZIO.sleep(250.millis)
            result <- runningProcess.kill()
          } yield result.exitCode

          assertZIO(program)(equalTo(ExitCode(137)))
        } @@ withLiveClock,

        test("can be checked if is alive") {
          val process = Process("sleep", List("10"))
          val program = for {
            runningProcess <- process.startProcess()
            isAliveBefore <- runningProcess.isAlive
            _ <- runningProcess.terminate()
            isAliveAfter <- runningProcess.isAlive
          } yield (isAliveBefore, isAliveAfter)

          assertZIO(program)(equalTo((true, false)))
        },
      ),

      suite("Customization")(
        test("can change the command") {
          val p1 = Process("something", List("Hello", "world")) ># ZPipeline.utf8Decode
          val p2 = p1.withCommand("echo")
          val program = p2.run().map(_.output)

          assertZIO(program)(equalTo("Hello world\n"))
        },

        test("can change the arguments") {
          val p1 = Process("echo") ># ZPipeline.utf8Decode
          val p2 = p1.withArguments(List("Hello", "world"))
          val program = p2.run().map(_.output)

          assertZIO(program)(equalTo("Hello world\n"))
        },

        test("respects the working directory") {
          ZIO.attempt(Files.createTempDirectory("prox")).flatMap { tempDirectory =>
            val process = (Process("pwd") in tempDirectory) ># ZPipeline.utf8Decode
            val program = process.run().map(_.output.trim)

            assertZIO(program)(equalTo(tempDirectory.toString) || equalTo(s"/private${tempDirectory}"))
          }
        },

        test("is customizable with environment variables") {
          val process = (Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\""))
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># ZPipeline.utf8Decode
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with excluded environment variables") {
          val process = (Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\""))
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")
            `without` "TEST1") ># ZPipeline.utf8Decode
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo("Hello ! I am prox!\n"))
        },

        test("is customizable with environment variables output is bound") {
          val process = (Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")) ># ZPipeline.utf8Decode
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if input is bound") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = ((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># ZPipeline.utf8Decode
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if error is bound") {
          val process = ((Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")) !># ZPipeline.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># ZPipeline.utf8Decode
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if input and output are bound") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = (((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source) ># ZPipeline.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if input and error are bound") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = (((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source) !># ZPipeline.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># ZPipeline.utf8Decode
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if output and error are bound") {
          val process = (((Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")) !># ZPipeline.utf8Decode) ># ZPipeline.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if everything is bound") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = ((((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source) !># ZPipeline.utf8Decode) ># ZPipeline.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertZIO(program)(equalTo("Hello world! I am prox!\n"))
        },
      ),

      test("double output redirect is illegal") {
        assertZIO(
          typeCheck("""val bad = Process("echo", List("Hello world")) > new File("x").toPath > new File("y").toPath"""))(
          isLeft(anything)
        )
      },
      test("double error redirect is illegal") {
        assertZIO(
          typeCheck("""val bad = Process("echo", List("Hello world")) !> new File("x").toPath !> new File("y").toPath"""))(
          isLeft(anything)
        )
      },
      test("double input redirect is illegal") {
        assertZIO(
          typeCheck("""val bad = (Process("echo", List("Hello world")) < ZStream("X").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))) < ZStream("Y").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))"""))(
          isLeft(anything)
        )
      }
    ) @@ timeout(60.seconds) @@ sequential
}
