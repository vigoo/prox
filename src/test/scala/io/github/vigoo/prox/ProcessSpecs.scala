package io.github.vigoo.prox

import java.io.File
import java.nio.file.Files

import cats.effect.IO
import cats.implicits._
import fs2._
import org.specs2.Specification

import scala.concurrent.ExecutionContext.Implicits.global
import implicits._

// scalastyle:off public.methods.have.type
// scalastyle:off public.member.have.type

class ProcessSpecs extends Specification { def is = s2"""
  A process can
    executed for getting it's exit code             $simpleProcessGetExitCode
    executed by redirecting it's output to a file   $simpleProcessFileOutput
    executed by redirecting it's output to a stream $simpleProcessStreamOutput
    executed by using a stream as it's input        $simpleProcessStreamInput
    be piped to another                             $simpleProcessPiping
  """

  def simpleProcessGetExitCode = {
    val program = for {
      trueRunning <- Process("true").start().map(_.asHList.head) // TODO: simplify
      falseRunning <- Process("false").start().map(_.asHList.head) // TODO: simplify
      trueResult <- trueRunning.waitForExit()
      falseResult <- falseRunning.waitForExit()
    } yield (trueResult.exitCode, falseResult.exitCode)
    program.unsafeRunSync() === (0, 1)
  }

  def simpleProcessFileOutput = {
    val tempFile = File.createTempFile("test", "txt")
    val program = for {
      running <- (Process("echo", List("Hello world!")) > tempFile.toPath).start().map(_.asHList.head) // TODO: simplify
      _ <- running.waitForExit()
      contents <- io.file.readAll[IO](tempFile.toPath, 1024).through(text.utf8Decode).runFoldMonoid
    } yield contents

    program.unsafeRunSync() === "Hello world!\n"
  }

  def simpleProcessStreamOutput = {
    val target: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    val program = for {
      running <- (Process("echo", List("Hello world!")) > target).start().map(_.asHList.head) // TODO: simplify
      _ <- running.waitForExit()
      contents <- running.output.through(text.utf8Decode).runFoldMonoid
    } yield contents

    program.unsafeRunSync() === "Hello world!\n"
  }

  def simpleProcessStreamInput = {
    val source: Stream[IO, Byte] = Stream("This is a test string").through(text.utf8Encode)
    val target: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    val program = for {
      running <- (Process("wc", List("-w")) < source > target).start().map(_.asHList.head) // TODO: simplify
      _ <- running.input.run
      contents <- running.output.through(text.utf8Decode).runFoldMonoid
      _ <- running.waitForExit()
    } yield contents.trim

    program.unsafeRunSync() === "5"
  }

  def simpleProcessPiping = {
    val target: Pipe[IO, Byte, Byte] = identity[Stream[IO, Byte]]
    val program = for {
      rps <- (Process("echo", List("This is a test string")) | (Process("wc", List("-w")) > target)).start().map(_.asHList.tupled)
      (runningEcho, runningWc) = rps
      contents <- runningWc.output.through(text.utf8Decode).runFoldMonoid
      _ <- runningEcho.waitForExit()
      _ <- runningWc.waitForExit()
    } yield contents.trim

    program.unsafeRunSync() === "5"
  }
}
