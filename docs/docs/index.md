---
layout: docs
title: Getting started
---

# Getting started with prox

First add `prox` as a dependency:

```sbt
libraryDependencies += "io.github.vigoo" %% "prox" % "0.5.2"
```

and use the following imports:

```scala mdoc:invisible
import cats.effect._
import scala.concurrent.ExecutionContext
```

```scala mdoc
import io.github.vigoo.prox._
import io.github.vigoo.prox.syntax._
``` 

### Defining a process to run
In prox a process to be executed is defined by a pure value which implementes the `Process[F, O, E]` trait.
The type parameters have the following meaning:

- `F` is the effect type, for example `IO` when using _cats-effect_ or `Task` when using _ZIO_ with its _cats-effect interop_
- `O` is the type of the output value after the system process has finished running
- `E` is the type of the error output value after the system process has finished running

> :bulb: Why does it need the `F` in the process type and not just for running? 
> The reason is that it describes the stream _execution_ as well in case the output is 
> redirected. 

We require `F` to implement the `Concurrent` type class, and for that we have to have an implicit
_context shifter_ in scope (this should be already available in an application using cats-effect):

```scala mdoc:silent
implicit val contextShift = IO.contextShift(ExecutionContext.global)
```
  
To create a simple process to be executed use the `Process` constructor:

```scala mdoc
val proc1 = Process[IO]("ls", List("-hal"))
```

or we can use the `cats.effect.IO`-specific _string interpolator_:

```scala mdoc
import io.github.vigoo.prox.syntax.catsInterpolation._

val proc2 = proc"ls -hal"
```

Then we can
- [customize the process execution](customize) by for example setting environment variables and working directory
- and [redirect the input, output and error](redirection) channels of the process
- [pipe two or more processes together](processgroups) 

still staying on purely specification level.

### Running the process

Once we have our process specification ready, we can _start_ the process with one of the
IO functions on process.

But for this we first have to have a `ProcessRunner` implementation in scope. The default 
one is called `JVMProcessRunner` and it can be created in the following way:

```scala mdoc:silent
implicit val runner: ProcessRunner[IO, JVMProcessInfo] = new JVMProcessRunner 
```

Read the [custom process runners](custom-runners) page for an example of using a customized runner.

With the runner in place we can use [several methods to start the process](running). 
The simplest one is called `run` and it blocks the active thread until the process finishes
running:

```scala mdoc
Blocker[IO].use { blocker =>
  proc1.run(blocker)
}
```

The result of this IO action is a `ProcessResult[O, E]`, with the ability to observe the 
_exit code_ and the redirected output and error values. In our first example both `O` and
`E` were `Unit` because the default is to redirect output and error to the _standard output_ and
_standard error_ streams.
