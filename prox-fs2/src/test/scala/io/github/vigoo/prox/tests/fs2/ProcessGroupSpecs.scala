package io.github.vigoo.prox.tests.fs2

import java.nio.file.Files

import cats.effect.ExitCode
import zio.clock.Clock
import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._
import zio.{IO, Task, ZIO}

object ProcessGroupSpecs extends DefaultRunnableSpec with ProxSpecHelpers {

  override val spec =
    suite("Piping processes together")(
      suite("Piping")(
        proxTest("is possible with two") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val processGroup = (Process("echo", List("This is a test string")) | Process("wc", List("-w"))) ># fs2.text.utf8Decode
          val program = processGroup.run().map(_.output.trim)

          assertM(program)(equalTo("5"))
        },

        proxTest("is possible with multiple") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val processGroup = (
            Process("echo", List("cat\ncat\ndog\napple")) |
              Process("sort") |
              Process("uniq", List("-c")) |
              Process("head", List("-n 1"))
            ) >? fs2.text.utf8Decode.andThen(_.through(fs2.text.lines))

          val program = processGroup.run().map(
            r => r.output.map(_.stripLineEnd.trim).filter(_.nonEmpty)
          )

          assertM(program)(hasSameElements(List("1 apple")))
        },

        proxTest("is customizable with pipes") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner


          val customPipe: fs2.Pipe[Task, Byte, Byte] =
            (s: fs2.Stream[Task, Byte]) => s
              .through(fs2.text.utf8Decode)
              .through(fs2.text.lines)
              .map(_.split(' ').toVector)
              .map(v => v.map(_ + " !!!").mkString(" "))
              .intersperse("\n")
              .through(fs2.text.utf8Encode)

          val processGroup = (Process("echo", List("This is a test string")).via(customPipe).to(Process("wc", List("-w")))) ># fs2.text.utf8Decode
          val program = processGroup.run().map(_.output.trim)

          assertM(program)(equalTo("11"))
        },

        proxTest("can be mapped") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val processGroup1 = (Process("!echo", List("This is a test string")) | Process("!wc", List("-w"))) ># fs2.text.utf8Decode
          val processGroup2 = processGroup1.map(new ProcessGroup.Mapper[String, Unit] {
            override def mapFirst[P <: Process[fs2.Stream[Task, Byte], Unit]](process: P): P = process.withCommand(process.command.tail).asInstanceOf[P]

            override def mapInnerWithIdx[P <: Process.UnboundIProcess[fs2.Stream[Task, Byte], Unit]](process: P, idx: Int): P = process.withCommand(process.command.tail).asInstanceOf[P]

            override def mapLast[P <: Process.UnboundIProcess[String, Unit]](process: P): P = process.withCommand(process.command.tail).asInstanceOf[P]
          })

          val program = processGroup2.run().map(_.output.trim)

          assertM(program)(equalTo("5"))
        }
      ),

      suite("Termination")(
        proxTest("can be terminated with cancellation") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val processGroup =
            Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0""")) |
              Process("sort")
          val program = processGroup.start().use { fiber => fiber.cancel }

          assertM(program)(equalTo(()))
        } @@ TestAspect.timeout(5.seconds),

        proxTest[Clock, Throwable]("can be terminated") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0"""))
          val p2 = Process("sort")
          val processGroup = p1 | p2

          val program = for {
            runningProcesses <- processGroup.startProcessGroup()
            _ <- ZIO(Thread.sleep(250))
            result <- runningProcesses.terminate()
          } yield result.exitCodes.toList

          assertM(program)(contains[(Process[Unit, Unit], ProxExitCode)](p1 -> ExitCode(1)))
        },

        proxTest("can be killed") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("perl", List("-e", """$SIG{TERM} = 'IGNORE'; sleep 30; exit 2"""))
          val p2 = Process("sort")
          val processGroup = p1 | p2

          val program = for {
            runningProcesses <- processGroup.startProcessGroup()
            _ <- ZIO(Thread.sleep(250))
            result <- runningProcesses.kill()
          } yield result.exitCodes

          assertM(program)(equalTo(Map[Process[Unit, Unit], ProxExitCode](
            p1 -> ExitCode(137),
            p2 -> ExitCode(137)
          )))
        } @@ TestAspect.flaky(10)
      ),

      suite("Input redirection")(
        proxTest("can be fed with an input stream") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val processGroup = (Process("cat") | Process("wc", List("-w"))) < stream ># fs2.text.utf8Decode
          val program = processGroup.run().map(_.output.trim)

          assertM(program)(equalTo("5"))
        },

        proxTest("can be fed with an input file") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          withTempFile { tempFile =>
            val program = for {
              _ <- ZIO(Files.write(tempFile.toPath, "This is a test string".getBytes("UTF-8")))
              processGroup = (Process("cat") | Process("wc", List("-w"))) < tempFile.toPath ># fs2.text.utf8Decode
              result <- processGroup.run()
            } yield result.output.trim

            assertM(program)(equalTo("5"))
          }
        }
      ),
      suite("Output redirection")(
        proxTest("output can be redirected to file") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          withTempFile { tempFile =>
            val processGroup = (Process("echo", List("This is a test string")) | Process("wc", List("-w"))) > tempFile.toPath
            val program = for {
              _ <- processGroup.run()
              contents <- fs2.io.file.readAll[Task](tempFile.toPath, blocker, 1024).through(fs2.text.utf8Decode).compile.foldMonoid
            } yield contents.trim

            assertM(program)(equalTo("5"))
          }
        },
      ),

      suite("Error redirection")(
        proxTest("can redirect each error output to a stream") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2) !># fs2.text.utf8Decode
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
              assert(result.errors.get(p2))(isSome(equalTo("world"))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can redirect each error output to a sink") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner


          val builder = new StringBuilder
          val target: fs2.Pipe[Task, Byte, Unit] = _.evalMap(byte => IO {
            builder.append(byte.toChar)
          }.unit)

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

        proxTest("can redirect each error output to a vector") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world!""""))

          val stream = fs2.text.utf8Decode[Task]
            .andThen(fs2.text.lines)
            .andThen(_.map(s => s.length))

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

        proxTest("can drain each error output") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2) drainErrors fs2.text.utf8Decode
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo(()))) &&
              assert(result.errors.get(p2))(isSome(equalTo(()))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can fold each error output") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("perl", List("-e", "print STDERR 'Hello\nworld'"))
          val p2 = Process("perl", List("-e", "print STDERR 'Does\nit\nwork?'"))
          val processGroup = (p1 | p2).foldErrors(
            fs2.text.utf8Decode.andThen(fs2.text.lines),
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
        proxTest("can redirect each error output to a stream customized per process") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.errorsToFoldMonoid {
            case p if p == p1 => fs2.text.utf8Decode.andThen(_.map(s => "P1: " + s))
            case p if p == p2 => fs2.text.utf8Decode.andThen(_.map(s => "P2: " + s))
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

        proxTest("can redirect each error output to a sink customized per process") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner


          val builder1 = new StringBuilder
          val builder2 = new StringBuilder

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.errorsToSink {
            case p if p == p1 => _.evalMap(byte => IO {
              builder1.append(byte.toChar)
            }.unit)
            case p if p == p2 =>
              _.evalMap(byte => IO {
                builder2.append(byte.toChar)
              }.unit)
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

        proxTest("can redirect each error output to a vector customized per process") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world!""""))

          val stream = fs2.text.utf8Decode[Task]
            .andThen(fs2.text.lines)
            .andThen(_.map(s => s.length))

          val processGroup = (p1 | p2).customizedPerProcess.errorsToVector {
            case p if p == p1 => stream.andThen(_.map(l => (1, l)))
            case p if p == p2 => stream.andThen(_.map(l => (2, l)))
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

        proxTest("can drain each error output customized per process") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("perl", List("-e", """print STDERR "Hello""""))
          val p2 = Process("perl", List("-e", """print STDERR "world""""))
          val processGroup = (p1 | p2).customizedPerProcess.drainErrors(_ => fs2.text.utf8Decode)
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo(()))) &&
              assert(result.errors.get(p2))(isSome(equalTo(()))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can fold each error output customized per process") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val p1 = Process("perl", List("-e", "print STDERR 'Hello\nworld'"))
          val p2 = Process("perl", List("-e", "print STDERR 'Does\nit\nwork?'"))
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
          val program = processGroup.run()

          program.map { result =>
            assert(result.errors.get(p1))(isSome(equalTo(Vector(Some('H'), Some('w'))))) &&
              assert(result.errors.get(p2))(isSome(equalTo(Vector(Some('s'), Some('t'), Some('?'))))) &&
              assert(result.output)(equalTo(())) &&
              assert(result.exitCodes.get(p1))(isSome(equalTo(ExitCode(0)))) &&
              assert(result.exitCodes.get(p2))(isSome(equalTo(ExitCode(0))))
          }
        },

        proxTest("can redirect each error output to file") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

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
                contents1 <- fs2.io.file.readAll[Task](tempFile1.toPath, blocker, 1024).through(fs2.text.utf8Decode).compile.foldMonoid
                contents2 <- fs2.io.file.readAll[Task](tempFile2.toPath, blocker, 1024).through(fs2.text.utf8Decode).compile.foldMonoid
              } yield (contents1, contents2)

              assertM(program)(equalTo(("Hello", "world")))
            }
          }
        },
      ),

      suite("Redirection ordering")(
        proxTest("can redirect each error output to a stream if fed with an input stream and redirected to an output stream") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = (p1 | p2 | p3) < stream ># fs2.text.utf8Decode !># fs2.text.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        proxTest("can redirect output if each error output and input are already redirected") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) < stream !># fs2.text.utf8Decode) ># fs2.text.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        proxTest("can attach output and then input stream if each error output and standard output are already redirected") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) !># fs2.text.utf8Decode) ># fs2.text.utf8Decode < stream

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        proxTest("can attach input and then output stream if each error output and standard output are already redirected") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) !># fs2.text.utf8Decode) < stream ># fs2.text.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        proxTest("can attach input stream and errors if standard output is already redirected") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = ((p1 | p2 | p3) ># fs2.text.utf8Decode) < stream !># fs2.text.utf8Decode

          processGroup.run()
            .map { result =>
              assert(result.errors.get(p1))(isSome(equalTo("Hello"))) &&
                assert(result.errors.get(p2))(isSome(equalTo(""))) &&
                assert(result.errors.get(p3))(isSome(equalTo(""))) &&
                assert(result.output.trim)(equalTo("5"))
            }
        },

        proxTest("can attach errors and finally input stream if standard output is already redirected") { prox =>
          import prox._

          implicit val processRunner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner

          val stream = fs2.Stream("This is a test string").through(fs2.text.utf8Encode)
          val p1 = Process("perl", List("-e", """my $str=<>; print STDERR Hello; print STDOUT "$str""""))
          val p2 = Process("sort")
          val p3 = Process("wc", List("-w"))
          val processGroup = (((p1 | p2 | p3) ># fs2.text.utf8Decode) !># fs2.text.utf8Decode) < stream

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
          typeCheck("""val bad = (Process("echo", List("Hello world")) ># fs2.text.utf8Decode) | Process("wc", List("-w"))"""))(
          isLeft(anything)
        )
      }
    ) @@ timeout(60.seconds) @@ sequential
}
