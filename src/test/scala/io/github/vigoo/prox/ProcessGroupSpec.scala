package io.github.vigoo.prox

import java.nio.file.Files

import cats.instances.string._
import zio._
import zio.console._
import zio.duration._
import zio.interop.catz._
import zio.test._
import zio.test.Assertion._
import zio.test.environment._
import cats.effect.{Blocker, ExitCode}
import io.github.vigoo.prox.syntax._
import zio.clock.Clock

object ProcessGroupSpecs extends ProxSpecHelpers {
  implicit val runner: ProcessRunner[Task] = new JVMProcessRunner

  val testSuite =
    suite("Piping processes together")(
      suite("Piping")(
        proxTest("is possible with two") { blocker =>
          val processGroup = (Process[Task]("echo", List("This is a test string")) | Process[Task]("wc", List("-w"))) ># fs2.text.utf8Decode
          val program = processGroup.run(blocker).map(_.output.trim)

          assertM(program, equalTo("5"))
        },

        proxTest("is possible with multiple") { blocker =>
          val processGroup = (
            Process[Task]("echo", List("cat\ncat\ndog\napple")) |
              Process[Task]("sort") |
              Process[Task]("uniq", List("-c")) |
              Process[Task]("head", List("-n 1"))
            ) >? fs2.text.utf8Decode.andThen(_.through(fs2.text.lines))

          val program = processGroup.run(blocker).map(
            r => r.output.map(_.stripLineEnd.trim).filter(_.nonEmpty)
          )

          assertM(program, hasSameElements(List("1 apple")))
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
      ),

      suite("Termination")(
        proxTest("can be terminated with cancellation") { blocker =>
          val processGroup =
            Process[Task]("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0""")) |
              Process[Task]("sort")
          val program = processGroup.start(blocker).use { fiber => fiber.cancel }

          assertM(program, equalTo(()))
        } @@ TestAspect.timeout(5.seconds),

        proxTest[Clock, Throwable, String]("can be terminated") { blocker =>

          val p1 = Process[Task]("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0"""))
          val p2 = Process[Task]("sort")
          val processGroup = p1 | p2

          val program = for {
            runningProcesses <- processGroup.startProcessGroup(blocker)
            _ <- ZIO(Thread.sleep(250))
            result <- runningProcesses.terminate()
          } yield result.exitCodes

          assertM(program, equalTo(Map(
            p1 -> ExitCode(1),
            p2 -> ExitCode(255)
          )))
        },

        proxTest("can be killed") { blocker =>
          val p1 = Process[Task]("perl", List("-e", """$SIG{TERM} = 'IGNORE'; sleep 30; exit 2"""))
          val p2 = Process[Task]("sort")
          val processGroup = p1 | p2

          val program = for {
            runningProcesses <- processGroup.startProcessGroup(blocker)
            _ <- ZIO(Thread.sleep(250))
            result <- runningProcesses.kill()
          } yield result.exitCodes

          assertM(program, equalTo(Map(
            p1 -> ExitCode(137),
            p2 -> ExitCode(137)
          )))
        }),

      suite("Input redirection")(
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
        }
      ),
      suite("Output redirection")(
        proxTest("output can be redirected to file") { blocker =>
          withTempFile { tempFile =>
            val processGroup = (Process[Task]("echo", List("This is a test string")) | Process[Task]("wc", List("-w"))) > tempFile.toPath
            val program = for {
              _ <- processGroup.run(blocker)
              contents <- fs2.io.file.readAll[Task](tempFile.toPath, blocker, 1024).through(fs2.text.utf8Decode).compile.foldMonoid
            } yield contents.trim

            assertM(program, equalTo("5"))
          }
        },
      ),

      suite("Error redirection")(
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

        proxTest("can redirect each error output to a sink") { blocker =>

          val builder = new StringBuilder
          val target: fs2.Pipe[Task, Byte, Unit] = _.evalMap(byte => IO {
            builder.append(byte.toChar)
          }.unit)

          val p1 = Process[Task]("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process[Task]("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2) !> target
          val program = processGroup.run(blocker)

          program.map { result =>
            assert(result.errors.get(p1), isSome(equalTo(()))) &&
              assert(result.errors.get(p2), isSome(equalTo(()))) &&
              assert(result.output, equalTo(())) &&
              assert(builder.toString.toSeq.sorted, equalTo("Helloworld".toSeq.sorted)) &&
              assert(result.exitCodes.get(p1), isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2), isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can redirect each error output to a vector") { blocker =>
          val p1 = Process[Task]("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process[Task]("perl", List("-e", """print STDERR "world!""""))

          val stream = fs2.text.utf8Decode[Task]
            .andThen(fs2.text.lines)
            .andThen(_.map(s => s.length))

          val processGroup = (p1 | p2) !>? stream
          val program = processGroup.run(blocker)

          program.map { result =>
            assert(result.errors.get(p1), isSome(hasSameElements(List(5)))) &&
              assert(result.errors.get(p2), isSome(hasSameElements(List(6)))) &&
              assert(result.output, equalTo(())) &&
              assert(result.exitCodes.get(p1), isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2), isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can drain each error output") { blocker =>
          val p1 = Process[Task]("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process[Task]("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2) drainErrors fs2.text.utf8Decode
          val program = processGroup.run(blocker)

          program.map { result =>
            assert(result.errors.get(p1), isSome(equalTo(()))) &&
              assert(result.errors.get(p2), isSome(equalTo(()))) &&
              assert(result.output, equalTo(())) &&
              assert(result.exitCodes.get(p1), isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2), isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can fold each error output") { blocker =>
          val p1 = Process[Task]("perl", List("-e", "print STDERR 'Hello\nworld'"))
          val p2 = Process[Task]("perl", List("-e", "print STDERR 'Does\nit\nwork?'"))
          val processGroup = (p1 | p2).foldErrors(
            fs2.text.utf8Decode.andThen(fs2.text.lines),
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = processGroup.run(blocker)

          program.map { result =>
            assert(result.errors.get(p1), isSome(equalTo(Vector(Some('H'), Some('w'))))) &&
              assert(result.errors.get(p2), isSome(equalTo(Vector(Some('D'), Some('i'), Some('w'))))) &&
              assert(result.output, equalTo(())) &&
              assert(result.exitCodes.get(p1), isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2), isSome(equalTo(ExitCode(0))))
          }
        },
      ),
      suite("Error redirection customized per process")(
        proxTest("can redirect each error output to a stream customized per process") { blocker =>
          val p1 = Process[Task]("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process[Task]("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.errorsToFoldMonoid {
            case p if p == p1 => fs2.text.utf8Decode.andThen(_.map(s => "P1: " + s))
            case p if p == p2 => fs2.text.utf8Decode.andThen(_.map(s => "P2: " + s))
          }
          val program = processGroup.run(blocker)

          program.map { result =>
            assert(result.errors.get(p1), isSome(equalTo("P1: Hello"))) &&
              assert(result.errors.get(p2), isSome(equalTo("P2: world"))) &&
              assert(result.output, equalTo(())) &&
              assert(result.exitCodes.get(p1), isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2), isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can redirect each error output to a sink customized per process") { blocker =>

          val builder1 = new StringBuilder
          val builder2 = new StringBuilder

          val p1 = Process[Task]("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process[Task]("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.errorsToSink {
            case p if p == p1 => _.evalMap(byte => IO {
              builder1.append(byte.toChar)
            }.unit)
            case p if p == p2 =>
              _.evalMap(byte => IO {
                builder2.append(byte.toChar)
              }.unit)
          }
          val program = processGroup.run(blocker)

          program.map { result =>
            assert(result.errors.get(p1), isSome(equalTo(()))) &&
              assert(result.errors.get(p2), isSome(equalTo(()))) &&
              assert(result.output, equalTo(())) &&
              assert(builder1.toString, equalTo("Hello")) &&
              assert(builder2.toString, equalTo("world")) &&
              assert(result.exitCodes.get(p1), isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2), isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can redirect each error output to a vector customized per process") { blocker =>
          val p1 = Process[Task]("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process[Task]("perl", List("-e", """print STDERR "world!""""))

          val stream = fs2.text.utf8Decode[Task]
            .andThen(fs2.text.lines)
            .andThen(_.map(s => s.length))

          val processGroup = (p1 | p2).customizedPerProcess.errorsToVector {
            case p if p == p1 => stream.andThen(_.map(l => (1, l)))
            case p if p == p2 => stream.andThen(_.map(l => (2, l)))
          }
          val program = processGroup.run(blocker)

          program.map { result =>
            assert(result.errors.get(p1), isSome(hasSameElements(List((1, 5))))) &&
              assert(result.errors.get(p2), isSome(hasSameElements(List((2, 6))))) &&
              assert(result.output, equalTo(())) &&
              assert(result.exitCodes.get(p1), isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2), isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can drain each error output customized per process") { blocker =>
          val p1 = Process[Task]("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process[Task]("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.drainErrors(_ => fs2.text.utf8Decode)
          val program = processGroup.run(blocker)

          program.map { result =>
            assert(result.errors.get(p1), isSome(equalTo(()))) &&
              assert(result.errors.get(p2), isSome(equalTo(()))) &&
              assert(result.output, equalTo(())) &&
              assert(result.exitCodes.get(p1), isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2), isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can fold each error output customized per process") { blocker =>
          val p1 = Process[Task]("perl", List("-e", "print STDERR 'Hello\nworld'"))
          val p2 = Process[Task]("perl", List("-e", "print STDERR 'Does\nit\nwork?'"))
          val processGroup = (p1 | p2).customizedPerProcess.foldErrors(
            {
              case p if p == p1 => fs2.text.utf8Decode[Task]
                .andThen(fs2.text.lines)
              case p if p == p2 => fs2.text.utf8Decode[Task]
                .andThen(fs2.text.lines)
                .andThen(_.map(_.reverse))
            },
            Vector.empty,
            (l: Vector[Option[Char]], s: String) => l :+ s.headOption
          )
          val program = processGroup.run(blocker)

          program.map { result =>
            assert(result.errors.get(p1), isSome(equalTo(Vector(Some('H'), Some('w'))))) &&
              assert(result.errors.get(p2), isSome(equalTo(Vector(Some('s'), Some('t'), Some('?'))))) &&
              assert(result.output, equalTo(())) &&
              assert(result.exitCodes.get(p1), isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2), isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can redirect each error output to file") { blocker =>
          withTempFile { tempFile1 =>
            withTempFile { tempFile2 =>
              val p1 = Process[Task]("perl", List("-e", """print STDERR "Hello""""))
              val p2 = Process[Task]("perl", List("-e", """print STDERR "world""""))
              val processGroup = (p1 | p2).customizedPerProcess.errorsToFile {
                case p if p == p1 => tempFile1.toPath
                case p if p == p2 => tempFile2.toPath
              }
              val program = for {
                _ <- processGroup.run(blocker)
                contents1 <- fs2.io.file.readAll[Task](tempFile1.toPath, blocker, 1024).through(fs2.text.utf8Decode).compile.foldMonoid
                contents2 <- fs2.io.file.readAll[Task](tempFile2.toPath, blocker, 1024).through(fs2.text.utf8Decode).compile.foldMonoid
              } yield (contents1, contents2)

              assertM(program, equalTo(("Hello", "world")))
            }
          }
        },
      ),

      suite("Redirection ordering")(
        proxTest("can redirect each error output to a stream if fed with an input stream and redirected to an output stream") { blocker =>
          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process[Task]("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process[Task]("sort")
          val p3 = Process[Task]("wc", List("-w"))
          val processGroup = (p1 | p2 | p3) < stream ># fs2.text.utf8Decode !># fs2.text.utf8Decode

          processGroup.run(blocker)
            .map { result =>
              assert(result.errors.get(p1), isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2), isSome(equalTo(""))) &&
                assert(result.errors.get(p3), isSome(equalTo(""))) &&
                assert(result.output.trim, equalTo("5"))
            }
        },

        proxTest("can redirect output if each error output and input are already redirected") { blocker =>
          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process[Task]("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process[Task]("sort")
          val p3 = Process[Task]("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) < stream !># fs2.text.utf8Decode) ># fs2.text.utf8Decode

          processGroup.run(blocker)
            .map { result =>
              assert(result.errors.get(p1), isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2), isSome(equalTo(""))) &&
                assert(result.errors.get(p3), isSome(equalTo(""))) &&
                assert(result.output.trim, equalTo("5"))
            }
        },

        proxTest("can attach output and then input stream if each error output and standard output are already redirected") { blocker =>
          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process[Task]("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process[Task]("sort")
          val p3 = Process[Task]("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) !># fs2.text.utf8Decode) ># fs2.text.utf8Decode < stream

          processGroup.run(blocker)
            .map { result =>
              assert(result.errors.get(p1), isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2), isSome(equalTo(""))) &&
                assert(result.errors.get(p3), isSome(equalTo(""))) &&
                assert(result.output.trim, equalTo("5"))
            }
        },

        proxTest("can attach input and then output stream if each error output and standard output are already redirected") { blocker =>
          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process[Task]("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process[Task]("sort")
          val p3 = Process[Task]("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) !># fs2.text.utf8Decode) < stream ># fs2.text.utf8Decode

          processGroup.run(blocker)
            .map { result =>
              assert(result.errors.get(p1), isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2), isSome(equalTo(""))) &&
                assert(result.errors.get(p3), isSome(equalTo(""))) &&
                assert(result.output.trim, equalTo("5"))
            }
        },

        proxTest("can attach input stream and errors if standard output is already redirected") { blocker =>
          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process[Task]("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process[Task]("sort")
          val p3 = Process[Task]("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) ># fs2.text.utf8Decode) < stream !># fs2.text.utf8Decode

          processGroup.run(blocker)
            .map { result =>
              assert(result.errors.get(p1), isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2), isSome(equalTo(""))) &&
                assert(result.errors.get(p3), isSome(equalTo(""))) &&
                assert(result.output.trim, equalTo("5"))
            }
        },

        proxTest("can attach errors and finally input stream if standard output is already redirected") { blocker =>
          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process[Task]("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process[Task]("sort")
          val p3 = Process[Task]("wc", List("-w"))
          val processGroup = (((p1 | p2 | p3) ># fs2.text.utf8Decode) !># fs2.text.utf8Decode) < stream

          processGroup.run(blocker)
            .map { result =>
              assert(result.errors.get(p1), isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2), isSome(equalTo(""))) &&
                assert(result.errors.get(p3), isSome(equalTo(""))) &&
                assert(result.output.trim, equalTo("5"))
            }
        },
      ),

      testM("bound process is not pipeable") {
        assertM(
          typeCheck("""val bad = (Process[Task]("echo", List("Hello world")) ># fs2.text.utf8Decode) | Process[Task]("wc", List("-w"))"""),
          isLeft(anything)
        )
      }
    )
}

object ProcessGroupSpec extends DefaultRunnableSpec(
  ProcessGroupSpecs.testSuite,
  defaultTestAspects = List(
    TestAspect.timeoutWarning(60.seconds),
    TestAspect.sequential
  )
)
