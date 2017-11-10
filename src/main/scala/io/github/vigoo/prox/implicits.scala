package io.github.vigoo.prox

import java.nio.file.Path

import cats.effect.IO
import fs2.{Pipe, Sink, Stream}

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

object implicits {

  implicit val pathAsTarget: CanBeProcessOutputTarget[Path] =
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
}
