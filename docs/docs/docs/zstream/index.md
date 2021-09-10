---
layout: docs
title: Getting started
---

# Getting started with prox

First add one of the `prox` interfaces as a dependency:

```sbt
libraryDependencies += "io.github.vigoo" %% "prox-zstream" % "0.7.3"
```

and import the ZIO specific API from: 

```scala mdoc
import io.github.vigoo.prox._
import io.github.vigoo.prox.zstream._
``` 

There is also an experimental version for ZIO 2, based on it's snapshot releases:

```sbt
libraryDependencies += "io.github.vigoo" %% "prox-zstream-2" % "0.7.3"
```

The code snippets in the documentation are based on the ZIO 1 version. 

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
