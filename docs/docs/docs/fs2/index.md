---
layout: docs
title: Getting started
---

# Getting started with prox

First add one of the `prox` interfaces as a dependency:

```sbt
libraryDependencies += "io.github.vigoo" %% "prox-fs2" % "0.7.0"
```

or for Cats Effect 3.x / FS2 3.x:

```sbt
libraryDependencies += "io.github.vigoo" %% "prox-fs2-3" % "0.7.0"
```

and, assuming that we have a long living `Blocker` thread pool defined already, we can create
the `Prox` module: 

```scala mdoc:invisible
import cats.effect._
import scala.concurrent.ExecutionContext
import io.github.vigoo.prox._

implicit val contextShift = IO.contextShift(ExecutionContext.global)
val (blocker, _) = Blocker[IO].allocated.unsafeRunSync()
```

```scala mdoc
val prox = ProxFS2[IO](blocker)
import prox._
``` 

We require `F` to implement the `Concurrent` type class, and for that we have to have an implicit
_context shifter_ in scope (this should be already available in an application using cats-effect).

### Defining a process to run
In prox a process to be executed is defined by a pure value which implements the `Process[O, E]` trait.
The type parameters have the following meaning:

- `O` is the type of the output value after the system process has finished running
- `E` is the type of the error output value after the system process has finished running
  
To create a simple process to be executed use the `Process` constructor:

```scala mdoc
val proc1 = Process("ls", List("-hal"))
```

or we can use the _string interpolator_:

```scala mdoc
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
implicit val runner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner 
```

Read the [custom process runners](custom-runners) page for an example of using a customized runner.

With the runner in place we can use [several methods to start the process](running). 
The simplest one is called `run` and it blocks the active thread until the process finishes
running:

```scala mdoc
proc1.run()
```

The result of this IO action is a `ProcessResult[O, E]`, with the ability to observe the 
_exit code_ and the redirected output and error values. In our first example both `O` and
`E` were `Unit` because the default is to redirect output and error to the _standard output_ and
_standard error_ streams.
