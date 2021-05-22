package io.github.vigoo.prox.tests.zstream

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import io.github.vigoo.prox.{ProxError, UnknownProxError, zstream}
import io.github.vigoo.prox.zstream._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._
import zio.stream.{ZSink, ZStream, ZTransducer}
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._
import zio.{ExitCode, ZIO}

object ProcessGroupSpecs extends DefaultRunnableSpec with ProxSpecHelpers {
  implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

  override val spec =
    suite("Piping processes together")(
      suite("Piping")(
        testM("is possible with two") {

          val processGroup = (Process("echo", List("This is a test string")) | Process("wc", List("-w"))) ># ZTransducer.utf8Decode
          val program = processGroup.run().map(_.output.trim)

          assertM(program)(equalTo("5"))
        },

        testM("is possible with multiple") {

          val processGroup = (
            Process("echo", List("cat\ncat\ndog\napple")) |
              Process("sort") |
              Process("uniq", List("-c")) |
              Process("head", List("-n 1"))
            ) >? (ZTransducer.utf8Decode >>> ZTransducer.splitLines)

          val program = processGroup.run().map(
            r => r.output.map(_.stripLineEnd.trim).filter(_.nonEmpty)
          )

          assertM(program)(hasSameElements(List("1 apple")))
        },

        testM("is customizable with pipes") {
          val customPipe = (s: zstream.ProxStream[Byte]) => s
            .transduce(ZTransducer.utf8Decode >>> ZTransducer.splitLines)
            .map(_.split(' ').toVector)
            .map(v => v.map(_ + " !!!").mkString(" "))
            .intersperse("\n")
            .flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val processGroup = (Process("echo", List("This is a test string")).via(customPipe).to(Process("wc", List("-w")))) ># ZTransducer.utf8Decode
          val program = processGroup.run().map(_.output.trim)

          assertM(program)(equalTo("10"))
        },

        testM("can be mapped") {
          import zstream.Process._

          val processGroup1 = (Process("!echo", List("This is a test string")) | Process("!wc", List("-w"))) ># ZTransducer.utf8Decode
          val processGroup2 = processGroup1.map(new ProcessGroup.Mapper[String, Unit] {
            override def mapFirst[P <: Process[zstream.ProxStream[Byte], Unit]](process: P): P = process.withCommand(process.command.tail).asInstanceOf[P]

            override def mapInnerWithIdx[P <: UnboundIProcess[zstream.ProxStream[Byte], Unit]](process: P, idx: Int): P = process.withCommand(process.command.tail).asInstanceOf[P]

            override def mapLast[P <: UnboundIProcess[String, Unit]](process: P): P = process.withCommand(process.command.tail).asInstanceOf[P]
          })

          val program = processGroup2.run().map(_.output.trim)

          assertM(program)(equalTo("5"))
        }
      ),

      suite("Termination")(
        testM("can be terminated with cancellation") {

          val processGroup =
            Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0""")) |
              Process("sort")
          val program = processGroup.start().use { fiber => fiber.interrupt.unit }

          assertM(program)(equalTo(()))
        } @@ TestAspect.timeout(5.seconds),

        testM[Blocking with Clock, ProxError]("can be terminated") {

          val p1 = Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0"""))
          val p2 = Process("sort")
          val processGroup = p1 | p2

          val program = for {
            runningProcesses <- processGroup.startProcessGroup()
            _ <- ZIO.sleep(250.millis)
            result <- runningProcesses.terminate()
          } yield result.exitCodes.toList

          assertM(program.provideSomeLayer[Blocking](Clock.live))(contains[(Process[Unit, Unit], ProxExitCode)](p1 -> ExitCode(1)))
        },

        testM("can be killed") {

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
          assertM(program.provideSomeLayer[Blocking](Clock.live))(
            contains(p1 -> ExitCode(137)
          ))
        }
      ),

      suite("Input redirection")(
        testM("can be fed with an input stream") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val processGroup = (Process("cat") | Process("wc", List("-w"))) < stream ># ZTransducer.utf8Decode
          val program = processGroup.run().map(_.output.trim)

          assertM(program)(equalTo("5"))
        },

        testM("can be fed with an input file") {

          withTempFile { tempFile =>
            val program = for {
              _ <- ZIO.effect(Files.write(tempFile.toPath, "This is a test string".getBytes("UTF-8"))).mapError(UnknownProxError.apply)
              processGroup = (Process("cat") | Process("wc", List("-w"))) < tempFile.toPath ># ZTransducer.utf8Decode
              result <- processGroup.run()
            } yield result.output.trim

            assertM(program)(equalTo("5"))
          }
        }
      ),
      suite("Output redirection")(
        testM("output can be redirected to file") {

          withTempFile { tempFile =>
            val processGroup = (Process("echo", List("This is a test string")) | Process("wc", List("-w"))) > tempFile.toPath
            val program = for {
              _ <- processGroup.run()
              contents <- ZStream.fromFile(tempFile.toPath, 1024).transduce(ZTransducer.utf8Decode).fold("")(_ + _).mapError(UnknownProxError.apply)
            } yield contents.trim

            assertM(program)(equalTo("5"))
          }
        },
      ),

      suite("Error redirection")(
        testM("can redirect each error output to a stream") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2) !># ZTransducer.utf8Decode
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
              assert(result.errors.get(p2))(isSome(equalTo("world"))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        testM("can redirect each error output to a sink") {


          val builder = new StringBuilder
          val target: zstream.ProxSink[Byte] = ZSink.foreach[Blocking, ProxError, Byte]((byte: Byte) => ZIO.effect(builder.append(byte.toChar)).mapError(UnknownProxError.apply))

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

        testM("can redirect each error output to a vector") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world!""""))

          val stream = (ZTransducer.utf8Decode >>> ZTransducer.splitLines).map(_.length)

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

        testM("can drain each error output") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2) drainErrors ZTransducer.utf8Decode
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo(()))) &&
              assert(result.errors.get(p2))(isSome(equalTo(()))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        testM("can fold each error output") {

          val p1 = Process("perl", List("-e", "print STDERR 'Hello\nworld'"))
          val p2 = Process("perl", List("-e", "print STDERR 'Does\nit\nwork?'"))
          val processGroup = (p1 | p2).foldErrors(
            ZTransducer.utf8Decode >>> ZTransducer.splitLines,
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
        testM("can redirect each error output to a stream customized per process") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.errorsToFoldMonoid {
            case p if p == p1 => ZTransducer.utf8Decode.map(s => "P1: " + s)
            case p if p == p2 => ZTransducer.utf8Decode.map(s => "P2: " + s)
          }
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo("P1: Hello"))) &&
              assert(result.errors.get(p2))(isSome(equalTo("P2: world"))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        testM("can redirect each error output to a sink customized per process") {


          val builder1 = new StringBuilder
          val builder2 = new StringBuilder

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.errorsToSink {
            case p if p == p1 => ZSink.foreach((byte: Byte) => ZIO.effect(builder1.append(byte.toChar)).mapError(UnknownProxError.apply))
            case p if p == p2 => ZSink.foreach((byte: Byte) => ZIO.effect(builder2.append(byte.toChar)).mapError(UnknownProxError.apply))
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

        testM("can redirect each error output to a vector customized per process") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world!""""))

          val stream = (ZTransducer.utf8Decode >>> ZTransducer.splitLines).map(_.length)

          val processGroup = (p1 | p2).customizedPerProcess.errorsToVector {
            case p if p == p1 => stream.map(l => (1, l))
            case p if p == p2 => stream.map(l => (2, l))
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

        testM("can drain each error output customized per process") {

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.drainErrors(_ => ZTransducer.utf8Decode)
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo(()))) &&
              assert(result.errors.get(p2))(isSome(equalTo(()))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        testM("can fold each error output customized per process") {

          val p1 = Process("perl", List("-e", "print STDERR 'Hello\nworld'"))
          val p2 = Process("perl", List("-e", "print STDERR 'Does\nit\nwork?'"))
          val processGroup = (p1 | p2).customizedPerProcess.foldErrors(
            {
              case p if p == p1 => ZTransducer.utf8Decode >>> ZTransducer.splitLines
              case p if p == p2 => (ZTransducer.utf8Decode >>> ZTransducer.splitLines).map(_.reverse)
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

        testM("can redirect each error output to file") {

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
                contents1 <- ZStream.fromFile(tempFile1.toPath, 1024).transduce(ZTransducer.utf8Decode).fold("")(_ + _).mapError(UnknownProxError.apply)
                contents2 <- ZStream.fromFile(tempFile2.toPath, 1024).transduce(ZTransducer.utf8Decode).fold("")(_ + _).mapError(UnknownProxError.apply)
              } yield (contents1, contents2)

              assertM(program)(equalTo(("Hello", "world")))
            }
          }
        },
      ),

      suite("Redirection ordering")(
        testM("can redirect each error output to a stream if fed with an input stream and redirected to an output stream") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = (p1 | p2 | p3) < stream ># ZTransducer.utf8Decode !># ZTransducer.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        testM("can redirect output if each error output and input are already redirected") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) < stream !># ZTransducer.utf8Decode) ># ZTransducer.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        testM("can attach output and then input stream if each error output and standard output are already redirected") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) !># ZTransducer.utf8Decode) ># ZTransducer.utf8Decode < stream

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        testM("can attach input and then output stream if each error output and standard output are already redirected") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) !># ZTransducer.utf8Decode) < stream ># ZTransducer.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        testM("can attach input stream and errors if standard output is already redirected") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) ># ZTransducer.utf8Decode) < stream !># ZTransducer.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        testM("can attach errors and finally input stream if standard output is already redirected") {

          val stream = ZStream("This is a test string").flatMap(s => ZStream.fromIterable(s.getBytes(StandardCharsets.UTF_8)))
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = (((p1 | p2 | p3) ># ZTransducer.utf8Decode) !># ZTransducer.utf8Decode) < stream

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },
      ),

      testM("bound process is not pipeable") {
        assertM(
          typeCheck("""val bad = (Process("echo", List("Hello world")) ># ZTransducer.utf8Decode) | Process("wc", List("-w"))"""))(
          isLeft(anything)
        )
      }
    ) @@ timeoutWarning(60.seconds) @@ sequential
}
