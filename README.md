# prox
[![Build Status](https://travis-ci.org/vigoo/prox.svg?branch=master)](https://travis-ci.org/vigoo/prox)
[![codecov](https://codecov.io/gh/vigoo/prox/branch/master/graph/badge.svg)](https://codecov.io/gh/vigoo/prox)
[![Apache 2 License License](http://img.shields.io/badge/license-APACHE2-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)

**prox** is a small library that helps you starting system processes and redirecting their input/output/error streams,
either to files, [fs2](https://github.com/functional-streams-for-scala/fs2) streams or each other.

It works by first defining a one or more processes then starting them, getting a set of **running processes**.

## Usage

### Getting started

Let's start by defining a single process with the `Process` constructor, taking a command and optionally a list of arguments and a working directory:

```scala
import io.github.vigoo.prox._
import io.github.vigoo.prox.syntax._

val process = Process("echo", List("Hello world"))
```

This is just a definition of a process, no real effect happened yet. We can *start* this process by using the `start` method on it, which creates an *effectful operation* in the IO monad, defined by [cats-effect](https://github.com/typelevel/cats-effect):

```scala
import scala.concurrent.ExecutionContext.Implicits.global

val runningProcess: IO[RunningProcess] = process.start
```

This, when executed, will start the above defined process and return an instance of `RunningProcess`, which allows things like waiting for the process to be terminated, or kill it. 

Let's see a full example of running the above process and printing it's exit code!

```scala
val program = 
    for {
      echo <- Process("echo", List("Hello world")).start
      result <- echo.waitForExit()
    } yield result.exitCode
    
val exitCode = program.unsafeRunSync()
``` 

### Redirection
Let's take a look at the type of `process` we defined above:

```scala
process: Process[NotRedirected, NotRedirected, NotRedirected]
```

The three type parameters indicate the redirection status of the processes *input*, *output* and *error* streams. The default is that they are *not redirected*, inheriting the parent processes streams.

Each stream can be redirected **at most once** using the `<`, `>` and `errorTo` operators. The target of these redirections are described by three type classes: `CanBeProcessOutputTarget`, `CanBeProcessErrorTarget` and `CanBeProcessInputSource`.

One type with such instances is `Path`. Let's how to redirect the output:

```scala
import io.github.vigoo.prox.path._

val process = Process("echo", List("Hello world")) > (home / "tmp" / "out.txt")
``` 

Running this process will redirect the process' output to the given file directly using the *Java process builder API'. We can't use this operator twice as it would be ambigous (and outputting to multiple files directly is not supported by the system), so the following does not typecheck:

```scala
val process = Process("echo", List("Hello world")) > (home / "tmp" / "out.txt") > (home / "tmp" / "out2.txt")
```

Similarly we can redirect the input and the error:

```scala
val p1 = Process("cat") < (home / "something")
val p2 = Process("make") errorTo (home / "errors.log")
```

### Streams
**[fs2](https://github.com/functional-streams-for-scala/fs2) streams** of bytes can be used as *inputs* for processes in the same way:

```scala
import fs2._

val source = Stream("Hello world").through(text.utf8Encode)
val printSource = Process("cat") < source
```

Similarly the output can be redirected to a **pipe** as following:

```scala
val captured = Process("cat") < source > identity[Stream[IO, Byte]]
```

Calling `start` on a process which has its streams connected to [fs2](https://github.com/functional-streams-for-scala/fs2) streams sets up the *IO operation* and starts the *input streams*, but the *output streams* must be executed manually 
as the task requires. For example to send a string through `cat` and capture the output we 
have to run fold the output stream, exposed on 
the `RunningProcess` interface:

```scala
val source = Stream("Hello world").through(text.utf8Encode)
val program: IO[String] = for {
  runningProcess <- (Process("cat") < source > text.utf8Decode[IO]).start
  contents <- running.output.runFoldMonoid
  _ <- runningProcess.waitForExit()
} yield contents
```

### Piping
The library also provides a way to **pipe two or more processes together**. This is implemented by the *stream support* above internally.

Let's start by piping two processes together:

```scala
val echoProcess = Process("echo", List("This is an output"))
val wordCountProcess = Process("wc", List("-w"))
val combined = echoProcess | wordCountProcess
```

The combined process is no longer a `Process`; it is a `PipedProcess`, but otherwise it works exactly the same, you can redirect its input and outputs or pipe it to another process:

```scala
val multipleProcesses = Process("cat", List("log.txt")) | Process("grep", List("ERROR")) | Process("sort") | Process("uniq", List("-c"))
```

The `start` method for piped processes no longer returns a single `IO[RunningProcess]`, but a *tuple* containing all the `RunningProcess`
instances for the involved processes:

```scala
for {
  runningProcs1 <- (echoProcess | wordCountProcess).start
  (echo, wordCount) = runningProcs1
  
  runningProcs2 <- multipleProcesses.start
  (cat, grep, sort, uniq) = runningProcs2
  
  _ <- wordCount.waitForExit()
  _ <- uniq.waitForExit()
} yield ()
```
