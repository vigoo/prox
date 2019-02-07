package io.github.vigoo.prox

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl._
import akka.util.ByteString
import cats.effect.{ContextShift, IO}
import cats.implicits._
import org.specs2.Specification

import scala.concurrent.ExecutionContext.Implicits.global
import shapeless.test.illTyped
import syntax._

import scala.concurrent.{ExecutionContext, Future}

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
  implicit val actorSystem: ActorSystem = ActorSystem("test")
  implicit val materializer: Materializer = ActorMaterializer()

  val utf8Decode: Sink[ByteString, Future[String]] =
    Flow[ByteString].reduce(_ ++ _).map(_.utf8String).toMat(Sink.head)(Keep.right)

  def simpleProcessGetExitCode = {
    val program = for {
      trueRunning <- Process("true").start()
      falseRunning <- Process("false").start()
      trueResult <- trueRunning.waitForExit()
      falseResult <- falseRunning.waitForExit()
    } yield (trueResult.exitCode, falseResult.exitCode)
    program.unsafeRunSync() must beEqualTo((0, 1))
  }

  def workingDirectoryWorks = {
    val tempDirectory = Files.createTempDirectory("prox")
    val program = for {
      pwdRunning <- ((Process("pwd") in tempDirectory) > utf8Decode).start()
      pwdResult <- pwdRunning.waitForExit()
    } yield pwdResult.fullOutput.trim

    program.unsafeRunSync() must beOneOf(tempDirectory.toString, s"/private${tempDirectory}")
  }

  def simpleProcessFileOutput = {
    val tempFile = File.createTempFile("test", "txt")
    val program = for {
      running <- (Process("echo", List("Hello world!")) > tempFile.toPath).start()
      _ <- running.waitForExit()
      contents <- IO(new String(Files.readAllBytes(tempFile.toPath), StandardCharsets.UTF_8))
    } yield contents

    program.unsafeRunSync() must beEqualTo("Hello world!\n")
  }

  def simpleProcessStreamOutput = {
    val program = for {
      running <- (Process("echo", List("Hello world!")) > utf8Decode).start()
      result <- running.waitForExit()
    } yield result.fullOutput

    program.unsafeRunSync() must beEqualTo("Hello world!\n")
  }

  def simpleProcessSinkOutput = {
    val builder = StringBuilder.newBuilder

    val target: Sink[ByteString, Future[Done]] = Sink.foreach((bs: ByteString) => builder.append(bs.utf8String))
    val program = for {
      running <- (Process("echo", List("Hello world!")) > target).start()
      _ <- running.waitForExit()
    } yield builder.toString()

    program.unsafeRunSync() must beEqualTo("Hello world!\n")
  }

  def simpleProcessStreamError = {
    val program = for {
      running <- (Process("perl", List("-e", """print STDERR "Hello"""")) redirectErrorTo utf8Decode).start()
      result <- running.waitForExit()
    } yield result.fullError

    program.unsafeRunSync() must beEqualTo("Hello")
  }

  def simpleProcessSinkError = {
    val builder = StringBuilder.newBuilder

    val target: Sink[ByteString, Future[Done]] = Sink.foreach((bs: ByteString) => builder.append(bs.utf8String))
    val program = for {
      running <- (Process("perl", List("-e", """print STDERR "Hello"""")) redirectErrorTo target).start()
      _ <- running.waitForExit()
    } yield builder.toString

    program.unsafeRunSync() must beEqualTo("Hello")
  }

  def simpleProcessStreamInput = {
    val source: Source[ByteString, Any] = Source.single("This is a test string").map(ByteString.apply)
    val program = for {
      running <- (Process("wc", List("-w")) < source > utf8Decode).start()
      result <- running.waitForExit()
    } yield result.fullOutput.trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessFileInput = {
    val tempFile = Files.createTempFile("prox", "txt")
    Files.write(tempFile, "This is a test string".getBytes("UTF-8"))
    val pipedProcess = Process("cat") | Process("wc", List("-w"))
    val program = for {
      runningProcesses <- (pipedProcess < tempFile > utf8Decode).start()
      (runningCat, runningWc) = runningProcesses
      _ <- runningCat.waitForExit()
      wcResult <- runningWc.waitForExit()
    } yield wcResult.fullOutput.trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessStreamInput = {
    val source: Source[ByteString, Any] = Source.single("This is a test string").map(ByteString.apply)
    val pipedProcess = Process("cat") | Process("wc", List("-w"))
    val program = for {
      runningProcesses <- (pipedProcess < source > utf8Decode).start()
      (runningCat, runningWc) = runningProcesses
      _ <- runningCat.waitForExit()
      wcResult <- runningWc.waitForExit()
    } yield wcResult.fullOutput.trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def pipedProcessStreamError = {
    val program = for {
      rps <- ((Process("true") | Process("perl", List("-e", """print STDERR "Hello""""))) redirectErrorTo utf8Decode).start()
      (_, running) = rps
      result <- running.waitForExit()
    } yield result.fullError

    program.unsafeRunSync() must beEqualTo("Hello")
  }

  def simpleProcessPiping = {
    val program = for {
      rps <- (Process("echo", List("This is a test string")) | (Process("wc", List("-w")) > utf8Decode)).start()
      (runningEcho, runningWc) = rps
      _ <- runningEcho.waitForExit()
      wcResult <- runningWc.waitForExit()
    } yield wcResult.fullOutput.trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def customProcessPiping = {
    val customPipe = Framing.delimiter(
      delimiter = ByteString("\n"),
      maximumFrameLength = 10000,
      allowTruncation = true
    ).map(_.utf8String)
      .map(_.split(' ').toVector)
      .map(v => v.map(_ + " !!!").mkString(" "))
      .intersperse("\n")
      .map(ByteString.apply)

    val program = for {
      rps <- (Process("echo", List("This is a test string")).via(customPipe).to(Process("wc", List("-w")) > utf8Decode)).start()
      (runningEcho, runningWc) = rps
      _ <- runningEcho.waitForExit()
      wcResult <- runningWc.waitForExit()
    } yield wcResult.fullOutput.trim

    program.unsafeRunSync() must beEqualTo("10")
  }

  def simpleProcessPipingHList = {
    val program = for {
      rpHL <- (Process("echo", List("This is a test string")) | (Process("wc", List("-w")) > utf8Decode)).startHL()
      runningEcho = rpHL.head
      runningWc = rpHL.tail.head
      _ <- runningEcho.waitForExit()
      wcResult <- runningWc.waitForExit()
    } yield wcResult.fullOutput.trim

    program.unsafeRunSync() must beEqualTo("5")
  }

  def multiProcessPiping = {
    val program = for {
      rps <- (Process("echo", List("cat\ncat\ndog\napple")) | Process("sort") | (Process("uniq", List("-c")) > utf8Decode)).start()
      (runningEcho, runningSort, runningUniq) = rps
      _ <- runningEcho.waitForExit()
      _ <- runningSort.waitForExit()
      uniqResult <- runningUniq.waitForExit()
    } yield uniqResult.fullOutput.lines.map(_.trim).toList

    program.unsafeRunSync() must beEqualTo(List("1 apple", "2 cat", "1 dog"))
  }

  def multiProcessPipingWithErrorRedir = {
    val errorTarget = ToVector(
      Framing.delimiter(
        delimiter = ByteString("\n"),
        maximumFrameLength = 100000,
        allowTruncation = true).map(_.utf8String)
    )
    val program = for {
      rps <- ((Process("perl", List("-e", """print STDERR "Hello\nworld"""")) redirectErrorTo errorTarget) | (Process("sort") redirectErrorTo errorTarget) | (Process("uniq", List("-c")) redirectErrorTo errorTarget)).start()
      (runningPerl, runningSort, runningUniq) = rps
      perlResult <- runningPerl.waitForExit()
      sortResult <- runningSort.waitForExit()
      uniqResult <- runningUniq.waitForExit()
    } yield (perlResult.fullError, sortResult.fullError, uniqResult.fullError)

    program.unsafeRunSync() must beEqualTo((Vector("Hello", "world"), Vector.empty, Vector.empty))
  }

  def isAlive = {
    val program = for {
      running <- Process("sleep", List("10")).start()
      isAliveBefore <- running.isAlive
      _ <- running.terminate()
      isAliveAfter <- running.isAlive
    } yield (isAliveBefore, isAliveAfter)

    program.unsafeRunSync() must beEqualTo((true, false))
  }

  def terminateSignal = {
    val program = for {
      running <- Process("perl", List("-e", """$SIG{TERM} = sub { exit 1 }; sleep 30; exit 0""")).start()
      _ <- IO { Thread.sleep(250); }
      result <- running.terminate()
    } yield (result.exitCode)

    program.unsafeRunSync() must beEqualTo(1)
  }

  def killSignal = {
    val program = for {
      running <- Process("perl", List("-e", """$SIG{TERM} = 'IGNORE'; sleep 30; exit 2""")).start()
      _ <- IO { Thread.sleep(250); }
      result <- running.kill()
    } yield (result.exitCode)

    program.unsafeRunSync() must beEqualTo(137)
  }

  def customEnvVariables = {
    val program = for {
      running <- ((Process("sh", List("-c", "echo \"Hello $TEST1! I am $TEST2!\"")) `with` ("TEST1" -> "world") `with` ("TEST2" -> "prox")) > utf8Decode).start()
      result <- running.waitForExit()
    } yield result.fullOutput

    program.unsafeRunSync() must beEqualTo("Hello world! I am prox!\n")
  }

  def outFoldMonoid = {
    val program = for {
      running <- (Process("echo", List("Hello\nworld!")) > Framing.delimiter(ByteString("\n"), 10000, true).map(_.utf8String)).start()
      result <- running.waitForExit()
    } yield result.fullOutput

    program.unsafeRunSync() must beEqualTo("Helloworld!")
  }

  case class StringLength(value: Int)

  def outLogNonMonoid = {
    val program = for {
      running <- (Process("echo", List("Hello\nworld!")) > Framing.delimiter(ByteString("\n"), 10000, true).map(_.utf8String).map(s => StringLength(s.length))).start()
      result <- running.waitForExit()
    } yield result.fullOutput

    program.unsafeRunSync() must beEqualTo(Vector(StringLength(5), StringLength(6)))
  }

  def outLogMonoid = {
    val program = for {
      running <- (Process("echo", List("Hello\nworld!")) > ToVector(Framing.delimiter(ByteString("\n"), 10000, true).map(_.utf8String).map(_.length))).start()
      result <- running.waitForExit()
    } yield result.fullOutput

    program.unsafeRunSync() must beEqualTo(Vector(5, 6))
  }

  def outIgnore = {
    val program = for {
      running <- (Process("echo", List("Hello\nworld!")) > Drain(Framing.delimiter(ByteString("\n"), 10000, true).map(_.utf8String))).start()
      result <- running.waitForExit()
    } yield result.fullOutput

    program.unsafeRunSync() must beEqualTo(())
  }

  def outCustomFold = {
    val program = for {
      running <- (Process("echo", List("Hello\nworld!")) >
        Fold(Framing.delimiter(ByteString("\n"), 10000, true).map(_.utf8String), Vector.empty, (l: Vector[Option[Char]], s: String) => l :+ s.headOption)).start()
      result <- running.waitForExit()
    } yield result.fullOutput

    program.unsafeRunSync() must beEqualTo(Vector(Some('H'), Some('w')))
  }

  def errFoldMonoid = {
    val program = for {
      running <- (Process("perl", List("-e", "print STDERR 'Hello\nworld!\n'")) redirectErrorTo Framing.delimiter(ByteString("\n"), 10000, true).map(_.utf8String)).start()
        result <- running.waitForExit()
    } yield result.fullError

    program.unsafeRunSync() must beEqualTo("Helloworld!")
  }

  def errLogNonMonoid = {
    val program = for {
      running <- (Process("perl", List("-e", "print STDERR 'Hello\nworld!\n'")) redirectErrorTo Framing.delimiter(ByteString("\n"), 10000, true).map(_.utf8String).map(s => StringLength(s.length))).start()
      result <- running.waitForExit()
    } yield result.fullError

    program.unsafeRunSync() must beEqualTo(Vector(StringLength(5), StringLength(6)))
  }

  def errLogMonoid = {
    val program = for {
      running <- (Process("perl", List("-e", "print STDERR 'Hello\nworld!\n'")) redirectErrorTo ToVector(Framing.delimiter(ByteString("\n"), 10000, true).map(_.utf8String).map(_.length))).start()
      result <- running.waitForExit()
    } yield result.fullError

    program.unsafeRunSync() must beEqualTo(Vector(5, 6))
  }

  def errIgnore = {
    val program = for {
      running <- (Process("perl", List("-e", "print STDERR 'Hello\nworld!\n'")) redirectErrorTo Drain(Framing.delimiter(ByteString("\n"), 10000, true).map(_.utf8String))).start()
      result <- running.waitForExit()
    } yield result.fullError

    program.unsafeRunSync() must beEqualTo(())
  }

  def errCustomFold = {
    val program = for {
      running <- (Process("perl", List("-e", "print STDERR 'Hello\nworld!\n'")) redirectErrorTo
        Fold(Framing.delimiter(ByteString("\n"), 10000, true).map(_.utf8String), Vector.empty, (l: Vector[Option[Char]], s: String) => l :+ s.headOption)).start()
      result <- running.waitForExit()
    } yield result.fullError

    program.unsafeRunSync() must beEqualTo(Vector(Some('H'), Some('w')))
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
