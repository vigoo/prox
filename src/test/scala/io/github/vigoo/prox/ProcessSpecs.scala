package io.github.vigoo.prox

import java.io.File
import java.nio.file.{Files, Paths}

import cats.effect.IO
import cats.implicits._
import fs2._
import org.specs2.Specification

import scala.concurrent.ExecutionContext.Implicits.global
import shapeless.test.illTyped
import syntax._
import path._

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

  The DSL prevents
    redirecting the output twice                    $doubleOutputRedirectIsIllegal
    redirecting the input twice                     $doubleInputRedirectIsIllegal
    redirecting the error twice                     $doubleErrorRedirectIsIllegal
    piping a process with redirected output         $pipingRedirectedOutputIsIllegal
    piping to a process with redirected input       $pipingToRedirectedInputIsIllegal
  """

  def simpleProcessGetExitCode = {
    val program = for {
      trueRunning <- Process("true").start
      falseRunning <- Process("false").start
      trueResult <- trueRunning.waitForExit()
      falseResult <- falseRunning.waitForExit()
    } yield (trueResult.exitCode, falseResult.exitCode)
    program.unsafeRunSync() must beEqualTo((0, 1))
  }

  def workingDirectoryWorks = {
    val tempDirectory = Files.createTempDirectory("prox")
    val program = for {
      pwdRunning <- ((Process("pwd") in tempDirectory) > text.utf8Decode[IO]).start
      pwdResult <- pwdRunning.waitForExit()
    } yield pwdResult.fullOutput.trim

    program.unsafeRunSync() must beOneOf(tempDirectory.toString, s"/private${tempDirectory}")
  }

  def simpleProcessFileOutput = {
    val tempFile = File.createTempFile("test", "txt")
    val program = for {
      running <- (Process("echo", List("Hello world!")) > tempFile.toPath).start
      _ <- running.waitForExit()
      contents <- io.file.readAll[IO](tempFile.toPath, 1024).through(text.utf8Decode).runFoldMonoid
    } yield contents

    program.unsafeRunSync() must beEqualTo("Hello world!\n")
  }

  def simpleProcessStreamOutput = {
    val program = for {
      running <- (Process("echo", List("Hello world!")) > text.utf8Decode[IO]).start
      result <- running.waitForExit()
    } yield result.fullOutput

    program.unsafeRunSync() must beEqualTo("Hello world!\n")
  }

  def simpleProcessSinkOutput = {
    val builder = StringBuilder.newBuilder

    val target: Sink[IO, Byte] = Sink(byte => IO { builder.append(byte.toChar) })
    val program = for {
      running <- (Process("echo", List("Hello world!")) > target).start
      _ <- running.waitForExit()
    } yield builder.toString()

    program.unsafeRunSync() must beEqualTo("Hello world!\n")
  }

  def simpleProcessStreamError = {
    val program = for {
      running <- (Process("perl", List("-e", """print STDERR "Hello"""")) redirectErrorTo text.utf8Decode[IO]).start
      result <- running.waitForExit()
    } yield result.fullError

    program.unsafeRunSync() must beEqualTo("Hello")
  }

  def simpleProcessSinkError = {
    val builder = StringBuilder.newBuilder

    val target: Sink[IO, Byte] = Sink(byte => IO { builder.append(byte.toChar) })
    val program = for {
      running <- (Process("perl", List("-e", """print STDERR "Hello"""")) redirectErrorTo target).start
      _ <- running.waitForExit()
    } yield builder.toString

    program.unsafeRunSync() must beEqualTo("Hello")
  }

  def simpleProcessStreamInput = {
    val source = Stream("This is a test string").through(text.utf8Encode)
    val program = for {
      running <- (Process("wc", List("-w")) < source > text.utf8Decode[IO]).start
      result <- running.waitForExit()
    } yield result.fullOutput.trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessFileInput = {
    val tempFile = Files.createTempFile("prox", "txt")
    Files.write(tempFile, "This is a test string".getBytes("UTF-8"))
    val pipedProcess = Process("cat") | Process("wc", List("-w"))
    val program = for {
      runningProcesses <- (pipedProcess < tempFile > text.utf8Decode[IO]).start
      (runningCat, runningWc) = runningProcesses
      _ <- runningCat.waitForExit()
      wcResult <- runningWc.waitForExit()
    } yield wcResult.fullOutput.trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessStreamInput = {
    val source: Stream[IO, Byte] = Stream("This is a test string").through(text.utf8Encode)
    val pipedProcess = Process("cat") | Process("wc", List("-w"))
    val program = for {
      runningProcesses <- (pipedProcess < source > text.utf8Decode[IO]).start
      (runningCat, runningWc) = runningProcesses
      _ <- runningCat.waitForExit()
      wcResult <- runningWc.waitForExit()
    } yield wcResult.fullOutput.trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessStreamError = {
    val program = for {
      rps <- ((Process("true") | Process("perl", List("-e", """print STDERR "Hello""""))) redirectErrorTo text.utf8Decode[IO]).start
      (_, running) = rps
      result <- running.waitForExit()
    } yield result.fullError

    program.unsafeRunSync() must beEqualTo("Hello")
  }

  def simpleProcessPiping = {
    val program = for {
      rps <- (Process("echo", List("This is a test string")) | (Process("wc", List("-w")) > text.utf8Decode[IO])).start
      (runningEcho, runningWc) = rps
      _ <- runningEcho.waitForExit()
      wcResult <- runningWc.waitForExit()
    } yield wcResult.fullOutput.trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def simpleProcessPipingHList = {
    val program = for {
      rpHL <- (Process("echo", List("This is a test string")) | (Process("wc", List("-w")) > text.utf8Decode[IO])).startHL
      runningEcho = rpHL.head
      runningWc = rpHL.tail.head
      _ <- runningEcho.waitForExit()
      wcResult <- runningWc.waitForExit()
    } yield wcResult.fullOutput.trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def multiProcessPiping = {
    val program = for {
      rps <- (Process("echo", List("cat\ncat\ndog\napple")) | Process("sort") | (Process("uniq", List("-c")) > text.utf8Decode[IO])).start
      (runningEcho, runningSort, runningUniq) = rps
      _ <- runningEcho.waitForExit()
      _ <- runningSort.waitForExit()
      uniqResult <- runningUniq.waitForExit()
    } yield uniqResult.fullOutput.lines.map(_.trim).toList

    program.unsafeRunSync() must beEqualTo(List("1 apple", "2 cat", "1 dog"))
  }

  def multiProcessPipingWithErrorRedir = {
    val errorTarget = Log(text.utf8Decode[IO].andThen(text.lines[IO]))
    val program = for {
      rps <- ((Process("perl", List("-e", """print STDERR "Hello\nworld"""")) redirectErrorTo errorTarget) | (Process("sort") redirectErrorTo errorTarget) | (Process("uniq", List("-c")) redirectErrorTo errorTarget)).start
      (runningPerl, runningSort, runningUniq) = rps
      perlResult <- runningPerl.waitForExit()
      sortResult <- runningSort.waitForExit()
      uniqResult <- runningUniq.waitForExit()
    } yield (perlResult.fullError, sortResult.fullError, uniqResult.fullError)

    program.unsafeRunSync() must beEqualTo((Vector("Hello", "world"), Vector.empty, Vector.empty))
  }

  def isAlive = {
    val program = for {
      running <- Process("cat").start
      isAliveBefore <- running.isAlive
      _ <- running.terminate()
      isAliveAfter <- running.isAlive
    } yield (isAliveBefore, isAliveAfter)

    program.unsafeRunSync() must beEqualTo((true, false))
  }

  def terminateSignal = {
    val program = for {
      running <- Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0""")).start
      _ <- IO { Thread.sleep(250); }
      result <- running.terminate()
    } yield (result.exitCode)

    program.unsafeRunSync() must beEqualTo(1)
  }

  def killSignal = {
    val program = for {
      running <- Process("perl", List("-e", """$SIG{TERM} = 'IGNORE'; sleep 30; exit 2""")).start
      _ <- IO { Thread.sleep(250); }
      result <- running.kill()
    } yield (result.exitCode)

    program.unsafeRunSync() must beEqualTo(137)
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
