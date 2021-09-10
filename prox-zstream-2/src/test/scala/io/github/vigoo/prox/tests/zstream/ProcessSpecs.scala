package io.github.vigoo.prox.tests.zstream

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import io.github.vigoo.prox.{ProxError, UnknownProxError, zstream}
import io.github.vigoo.prox.zstream._
import zio._
import zio.stream.{ZSink, ZStream, ZTransducer}
import zio.test.Assertion.{anything, equalTo, hasSameElements, isLeft}
import zio.test.TestAspect._
import zio.test._
import zio.test.environment.Live
import zio.{ExitCode, ZIO}

object ProcessSpecs extends DefaultRunnableSpec with ProxSpecHelpers {
  implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

  override val spec =
    suite("Executing a process")(
      test("returns the exit code") {
        val program = for {
          trueResult <- Process("true").run()
          falseResult <- Process("false").run()
        } yield (trueResult.exitCode, falseResult.exitCode)

        assertM(program)(equalTo((ExitCode(0), ExitCode(1))))
      },

      suite("Output redirection")(
        test("can redirect output to a file") {
          withTempFile { tempFile =>
            val process = Process("echo", List("Hello world!")) > tempFile.toPath
            val program = for {
              _ <- process.run()
              contents <- ZStream.fromFile(tempFile.toPath, 1024).transduce(ZTransducer.utf8Decode).fold("")(_ + _).mapError(UnknownProxError.apply)
            } yield contents

            assertM(program)(equalTo("Hello world!\n"))
          }
        },

        test("can redirect output to append a file") {
          withTempFile { tempFile =>
            val process1 = Process("echo", List("Hello")) > tempFile.toPath
            val process2 = Process("echo", List("world")) >> tempFile.toPath
            val program = for {
              _ <- process1.run()
              _ <- process2.run()
              contents <- ZStream.fromFile(tempFile.toPath, 1024).transduce(ZTransducer.utf8Decode).fold("")(_ + _).mapError(UnknownProxError.apply)
            } yield contents

            assertM(program)(equalTo("Hello\nworld\n"))
          }
        },

        test("can redirect output to stream") {
          val process = Process("echo", List("Hello world!")) ># ZTransducer.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world!\n"))
        },

        test("can redirect output to stream folding monoid") {
          val process = Process("echo", List("Hello\nworld!")) ># (ZTransducer.utf8Decode >>> ZTransducer.splitLines)
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Helloworld!"))
        },

        test("can redirect output to stream collected to vector") {
          case class StringLength(value: Int)

          val stream =
            ZTransducer.utf8Decode >>>
              ZTransducer.splitLines.map(s => StringLength(s.length))

          val process = Process("echo", List("Hello\nworld!")) >? stream
          val program = process.run().map(_.output)

          assertM(program)(hasSameElements(List(StringLength(5), StringLength(6))))
        },

        test("can redirect output to stream and ignore it's result") {
          val process = Process("echo", List("Hello\nworld!")).drainOutput(ZTransducer.utf8Decode >>> ZTransducer.splitLines)
          val program = process.run().map(_.output)

          assertM(program)(equalTo(()))
        },

        test("can redirect output to stream and fold it") {
          val process = Process("echo", List("Hello\nworld!")).foldOutput(
            ZTransducer.utf8Decode >>> ZTransducer.splitLines,
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = process.run().map(_.output)

          assertM(program)(equalTo(Vector(Some('H'), Some('w'))))
        },

        test("can redirect output to a sink") {
          val builder = new StringBuilder
          val target: zstream.ProxSink[Byte] = ZSink.foreach((byte: Byte) => ZIO.attempt(builder.append(byte.toChar)).mapError(UnknownProxError.apply))

          val process = Process("echo", List("Hello world!")) > target
          val program = process.run().as(builder.toString)

          assertM(program)(equalTo("Hello world!\n"))
        },
      ),
      suite("Error redirection")(
        test("can redirect error to a file") {
          withTempFile { tempFile =>
            val process = Process("perl", List("-e", "print STDERR 'Hello world!'")) !> tempFile.toPath
            val program = for {
              _ <- process.run()
              contents <- ZStream.fromFile(tempFile.toPath, 1024).transduce(ZTransducer.utf8Decode).fold("")(_ + _).mapError(UnknownProxError.apply)
            } yield contents

            assertM(program)(equalTo("Hello world!"))
          }
        },

        test("can redirect error to append a file") {
          withTempFile { tempFile =>
            val process1 = Process("perl", List("-e", "print STDERR Hello")) !> tempFile.toPath
            val process2 = Process("perl", List("-e", "print STDERR world")) !>> tempFile.toPath
            val program = for {
              _ <- process1.run()
              _ <- process2.run()
              contents <- ZStream.fromFile(tempFile.toPath, 1024).transduce(ZTransducer.utf8Decode).fold("")(_ + _).mapError(UnknownProxError.apply)
            } yield contents

            assertM(program)(equalTo("Helloworld"))
          }
        },

        test("can redirect error to stream") {
          val process = Process("perl", List("-e", """print STDERR "Hello"""")) !># ZTransducer.utf8Decode
          val program = process.run().map(_.error)

          assertM(program)(equalTo("Hello"))
        },

        test("can redirect error to stream folding monoid") {
          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")) !># (ZTransducer.utf8Decode >>> ZTransducer.splitLines)
          val program = process.run().map(_.error)

          assertM(program)(equalTo("Helloworld!"))
        },

        test("can redirect error to stream collected to vector") {
          case class StringLength(value: Int)

          val stream =
            ZTransducer.utf8Decode >>>
              ZTransducer.splitLines.map(s => StringLength(s.length))

          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")) !>? stream
          val program = process.run().map(_.error)

          assertM(program)(hasSameElements(List(StringLength(5), StringLength(6))))
        },

        test("can redirect error to stream and ignore it's result") {
          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")).drainError(ZTransducer.utf8Decode >>> ZTransducer.splitLines)
          val program = process.run().map(_.error)

          assertM(program)(equalTo(()))
        },

        test("can redirect error to stream and fold it") {
          val process = Process("perl", List("-e", "print STDERR 'Hello\nworld!'")).foldError(
            ZTransducer.utf8Decode >>> ZTransducer.splitLines,
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = process.run().map(_.error)

          assertM(program)(equalTo(Vector(Some('H'), Some('w'))))
        },

        test("can redirect error to a sink") {
          val builder = new StringBuilder
          val target: zstream.ProxSink[Byte] = ZSink.foreach((byte: Byte) => ZIO.attempt(builder.append(byte.toChar)).mapError(UnknownProxError.apply))

          val process = Process("perl", List("-e", """print STDERR "Hello"""")) !> target
          val program = process.run().as(builder.toString)

          assertM(program)(equalTo("Hello"))
        },
      ),

      suite("Redirection ordering")(
        test("can redirect first input and then error to stream") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = Process("perl", List("-e", """my $str = <>; print STDERR "$str"""".stripMargin)) < source !># ZTransducer.utf8Decode
          val program = process.run().map(_.error)

          assertM(program)(equalTo("This is a test string"))
        },

        test("can redirect error first then output to stream") {
          val process = (Process("perl", List("-e", """print STDOUT Hello; print STDERR World""".stripMargin)) !># ZTransducer.utf8Decode) ># ZTransducer.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertM(program)(equalTo("HelloWorld"))
        },

        test("can redirect output first then error to stream") {
          val process = (Process("perl", List("-e", """print STDOUT Hello; print STDERR World""".stripMargin)) ># ZTransducer.utf8Decode) !># ZTransducer.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertM(program)(equalTo("HelloWorld"))
        },

        test("can redirect output first then error finally input to stream") {
          val source = ZStream("Hello").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = ((Process("perl", List("-e", """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin))
            ># ZTransducer.utf8Decode)
            !># ZTransducer.utf8Decode) < source
          val program = process.run().map(r => r.output + r.error)

          assertM(program)(equalTo("HelloWorld"))
        },

        test("can redirect output first then input finally error to stream") {
          val source = ZStream("Hello").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = ((Process("perl", List("-e", """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin))
            ># ZTransducer.utf8Decode)
            < source) !># ZTransducer.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertM(program)(equalTo("HelloWorld"))
        },

        test("can redirect input first then error finally output to stream") {
          val source = ZStream("Hello").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = ((Process("perl", List("-e", """my $str = <>; print STDOUT "$str"; print STDERR World""".stripMargin))
            < source)
            !># ZTransducer.utf8Decode) ># ZTransducer.utf8Decode
          val program = process.run().map(r => r.output + r.error)

          assertM(program)(equalTo("HelloWorld"))
        },
      ),

      suite("Input redirection")(
        test("can use stream as input") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = Process("wc", List("-w")) < source ># ZTransducer.utf8Decode
          val program = process.run().map(_.output.trim)

          assertM(program)(equalTo("5"))
        },

        test("can use stream as input flushing after each chunk") {
          val source = ZStream("This ", "is a test", " string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = (Process("wc", List("-w")) !< source) ># ZTransducer.utf8Decode
          val program = process.run().map(_.output.trim)

          assertM(program)(equalTo("5"))
        },
      ),

      suite("Termination")(
        test("can be terminated with cancellation") {
          val process = Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0"""))
          val program = process.start().use { fiber => fiber.interrupt.unit }

          assertM(program)(equalTo(()))
        } @@ TestAspect.timeout(5.seconds),

        test("can be terminated") {
          val process = Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0"""))
          val program = for {
            runningProcess <- process.startProcess()
            _ <- ZIO.sleep(250.millis)
            result <- runningProcess.terminate()
          } yield result.exitCode

          assertM(program.provideLayer(Clock.live))(equalTo(ExitCode(1)))
        },

        test("can be killed") {
          val process = Process("perl", List("-e", """$SIG{TERM} = 'IGNORE'; sleep 30; exit 2"""))
          val program = for {
            runningProcess <- process.startProcess()
            _ <- ZIO(Thread.sleep(250))
            result <- runningProcess.kill()
          } yield result.exitCode

          assertM(program.provideLayer(Clock.live))(equalTo(ExitCode(137)))
        },

        test("can be checked if is alive") {
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
        test("can change the command") {
          val p1 = Process("something", List("Hello", "world")) ># ZTransducer.utf8Decode
          val p2 = p1.withCommand("echo")
          val program = p2.run().map(_.output)

          assertM(program)(equalTo("Hello world\n"))
        },

        test("can change the arguments") {
          val p1 = Process("echo") ># ZTransducer.utf8Decode
          val p2 = p1.withArguments(List("Hello", "world"))
          val program = p2.run().map(_.output)

          assertM(program)(equalTo("Hello world\n"))
        },

        test("respects the working directory") {
          ZIO.attempt(Files.createTempDirectory("prox")).flatMap { tempDirectory =>
            val process = (Process("pwd") in tempDirectory) ># ZTransducer.utf8Decode
            val program = process.run().map(_.output.trim)

            assertM(program)(equalTo(tempDirectory.toString) || equalTo(s"/private${tempDirectory}"))
          }
        },

        test("is customizable with environment variables") {
          val process = (Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\""))
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># ZTransducer.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with excluded environment variables") {
          val process = (Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\""))
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")
            `without` "TEST1") ># ZTransducer.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello ! I am prox!\n"))
        },

        test("is customizable with environment variables output is bound") {
          val process = (Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")) ># ZTransducer.utf8Decode
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if input is bound") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = ((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># ZTransducer.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if error is bound") {
          val process = ((Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")) !># ZTransducer.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># ZTransducer.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if input and output are bound") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = (((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source) ># ZTransducer.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if input and error are bound") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = (((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source) !># ZTransducer.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox")) ># ZTransducer.utf8Decode
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if output and error are bound") {
          val process = (((Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")) !># ZTransducer.utf8Decode) ># ZTransducer.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },

        test("is customizable with environment variables if everything is bound") {
          val source = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val process = ((((Process("sh", List("-c", "cat > /dev/null; echo \"Hello $TEST1! I am $TEST2!\"")) < source) !># ZTransducer.utf8Decode) ># ZTransducer.utf8Decode)
            `with` ("TEST1" -> "world")
            `with` ("TEST2" -> "prox"))
          val program = process.run().map(_.output)

          assertM(program)(equalTo("Hello world! I am prox!\n"))
        },
      ),

      test("double output redirect is illegal") {
        assertM(
          typeCheck("""val bad = Process("echo", List("Hello world")) > new File("x").toPath > new File("y").toPath"""))(
          isLeft(anything)
        )
      },
      test("double error redirect is illegal") {
        assertM(
          typeCheck("""val bad = Process("echo", List("Hello world")) !> new File("x").toPath !> new File("y").toPath"""))(
          isLeft(anything)
        )
      },
      test("double input redirect is illegal") {
        assertM(
          typeCheck("""val bad = (Process("echo", List("Hello world")) < ZStream("X").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))) < ZStream("Y").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))"""))(
          isLeft(anything)
        )
      }
    ) @@ timeout(60.seconds) @@ sequential
}
