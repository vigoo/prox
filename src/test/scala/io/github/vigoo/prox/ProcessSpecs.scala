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
    ko
  }
}
