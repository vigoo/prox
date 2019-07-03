package io.github.vigoo.prox

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors

import cats.effect.{Blocker, ContextShift, IO}
import cats.implicits._
import fs2._
import org.specs2.Specification

import scala.concurrent.ExecutionContext.Implicits.global
import shapeless.test.illTyped
import syntax._

import scala.concurrent.ExecutionContext

// scalastyle:off public.methods.have.type
// scalastyle:off public.member.have.type

class ProcessSpecs extends Specification { def is = s2"""
  A process can be
    executed for getting it's exit code                             $simpleProcessGetExitCode
    executed by redirecting it's output to a file                   $simpleProcessFileOutput
    executed by redirecting it's output to a stream                 $simpleProcessStreamOutput
    executed by redirecting it's output to a sink                   $simpleProcessSinkOutput
    executed by redirecting it's error to a stream                  $simpleProcessStreamError
    executed by redirecting it's error to a sink                    $simpleProcessSinkError
    executed by using a stream as it's input                        $simpleProcessStreamInput
    piped to another                                                $simpleProcessPiping
    piped to another through a custom pipe                          $customProcessPiping
    piped to another getting a HList of running processes           $simpleProcessPipingHList
    chained by multiple piping operations                           $multiProcessPiping
    chained by multiple piping operations, preserving error redir   $multiProcessPipingWithErrorRedir
    specified to run in a given directory                           $workingDirectoryWorks
    piped and then its input redirected to a stream                 $pipedProcessStreamInput
    piped and then its input redirected to a file                   $pipedProcessFileInput
    piped and then its error redirected to a stream                 $pipedProcessStreamError
    be checked if it is alive                                       $isAlive
    be terminated                                                   $terminateSignal
    be killed                                                       $killSignal
    be executed with custom environment variables                   $customEnvVariables

  Stream input sources can be
    marked to be flushed per chunks                                 $flushChunks

  Stream output targets can be
    folded automatically for monoids                                $outFoldMonoid
    logged automatically for non monoids                            $outLogNonMonoid
    logged for monoids                                              $outLogMonoid
    ignored                                                         $outIgnore
    folded with custom function                                     $outCustomFold

  Stream error targets can be
    folded automatically for monoids                                $errFoldMonoid
    logged automatically for non monoids                            $errLogNonMonoid
    logged for monoids                                              $errLogMonoid
    ignored                                                         $errIgnore
    folded with custom function                                     $errCustomFold

  The DSL prevents
    redirecting the output twice                                    $doubleOutputRedirectIsIllegal
    redirecting the input twice                                     $doubleInputRedirectIsIllegal
    redirecting the error twice                                     $doubleErrorRedirectIsIllegal
    piping a process with redirected output                         $pipingRedirectedOutputIsIllegal
    piping to a process with redirected input                       $pipingToRedirectedInputIsIllegal
  """

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  def simpleProcessGetExitCode = {
    val program = Blocker[IO].use { blocker =>
      for {
        trueRunning <- Process("true").start(blocker)
        falseRunning <- Process("false").start(blocker)
        trueResult <- trueRunning.waitForExit()
        falseResult <- falseRunning.waitForExit()
      } yield (trueResult.exitCode, falseResult.exitCode)
    }
    program.unsafeRunSync() must beEqualTo((0, 1))
  }

  def workingDirectoryWorks = {
    val tempDirectory = Files.createTempDirectory("prox")
    val program = Blocker[IO].use { blocker =>
      for {
        pwdRunning <- ((Process("pwd") in tempDirectory) > text.utf8Decode[IO]).start(blocker)
        pwdResult <- pwdRunning.waitForExit()
      } yield pwdResult.fullOutput.trim
    }

    program.unsafeRunSync() must beOneOf(tempDirectory.toString, s"/private${tempDirectory}")
  }

  def simpleProcessFileOutput = {
    val tempFile = File.createTempFile("test", "txt")
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("echo", List("Hello world!")) > tempFile.toPath).start(blocker)
        _ <- running.waitForExit()
        contents <- io.file.readAll[IO](tempFile.toPath, blocker, 1024).through(text.utf8Decode).compile.foldMonoid
      } yield contents
    }

    program.unsafeRunSync() must beEqualTo("Hello world!\n")
  }

  def simpleProcessStreamOutput = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("echo", List("Hello world!")) > text.utf8Decode[IO]).start(blocker)
        result <- running.waitForExit()
      } yield result.fullOutput
    }

    program.unsafeRunSync() must beEqualTo("Hello world!\n")
  }

  def simpleProcessSinkOutput = {
    val builder = new StringBuilder()

    val target: Pipe[IO, Byte, Unit] = _.evalMap(byte => IO { builder.append(byte.toChar) }.void)
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("echo", List("Hello world!")) > target).start(blocker)
        _ <- running.waitForExit()
      } yield builder.toString()
    }

    program.unsafeRunSync() must beEqualTo("Hello world!\n")
  }

  def simpleProcessStreamError = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("perl", List("-e", """print STDERR "Hello"""")) redirectErrorTo text.utf8Decode[IO]).start(blocker)
        result <- running.waitForExit()
      } yield result.fullError
    }

    program.unsafeRunSync() must beEqualTo("Hello")
  }

  def simpleProcessSinkError = {
    val builder = new StringBuilder()

    val target: Pipe[IO, Byte, Unit] = _.evalMap(byte => IO { builder.append(byte.toChar) }.void)
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("perl", List("-e", """print STDERR "Hello"""")) redirectErrorTo target).start(blocker)
        _ <- running.waitForExit()
      } yield builder.toString
    }

    program.unsafeRunSync() must beEqualTo("Hello")
  }

  def simpleProcessStreamInput = {
    val source = Stream("This is a test string").through(text.utf8Encode)
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("wc", List("-w")) < source > text.utf8Decode[IO]).start(blocker)
        result <- running.waitForExit()
      } yield result.fullOutput.trim
    }

    program.unsafeRunSync() must beEqualTo("5")
  }

  def flushChunks = {
    val source: Stream[IO, Byte] = Stream("This ", "is a test", " string").through(text.utf8Encode)
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("wc", List("-w")) < FlushChunks(source) > text.utf8Decode[IO]).start(blocker)
        result <- running.waitForExit()
      } yield result.fullOutput.trim
    }

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessFileInput = {
    val tempFile = Files.createTempFile("prox", "txt")
    Files.write(tempFile, "This is a test string".getBytes("UTF-8"))
    val pipedProcess = Process("cat") | Process("wc", List("-w"))
    val program = Blocker[IO].use { blocker =>
      for {
        runningProcesses <- (pipedProcess < tempFile > text.utf8Decode[IO]).start(blocker)
        (runningCat, runningWc) = runningProcesses
        _ <- runningCat.waitForExit()
        wcResult <- runningWc.waitForExit()
      } yield wcResult.fullOutput.trim
    }

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessStreamInput = {
    val source: Stream[IO, Byte] = Stream("This is a test string").through(text.utf8Encode)
    val pipedProcess = Process("cat") | Process("wc", List("-w"))
    val program = Blocker[IO].use { blocker =>
      for {
        runningProcesses <- (pipedProcess < source > text.utf8Decode[IO]).start(blocker)
        (runningCat, runningWc) = runningProcesses
        _ <- runningCat.waitForExit()
        wcResult <- runningWc.waitForExit()
      } yield wcResult.fullOutput.trim
    }

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessStreamError = {
    val program = Blocker[IO].use { blocker =>
      for {
        rps <- ((Process("true") | Process("perl", List("-e", """print STDERR "Hello""""))) redirectErrorTo text.utf8Decode[IO]).start(blocker)
        (_, running) = rps
        result <- running.waitForExit()
      } yield result.fullError
    }

    program.unsafeRunSync() must beEqualTo("Hello")
  }

  def simpleProcessPiping = {
    val program = Blocker[IO].use { blocker =>
      for {
        rps <- (Process("echo", List("This is a test string")) | (Process("wc", List("-w")) > text.utf8Decode[IO])).start(blocker)
        (runningEcho, runningWc) = rps
        _ <- runningEcho.waitForExit()
        wcResult <- runningWc.waitForExit()
      } yield wcResult.fullOutput.trim
    }

    program.unsafeRunSync() must beEqualTo("5")
  }

  def customProcessPiping = {
    val customPipe: Pipe[IO, Byte, Byte] =
      (s: Stream[IO, Byte]) => s
        .through(text.utf8Decode)
        .through(text.lines)
        .map(_.split(' ').toVector)
        .map(v => v.map(_ + " !!!").mkString(" "))
        .intersperse("\n")
        .through(text.utf8Encode)

    val program = Blocker[IO].use { blocker =>
      for {
        rps <- (Process("echo", List("This is a test string")).via(customPipe).to(Process("wc", List("-w")) > text.utf8Decode[IO])).start(blocker)
        (runningEcho, runningWc) = rps
        _ <- runningEcho.waitForExit()
        wcResult <- runningWc.waitForExit()
      } yield wcResult.fullOutput.trim
    }

    program.unsafeRunSync() must beEqualTo("11")
  }

  def simpleProcessPipingHList = {
    val program = Blocker[IO].use { blocker =>
      for {
        rpHL <- (Process("echo", List("This is a test string")) | (Process("wc", List("-w")) > text.utf8Decode[IO])).startHL(blocker)
        runningEcho = rpHL.head
        runningWc = rpHL.tail.head
        _ <- runningEcho.waitForExit()
        wcResult <- runningWc.waitForExit()
      } yield wcResult.fullOutput.trim
    }

    program.unsafeRunSync() must beEqualTo("5")
  }

  def multiProcessPiping = {
    val program = Blocker[IO].use { blocker =>
      for {
        rps <- (Process("echo", List("cat\ncat\ndog\napple")) | Process("sort") | (Process("uniq", List("-c")) > text.utf8Decode[IO])).start(blocker)
        (runningEcho, runningSort, runningUniq) = rps
        _ <- runningEcho.waitForExit()
        _ <- runningSort.waitForExit()
        uniqResult <- runningUniq.waitForExit()
      } yield uniqResult.fullOutput.linesIterator.map(_.trim).toList
    }

    program.unsafeRunSync() must beEqualTo(List("1 apple", "2 cat", "1 dog"))
  }

  def multiProcessPipingWithErrorRedir = {
    val errorTarget = ToVector(text.utf8Decode[IO].andThen(text.lines[IO]))
    val program = Blocker[IO].use { blocker =>
      for {
        rps <- ((Process("perl", List("-e", """print STDERR "Hello\nworld"""")) redirectErrorTo errorTarget) | (Process("sort") redirectErrorTo errorTarget) | (Process("uniq", List("-c")) redirectErrorTo errorTarget)).start(blocker)
        (runningPerl, runningSort, runningUniq) = rps
        perlResult <- runningPerl.waitForExit()
        sortResult <- runningSort.waitForExit()
        uniqResult <- runningUniq.waitForExit()
      } yield (perlResult.fullError, sortResult.fullError, uniqResult.fullError)
    }

    program.unsafeRunSync() must beEqualTo((Vector("Hello", "world"), Vector.empty, Vector.empty))
  }

  def isAlive = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- Process("sleep", List("10")).start(blocker)
        isAliveBefore <- running.isAlive
        _ <- running.terminate()
        isAliveAfter <- running.isAlive
      } yield (isAliveBefore, isAliveAfter)
    }

    program.unsafeRunSync() must beEqualTo((true, false))
  }

  def terminateSignal = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0""")).start(blocker)
        _ <- IO {
          Thread.sleep(250);
        }
        result <- running.terminate()
      } yield (result.exitCode)
    }

    program.unsafeRunSync() must beEqualTo(1)
  }

  def killSignal = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- Process("perl", List("-e", """$SIG{TERM} = 'IGNORE'; sleep 30; exit 2""")).start(blocker)
        _ <- IO {
          Thread.sleep(250);
        }
        result <- running.kill()
      } yield (result.exitCode)
    }

    program.unsafeRunSync() must beEqualTo(137)
  }

  def customEnvVariables = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- ((Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")) `with` ("TEST1" -> "world") `with` ("TEST2" -> "prox")) > text.utf8Decode[IO]).start(blocker)
        result <- running.waitForExit()
      } yield result.fullOutput
    }

    program.unsafeRunSync() must beEqualTo("Hello world! I am prox!\n")
  }

  def outFoldMonoid = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("echo", List("Hello\nworld!")) > text.utf8Decode[IO].andThen(text.lines[IO])).start(blocker)
        result <- running.waitForExit()
      } yield result.fullOutput
    }

    program.unsafeRunSync() must beEqualTo("Helloworld!")
  }

  case class StringLength(value: Int)

  def outLogNonMonoid = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("echo", List("Hello\nworld!")) > text.utf8Decode[IO].andThen(text.lines[IO]).andThen(_.map(s => StringLength(s.length)))).start(blocker)
        result <- running.waitForExit()
      } yield result.fullOutput
    }

    program.unsafeRunSync() must beEqualTo(Vector(StringLength(5), StringLength(6), StringLength(0)))
  }

  def outLogMonoid = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("echo", List("Hello\nworld!")) > ToVector(text.utf8Decode[IO].andThen(text.lines[IO]).andThen(_.map(_.length)))).start(blocker)
        result <- running.waitForExit()
      } yield result.fullOutput
    }

    program.unsafeRunSync() must beEqualTo(Vector(5, 6, 0))
  }

  def outIgnore = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("echo", List("Hello\nworld!")) > Drain(text.utf8Decode[IO].andThen(text.lines[IO]))).start(blocker)
        result <- running.waitForExit()
      } yield result.fullOutput
    }

    program.unsafeRunSync() must beEqualTo(())
  }

  def outCustomFold = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("echo", List("Hello\nworld!")) >
          Fold(text.utf8Decode[IO].andThen(text.lines[IO]), Vector.empty, (l: Vector[Option[Char]], s: String) => l :+ s.headOption)).start(blocker)
        result <- running.waitForExit()
      } yield result.fullOutput
    }

    program.unsafeRunSync() must beEqualTo(Vector(Some('H'), Some('w'), None))
  }

  def errFoldMonoid = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("perl", List("-e", "print STDERR 'Hello\nworld!\n'")) redirectErrorTo text.utf8Decode[IO].andThen(text.lines[IO])).start(blocker)
        result <- running.waitForExit()
      } yield result.fullError
    }

    program.unsafeRunSync() must beEqualTo("Helloworld!")
  }

  def errLogNonMonoid = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("perl", List("-e", "print STDERR 'Hello\nworld!\n'")) redirectErrorTo text.utf8Decode[IO].andThen(text.lines[IO]).andThen(_.map(s => StringLength(s.length)))).start(blocker)
        result <- running.waitForExit()
      } yield result.fullError
    }

    program.unsafeRunSync() must beEqualTo(Vector(StringLength(5), StringLength(6), StringLength(0)))
  }

  def errLogMonoid = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("perl", List("-e", "print STDERR 'Hello\nworld!\n'")) redirectErrorTo ToVector(text.utf8Decode[IO].andThen(text.lines[IO]).andThen(_.map(_.length)))).start(blocker)
        result <- running.waitForExit()
      } yield result.fullError
    }

    program.unsafeRunSync() must beEqualTo(Vector(5, 6, 0))
  }

  def errIgnore = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("perl", List("-e", "print STDERR 'Hello\nworld!\n'")) redirectErrorTo Drain(text.utf8Decode[IO].andThen(text.lines[IO]))).start(blocker)
        result <- running.waitForExit()
      } yield result.fullError
    }

    program.unsafeRunSync() must beEqualTo(())
  }

  def errCustomFold = {
    val program = Blocker[IO].use { blocker =>
      for {
        running <- (Process("perl", List("-e", "print STDERR 'Hello\nworld!\n'")) redirectErrorTo
          Fold(text.utf8Decode[IO].andThen(text.lines[IO]), Vector.empty, (l: Vector[Option[Char]], s: String) => l :+ s.headOption)).start(blocker)
        result <- running.waitForExit()
      } yield result.fullError
    }

    program.unsafeRunSync() must beEqualTo(Vector(Some('H'), Some('w'), None))
  }

  def doubleOutputRedirectIsIllegal = {
    illTyped("""val bad = (Process("echo", List("Hello world!")) > new File("x").toPath) > new File("y").toPath""")
    ok
  }

  def doubleInputRedirectIsIllegal = {
    illTyped("""val bad = (Process("wc", List("-w")) < Stream.eval(IO("X")).through(text.utf8Encode)) < Stream.eval(IO("Y")).through(text.utf8Encode)""")
    ok
  }

  def doubleErrorRedirectIsIllegal = {
    illTyped("""val bad = (Process("echo", List("Hello world!")) redirectErrorTo (new File("x").toPath)) redirectErrorTo (new File("y").toPath)""")
    ok
  }

  def pipingRedirectedOutputIsIllegal = {
    illTyped("""val bad = (Process("echo", List("Hello world!")) > new File("x").toPath) | Process("wc", List("-w"))""")
    ok
  }

  def pipingToRedirectedInputIsIllegal = {
    illTyped("""val bad = Process("echo", List("Hello world!")) | (Process("wc", List("-w")) < new File("x").path)""")
    ok
  }
}
