package io.github.vigoo.prox

import java.nio.file.Path

import cats.effect.IO
import fs2.{Pipe, Sink, Stream}

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

object implicits {

  implicit val pathAsTarget: CanBeProcessOutputTarget[Path] =
    (path: Path) => new FileTarget(path)

  implicit val pathAsErrorTarget: CanBeProcessErrorTarget[Path] =
    (path: Path) => new FileTarget(path)

  implicit val pathAsSource: CanBeProcessInputSource[Path] =
    (path: Path) => new FileSource(path)

  implicit def streamAsSource: CanBeProcessInputSource[Stream[IO, Byte]] =
    (source: Stream[IO, Byte]) => new InputStreamingSource(source)

  implicit def pipeAsTarget: CanBeProcessOutputTarget[Pipe[IO, Byte, Byte]] =
    (pipe: Pipe[IO, Byte, Byte]) => new OutputStreamingTarget(pipe)

  implicit def sinkAsTarget(implicit executionContext: ExecutionContext): CanBeProcessOutputTarget[Sink[IO, Byte]] =
    (sink: Sink[IO, Byte]) => new OutputStreamingTarget(in =>
      in.observe(sink)
    )

  implicit def pipeAsErrorTarget: CanBeProcessErrorTarget[Pipe[IO, Byte, Byte]] =
    (pipe: Pipe[IO, Byte, Byte]) => new ErrorStreamingTarget(pipe)

  implicit def sinkAsErrorTarget(implicit executionContext: ExecutionContext): CanBeProcessErrorTarget[Sink[IO, Byte]] =
    (sink: Sink[IO, Byte]) => new ErrorStreamingTarget(in =>
      in.observe(sink)
    )

  implicit class ProcessNodeOutputRedirect[PN <: ProcessNode[_, NotRedirected, _]](processNode: PN) {
    def >[To: CanBeProcessOutputTarget](to: To): PN#RedirectedOutput = {
      processNode.unsafeChangeRedirectedOutput(to)
    }
  }

  implicit class ProcessNodeInputRedirect[PN <: ProcessNode[NotRedirected, _, _]](processNode: PN) {
    def <[From: CanBeProcessInputSource](from: From): PN#RedirectedInput= {
      processNode.unsafeChangeRedirectedInput(from)
    }
  }

  implicit class ProcessNodeErrorRedirect[PN <: ProcessNode[_, _, NotRedirected]](processNode: PN) {
    def redirectErrorTo[To: CanBeProcessErrorTarget](to: To): PN#RedirectedError = {
      processNode.unsafeChangeRedirectedError(to)
    }
  }
}
