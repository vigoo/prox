package io.github.vigoo.prox.examples

import java.nio.file.{Path, Paths}

import cats.effect.{Blocker, Concurrent, ExitCode, IO, IOApp}
import cats.implicits._
import fs2.concurrent.Queue
import io.github.vigoo.prox._
import io.github.vigoo.prox.path._
import io.github.vigoo.prox.syntax._
import fs2._

/**
  * Example showing how to communicate with an interactive REPL implemented in Python
  */
object InteractivePythonProcess extends IOApp {
  def println(s: String): IO[Unit] =
    IO(Predef.println(s))

  /**
    * Wrapper for defining Python processes, equivalent to "activating the virtualenv"
    */
  def pythonProcess[F[_] : Concurrent](virtualenvRoot: Path, scriptFileName: Path, args: List[String] = List.empty): Process[F, Byte, Byte, Unit, Unit, NotRedirected, NotRedirected, NotRedirected] = {
    val bin = virtualenvRoot / "bin"
    val python = bin / "python"
    Process[F](python.toString, scriptFileName.toString :: args) `with`
      ("PATH" -> (bin.toString + ":" + System.getenv("PATH"))) `with`
      ("VIRTUAL_ENV" -> virtualenvRoot.toString) without "PYTHONHOME"
  }

  /** Simple interface for communicating with the external process */
  trait ExternalProcess {
    def sendAndReceive(message: String): IO[String]
    def stop(): IO[ExitCode]
  }

  def startExternalProcess(workingDir: Path, virtualenvRoot: Path, scriptPath: Path): IO[ExternalProcess] = {
    Blocker[IO].use { blocker =>
      for {
        inputQueue <- Queue.unbounded[IO, String] // queue storing commands to be sent
        outputQueue <- Queue.noneTerminated[IO, String] // queue storing answers from the process, None represents end of stream

        // input and output are the byte streams connectable to the prox Process
        input = inputQueue.dequeue.through(text.utf8Encode)
        output = text.utf8Decode
          .andThen((s: Stream[IO, String]) => s.through(text.lines))
          .andThen(_.noneTerminate)
          .andThen(outputQueue.enqueue)

        // Input uses the FlushChunks modifier to avoid flush after each chunk (default buffer size in JRE is 8k)
        process = (pythonProcess[IO](virtualenvRoot, scriptPath) in workingDir) < FlushChunks(input) > output

        _ <- println("Starting external process...")
        python <- process.start(blocker)

        // The external process pipe uses the two defined queues as an interface
        pipe = (s: Stream[IO, String]) => s.through(inputQueue.enqueue).flatMap(_ => outputQueue.dequeue)
      } yield new ExternalProcess {
        override def sendAndReceive(message: String): IO[String] =
        // Sending a single \n terminated command and waiting until a single line answer arrives
          Stream.emit(message + "\n").through(pipe).take(1).compile.toList.map(_.head)

        override def stop(): IO[ExitCode] =
          for {
            // We assume that empty line triggers termination in the extenral process
            _ <- Stream.emit("\n").through(pipe).compile.drain
            result <- python.waitForExit()
          } yield ExitCode(result.exitCode)
      }
    }
  }

  override def run(args: List[String]): IO[ExitCode] =
    for {
      external <- startExternalProcess(
        workingDir = Paths.get(args.head), // pass the project root as argument
        virtualenvRoot = Paths.get("examples/externalpyproc/virtualenv"),
        scriptPath = Paths.get("examples/externalpyproc/test.py"))

      response <- external.sendAndReceive("Hello world")
      _ <- println(s"Response: $response")

      response2 <- external.sendAndReceive("Another message")
      _ <- println(s"Response: $response2")

      _ <- println(s"Asking the script to exit")
      exitCode <- external.stop()
      _ <- println(s"Result is $exitCode")
    } yield ExitCode.Success
}
