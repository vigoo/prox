---
layout: docs
title: Running processes
section: zstream
---

# Running processes and process groups
```scala mdoc:invisible
import io.github.vigoo.prox._
import io.github.vigoo.prox.zstream._
```

There are three methods for running a _process_:

- The `run` method is the simplest one, it starts the process and then blocks the current fiber until it terminates
- The `start` method starts the process and returns a fiber packed into a resource. The fiber finishes when the process terminates. Canceling the fiber terminates the process.
- The `startProcess` method returns a `RunningProcess[O, E]` interface that allows advanced some operations

Similarly for a _process group_, there is a `run`, a `start` and a `startProcessGroup` method but with different result types.

Let's see some examples!

```scala mdoc:silent
implicit val runner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner 

val process = Process("echo", List("hello"))

val result1 = process.run()
val result2 = process.start().use { fiber =>
  fiber.join
}

val result3 = 
  for { 
    runningProcess <- process.startProcess()
    _ <- runningProcess.kill()
  } yield ()
```

Both `RunningProcess` and `RunningProcessGroup` has the following methods:
- `waitForExit()` waits until the process terminates
- `terminate()` sends `SIGTERM` to the process
- `kill()` sends `SIGKILL` to the process

In addition `RunningProcess` also defines an `isAlive` check.

### Process execution result
The result of a process is represented by `ProcessResult[O, E]` defined as follows:

```scala
trait ProcessResult[+O, +E] {
  val exitCode: ExitCode
  val output: O
  val error: E
}
```

The type and value of `output` and `error` depends on what [redirection was defined](redirection) on the process.

### Process group execution result 
The result of a process group is represented by `ProcessGroupResult[O, E]`:

```scala
trait ProcessGroupResult[+O, +E] {
  val exitCodes: Map[Process[Unit, Unit], ExitCode]
  val output: O
  val errors: Map[Process[Unit, Unit], E]
}
```

The keys of the maps are the original _process_ values used in the piping operations.