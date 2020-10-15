---
layout: docs
title: Process groups
---

# Connecting processes together via pipes
```scala mdoc:invisible
import cats.effect._
import scala.concurrent.ExecutionContext
import io.github.vigoo.prox._

implicit val contextShift = IO.contextShift(ExecutionContext.global)
val (blocker, _) = Blocker[IO].allocated.unsafeRunSync()

val prox = ProxFS2[IO](blocker)
import prox._
``` 

Connecting one process to another means that the standard output of the first process
gets redirected to the standard input of the second process. This is implemented using
the redirection capabilities described [on the redirection page](redirection). The result
of connecting one process to another is called a _process group_ and it implements the 
trait `ProcessGroup[O, E]`.

To create a process group, either:
- Use the `|` or `via` methods between two **unbounded** processes
- Use the `|` or `via` methods between an **unbounded** process group and an **unbounded** process 

It is important that the process group construction must always happen before any redirection,
the type system enforces this by requiring the involved processes to be `UnboundedProcess`.

> :bulb: `Process.UnboundedProcess` is a type alias for a process with all the redirection capabilities

Let's see an example of simply piping:

```scala mdoc:silent
val group1 = Process("grep", List("ERROR")) | Process("sort")
val group2 = group1 | Process("uniq", List("-c"))
```

A custom pipe (when using `via`) can be anything of the type `Pipe[F, Byte, Byte]`. The
following not very useful example capitalizes each word coming through:

```scala mdoc:silent
val customPipe: fs2.Pipe[IO, Byte, Byte] =
    (s: fs2.Stream[IO, Byte]) => s
      .through(fs2.text.utf8Decode) // decode UTF-8
      .through(fs2.text.lines)      // split to lines
      .map(_.split(' ').toVector)   // split lines to words
      .map(v => v.map(_.capitalize).mkString(" "))
      .intersperse("\n")            // remerge lines 
      .through(fs2.text.utf8Encode) // encode as UTF-8

val group3 = Process("echo", List("hello world")).via(customPipe).to(Process("wc", List("-w")))
```