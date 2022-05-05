package io.github.vigoo.prox.tests.zstream

import io.github.vigoo.prox.zstream._
import io.github.vigoo.prox.{UnknownProxError, zstream}
import zio.{Clock, _}
import zio.stream.{ZSink, ZStream, ZPipeline}
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import java.nio.charset.StandardCharsets
import java.nio.file.Files

object ProcessGroupSpecs extends ZIOSpecDefault with ProxSpecHelpers {
  implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

  override val spec =
    suite("Piping processes together")(
      suite("Piping")(
        test("is possible with two") {

          val processGroup = (Process("echo", List("This is a test string")) | Process("wc", List("-w"))) ># ZPipeline.utf8Decode
          val program = processGroup.run().map(_.output.trim)

          assertZIO(program)(equalTo("5"))
        },

        test("is possible with multiple") {

          val processGroup = (
            Process("echo", List("cat\ncat\ndog\napple")) |
              Process("sort") |
              Process("uniq", List("-c")) |
              Process("head", List("-n 1"))
            ) >? (ZPipeline.utf8Decode >>> ZPipeline.splitLines)

          val program = processGroup.run().map(
            r => r.output.map(_.stripLineEnd.trim).filter(_.nonEmpty)
          )

          assertZIO(program)(hasSameElements(List("1 apple")))
        },

        test("is customizable with pipes") {
          val customPipe = (s: zstream.ProxStream[Byte]) => s
            .via(
              ZPipeline.fromChannel(
                (ZPipeline.utf8Decode >>> ZPipeline.splitLines).channel.mapError(UnknownProxError.apply)
              )
            )
            .map(_.split(' ').toVector)
            .map(v => v.map(_ + " !!!").mkString(" "))
            .intersperse("\n")
            .flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val processGroup = Process("echo", List("This is a test string")).via(customPipe).to(Process("wc", List("-w"))) ># ZPipeline.utf8Decode
          val program = processGroup.run().map(_.output.trim)

          assertZIO(program)(equalTo("10"))
        },

        test("can be mapped") {
          import zstream.Process._

          val processGroup1 = (Process("!echo", List("This is a test string")) | Process("!wc", List("-w"))) ># ZPipeline.utf8Decode
          val processGroup2 = processGroup1.map(new ProcessGroup.Mapper[String, Unit] {
            override def mapFirst[P <: Process[zstream.ProxStream[Byte], Unit]](process: P): P = process.withCommand(process.command.tail).asInstanceOf[P]

            override def mapInnerWithIdx[P <: UnboundIProcess[zstream.ProxStream[Byte], Unit]](process: P, idx: Int): P = process.withCommand(process.command.tail).asInstanceOf[P]

            override def mapLast[P <: UnboundIProcess[String, Unit]](process: P): P = process.withCommand(process.command.tail).asInstanceOf[P]
          })

          val program = processGroup2.run().map(_.output.trim)

          assertZIO(program)(equalTo("5"))
        }
      ),

      suite("Termination")(
        test("can be terminated with cancellation") {

          val processGroup =
            Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0""")) |
              Process("sort")
          val program = ZIO.scoped { processGroup.start().flatMap { fiber => fiber.interrupt.unit } }

          assertZIO(program)(equalTo(()))
        } @@ TestAspect.timeout(5.seconds),

        test("can be terminated") {

          val p1 = Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0"""))
          val p2 = Process("sort")
          val processGroup = p1 | p2

          val program = for {
            runningProcesses <- processGroup.startProcessGroup()
            _ <- ZIO.sleep(250.millis)
            result <- runningProcesses.terminate()
          } yield result.exitCodes.toList

          assertZIO(program)(contains[(Process[Unit, Unit], ProxExitCode)](p1 -> ExitCode(1)))
        } @@ withLiveClock,

        test("can be killed") {

          val p1 = Process("perl", List("-e", """$SIG{TERM} = 'IGNORE'; sleep 30; exit 2"""))
          val p2 = Process("sort")
          val processGroup = p1 | p2

          val program = for {
            runningProcesses <- processGroup.startProcessGroup()
            _ <- ZIO.sleep(250.millis)
            result <- runningProcesses.kill()
          } yield result.exitCodes

          // Note: we can't assert on the second process' exit code because there is a race condition
          // between killing it directly and being stopped because of the upstream process got killed.
          assertZIO(program)(
            contains(p1 -> ExitCode(137)
            ))
        } @@ withLiveClock
      ),

      suite("Input redirection")(
        test("can be fed with an input stream") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val processGroup = (Process("cat") | Process("wc", List("-w"))) < stream ># ZPipeline.utf8Decode
          val program = processGroup.run().map(_.output.trim)

          assertZIO(program)(equalTo("5"))
        },

        test("can be fed with an input file") {

          withTempFile { tempFile =>
            val program = for {
              _ <- ZIO.attempt(Files.write(tempFile.toPath, "This is a test string".getBytes("UTF-8"))).mapError(UnknownProxError.apply)
              processGroup = (Process("cat") | Process("wc", List("-w"))) < tempFile.toPath ># ZPipeline.utf8Decode
              result <- processGroup.run()
            } yield result.output.trim

            assertZIO(program)(equalTo("5"))
          }
        }
      ),
      suite("Output redirection")(
        test("output can be redirected to file") {

          withTempFile { tempFile =>
            val processGroup = (Process("echo", List("This is a test string")) | Process("wc", List("-w"))) > tempFile.toPath
            val program = for {
              _ <- processGroup.run()
              contents <- ZStream.fromFile(tempFile, 1024).via(ZPipeline.utf8Decode).runFold("")(_ + _).mapError(UnknownProxError.apply)
            } yield contents.trim

            assertZIO(program)(equalTo("5"))
          }
        },
      ),

      suite("Error redirection")(
        test("can redirect each error output to a stream") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2) !># ZPipeline.utf8Decode
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
              assert(result.errors.get(p2))(isSome(equalTo("world"))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        test("can redirect each error output to a sink") {


          val builder = new StringBuilder
          val target: zstream.ProxSink[Byte] = ZSink.foreach((byte: Byte) => ZIO.attempt(builder.append(byte.toChar)).mapError(UnknownProxError.apply))

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2) !> target
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo(()))) &&
              assert(result.errors.get(p2))(isSome(equalTo(()))) &&
              assert(result.output)(equalTo(())) &&
              assert(builder.toString.toSeq.sorted)(equalTo("Helloworld".toSeq.sorted)) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        test("can redirect each error output to a vector") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world!""""))

          val stream = ZPipeline.utf8Decode >>> ZPipeline.splitLines >>> ZPipeline.map[String, Int](_.length)

          val processGroup = (p1 | p2) !>? stream
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(hasSameElements(List(5)))) &&
              assert(result.errors.get(p2))(isSome(hasSameElements(List(6)))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        test("can drain each error output") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2) drainErrors ZPipeline.utf8Decode
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo(()))) &&
              assert(result.errors.get(p2))(isSome(equalTo(()))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        test("can fold each error output") {

          val p1 = Process("perl", List("-e", "print STDERR 'Hello\nworld'"))
          val p2 = Process("perl", List("-e", "print STDERR 'Does\nit\nwork?'"))
          val processGroup = (p1 | p2).foldErrors(
            ZPipeline.utf8Decode >>> ZPipeline.splitLines,
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo(Vector(Some('H'), Some('w'))))) &&
              assert(result.errors.get(p2))(isSome(equalTo(Vector(Some('D'), Some('i'), Some('w'))))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },
      ),
      suite("Error redirection customized per process")(
        test("can redirect each error output to a stream customized per process") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.errorsToFoldMonoid {
            case p if p == p1 => ZPipeline.utf8Decode >>> ZPipeline.map(s => "P1: " + s)
            case p if p == p2 => ZPipeline.utf8Decode >>> ZPipeline.map(s => "P2: " + s)
          }
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo("P1: HelloP1: "))) &&
              assert(result.errors.get(p2))(isSome(equalTo("P2: worldP2: "))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        test("can redirect each error output to a sink customized per process") {


          val builder1 = new StringBuilder
          val builder2 = new StringBuilder

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.errorsToSink {
            case p if p == p1 => ZSink.foreach((byte: Byte) => ZIO.attempt(builder1.append(byte.toChar)).mapError(UnknownProxError.apply))
            case p if p == p2 => ZSink.foreach((byte: Byte) => ZIO.attempt(builder2.append(byte.toChar)).mapError(UnknownProxError.apply))
          }
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo(()))) &&
              assert(result.errors.get(p2))(isSome(equalTo(()))) &&
              assert(result.output)(equalTo(())) &&
              assert(builder1.toString)(equalTo("Hello")) &&
              assert(builder2.toString)(equalTo("world")) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        test("can redirect each error output to a vector customized per process") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world!""""))

          val stream = ZPipeline.utf8Decode >>> ZPipeline.splitLines >>> ZPipeline.map[String, Int](_.length)

          val processGroup = (p1 | p2).customizedPerProcess.errorsToVector {
            case p if p == p1 => stream >>> ZPipeline.map(l => (1, l))
            case p if p == p2 => stream >>> ZPipeline.map(l => (2, l))
          }
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(hasSameElements(List((1, 5))))) &&
              assert(result.errors.get(p2))(isSome(hasSameElements(List((2, 6))))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        test("can drain each error output customized per process") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.drainErrors(_ => ZPipeline.utf8Decode)
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo(()))) &&
              assert(result.errors.get(p2))(isSome(equalTo(()))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        test("can fold each error output customized per process") {

          val p1 = Process("perl", List("-e", "print STDERR 'Hello\nworld'"))
          val p2 = Process("perl", List("-e", "print STDERR 'Does\nit\nwork?'"))
          val processGroup = (p1 | p2).customizedPerProcess.foldErrors(
            {
              case p if p == p1 => ZPipeline.utf8Decode >>> ZPipeline.splitLines
              case p if p == p2 => ZPipeline.utf8Decode >>> ZPipeline.splitLines >>> ZPipeline.map[String, String](_.reverse)
            },
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo(Vector(Some('H'), Some('w'))))) &&
              assert(result.errors.get(p2))(isSome(equalTo(Vector(Some('s'), Some('t'), Some('?'))))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        test("can redirect each error output to file") {

          withTempFile { tempFile1 =>
            withTempFile { tempFile2 =>
              val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
              val p2 = Process("perl", List("-e", """print STDERR "world""""))
              val processGroup = (p1 | p2).customizedPerProcess.errorsToFile {
                case p if p == p1 => tempFile1.toPath
                case p if p == p2 => tempFile2.toPath
              }
              val program = for {
                _ <- processGroup.run()
                contents1 <- ZStream.fromFile(tempFile1, 1024).via(ZPipeline.utf8Decode).runFold("")(_ + _).mapError(UnknownProxError.apply)
                contents2 <- ZStream.fromFile(tempFile2, 1024).via(ZPipeline.utf8Decode).runFold("")(_ + _).mapError(UnknownProxError.apply)
              } yield (contents1, contents2)

              assertZIO(program)(equalTo(("Hello", "world")))
            }
          }
        },
      ),

      suite("Redirection ordering")(
        test("can redirect each error output to a stream if fed with an input stream and redirected to an output stream") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = (p1 | p2 | p3) < stream ># ZPipeline.utf8Decode !># ZPipeline.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        test("can redirect output if each error output and input are already redirected") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) < stream !># ZPipeline.utf8Decode) ># ZPipeline.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        test("can attach output and then input stream if each error output and standard output are already redirected") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) !># ZPipeline.utf8Decode) ># ZPipeline.utf8Decode < stream

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        test("can attach input and then output stream if each error output and standard output are already redirected") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) !># ZPipeline.utf8Decode) < stream ># ZPipeline.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        test("can attach input stream and errors if standard output is already redirected") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) ># ZPipeline.utf8Decode) < stream !># ZPipeline.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        test("can attach errors and finally input stream if standard output is already redirected") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = (((p1 | p2 | p3) ># ZPipeline.utf8Decode) !># ZPipeline.utf8Decode) < stream

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },
      ),

      test("bound process is not pipeable") {
        assertZIO(
          typeCheck("""val bad = (Process("echo", List("Hello world")) ># ZPipeline.utf8Decode) | Process("wc", List("-w"))"""))(
          isLeft(anything)
        )
      }
    ) @@ timeoutWarning(60.seconds) @@ sequential
}
