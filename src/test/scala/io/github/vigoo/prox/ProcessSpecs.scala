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
    executed by redirecting it's error to a stream                  $simpleProcessStreamError
    executed by using a stream as it's input                        $simpleProcessStreamInput
    piped to another                                                $simpleProcessPiping
    piped to another getting a HList of running processes           $simpleProcessPipingHList
    chained by multiple piping operations                           $multiProcessPiping
    chained by multiple piping operations, preserving error redir   $multiProcessPipingWithErrorRedir
    executed by redirecting it's output to a non-byte stream        $typedProcessStreamOutput
    executed by redirecting it's error to a non-byte stream         $typedProcessStreamError
    specified to run in a given directory                           $workingDirectoryWorks
    piped and then its input redirected to a stream                 $pipedProcessStreamInput
    piped and then its input redirected to a file                   $pipedProcessFileInput
    piped and then its error redirected to a stream                 $pipedProcessStreamError

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
      contents <- async.start(pwdRunning.output.runFoldMonoid)
      _ <- pwdRunning.waitForExit()
    } yield contents.unsafeRunSync().lines.toList.headOption

    program.unsafeRunSync() must beSome(tempDirectory.toString) or beSome(s"/private${tempDirectory}")
  }

  def simpleProcessFileOutput = {
    val tempFile = File.createTempFile("test", "txt")
    val program = for {
      running <- (Process("echo", List("Hello world!")) > tempFile.toPath).start
      contents <- async.start(io.file.readAll[IO](tempFile.toPath, 1024).through(text.utf8Decode).runFoldMonoid)
      _ <- running.waitForExit()
    } yield contents.unsafeRunSync()

    program.unsafeRunSync() must beEqualTo("Hello world!\n")
  }

  def simpleProcessStreamOutput = {
    val target: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    val program = for {
      running <- (Process("echo", List("Hello world!")) > target).start
      contents <- async.start(running.output.through(text.utf8Decode).runFoldMonoid)
      _ <- running.waitForExit()
    } yield contents.unsafeRunSync()

    program.unsafeRunSync() must beEqualTo("Hello world!\n")
  }

  def typedProcessStreamOutput = {
    val program = for {
      running <- (Process("echo", List("Hello world!")) > text.utf8Decode[IO]).start
      contents <- async.start(running.output.runFoldMonoid)
      _ <- running.waitForExit()
    } yield contents.unsafeRunSync()

    program.attempt.unsafeRunSync() must beRight("Hello world!\n")
  }

  def simpleProcessStreamError = {
    val target: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    val program = for {
      running <- (Process("perl", List("-e", """print STDERR "Hello"""")) redirectErrorTo target).start
      contents <- async.start(running.error.through(text.utf8Decode).runFoldMonoid)
      _ <- running.waitForExit()
    } yield contents.unsafeRunSync()

    program.unsafeRunSync() must beEqualTo("Hello")
  }

  def typedProcessStreamError = {
    val target: Pipe[IO, Byte, String] = text.utf8Decode.andThen(text.lines)
    val program = for {
      running <- (Process("perl", List("-e", """print STDERR "Hello\nworld"""")) redirectErrorTo target).start
      contents <- async.start(running.error.runLog)
      _ <- running.waitForExit()
    } yield contents.unsafeRunSync()

    program.unsafeRunSync() must beEqualTo(Vector("Hello", "world"))
  }

  def simpleProcessStreamInput = {
    val source = Stream("This is a test string").through(text.utf8Encode)
    val target: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    val program = for {
      running <- (Process("wc", List("-w")) < source > target).start
      contents <- async.start(running.output.through(text.utf8Decode).runFoldMonoid)
      _ <- running.waitForExit()
    } yield contents.unsafeRunSync().trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessFileInput = {
    val tempFile = Files.createTempFile("prox", "txt")
    Files.write(tempFile, "This is a test string".getBytes("UTF-8"))
    val pipedProcess = Process("cat") | Process("wc", List("-w"))
    val program = for {
      runningProcesses <- (pipedProcess < tempFile > text.utf8Decode[IO]).start
      (runningCat, runningWc) = runningProcesses
      contents <- async.start(runningWc.output.runFoldMonoid)
      _ <- runningCat.waitForExit()
      _ <- runningWc.waitForExit()
    } yield contents.unsafeRunSync().trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessStreamInput = {
    val source: Stream[IO, Byte] = Stream("This is a test string").through(text.utf8Encode)
    val pipedProcess = Process("cat") | Process("wc", List("-w"))
    val program = for {
      runningProcesses <- (pipedProcess < source > text.utf8Decode[IO]).start
      (runningCat, runningWc) = runningProcesses
      contents <- async.start(runningWc.output.runFoldMonoid)
      _ <- runningCat.waitForExit()
      _ <- runningWc.waitForExit()
    } yield contents.unsafeRunSync().trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessStreamError = {
    val program = for {
      rps <- ((Process("true") | Process("perl", List("-e", """print STDERR "Hello""""))) redirectErrorTo text.utf8Decode[IO]).start
      (_, running) = rps
      contents <- async.start(running.error.runFoldMonoid)
      _ <- running.waitForExit()
    } yield contents.unsafeRunSync()

    program.unsafeRunSync() must beEqualTo("Hello")
  }

  def simpleProcessPiping = {
    val target: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    val program = for {
      rps <- (Process("echo", List("This is a test string")) | (Process("wc", List("-w")) > target)).start
      (runningEcho, runningWc) = rps
      contents <- async.start(runningWc.output.through(text.utf8Decode).runFoldMonoid)
      _ <- runningEcho.waitForExit()
      _ <- runningWc.waitForExit()
    } yield contents.unsafeRunSync().trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def simpleProcessPipingHList = {
    val target: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    val program = for {
      rpHL <- (Process("echo", List("This is a test string")) | (Process("wc", List("-w")) > target)).startHL
      runningEcho = rpHL.head
      runningWc = rpHL.tail.head
      contents <- runningWc.output.through(text.utf8Decode).runFoldMonoid
      _ <- runningEcho.waitForExit()
      _ <- runningWc.waitForExit()
    } yield contents.trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def multiProcessPiping = {
    val target: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    val program = for {
      rps <- (Process("echo", List("cat\ncat\ndog\napple")) | Process("sort") | (Process("uniq", List("-c")) > target)).start
      (runningEcho, runningSort, runningUniq) = rps
      contents <- async.start(runningUniq.output.through(text.utf8Decode).runFoldMonoid)
      _ <- runningEcho.waitForExit()
      _ <- runningSort.waitForExit()
      _ <- runningUniq.waitForExit()
    } yield contents.unsafeRunSync().lines.map(_.trim).toList

    program.unsafeRunSync() must beEqualTo(List("1 apple", "2 cat", "1 dog"))
  }

  def multiProcessPipingWithErrorRedir = {
    val errorTarget: Pipe[IO, Byte, String] = text.utf8Decode.andThen(text.lines)
    val program = for {
      rps <- ((Process("perl", List("-e", """print STDERR "Hello\nworld"""")) redirectErrorTo errorTarget) | (Process("sort") redirectErrorTo errorTarget) | (Process("uniq", List("-c")) redirectErrorTo errorTarget)).start
      (runningPerl, runningSort, runningUniq) = rps
      contents <- async.start(runningUniq.output.through(text.utf8Decode).runFoldMonoid)
      perlErrors <- async.start(runningPerl.error.runLog)
      sortErrors <- async.start(runningSort.error.runLog)
      uniqErrors <- async.start(runningUniq.error.runLog)
      _ <- runningPerl.waitForExit()
      _ <- runningSort.waitForExit()
      _ <- runningUniq.waitForExit()
    } yield (perlErrors.unsafeRunSync(), sortErrors.unsafeRunSync(), uniqErrors.unsafeRunSync())

    program.unsafeRunSync() must beEqualTo((Vector("Hello", "world"), Vector.empty, Vector.empty))
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
