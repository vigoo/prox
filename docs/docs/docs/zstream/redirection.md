---
layout: docs
title: Redirection
---

# Redirecting input, output and error

```scala mdoc:invisible
import io.github.vigoo.prox._
import io.github.vigoo.prox.zstream._
```

Similarly to [customization](customize), redirection is also implemented with _capability traits_.
The `ProcessIO` type returned by the `Process` constructor implements all the three redirection capability
traits:

- `RedirectableInput` marks that the standard input of the process is not bound yet
- `RedirectableOutput` marks that the standard output of the process is not bound yet
- `RedirectableError` marks that the standard error output of the process is not bound yet

Each of the three channels can be **only redirected once**. The result type of each redirection method no longer
implements the given capability.

Let's see an example of this (redirection methods are described below on this page):

```scala mdoc
import zio._
import zio.stream._
import zio.prelude._

val proc1 = Process("echo", List("Hello world"))
val proc2 = proc1 ># ZPipeline.utf8Decode
```

It is no longer possible to redirect the output of `proc2`:

```scala mdoc:fail
val proc3 = proc2 >? (ZPipeline.utf8Decode >>> ZPipeline.splitLines) 
```

Many redirection methods have an _operator_ version but all of them have alphanumberic
variants as well.

### Input redirection
Input redirection is enabled by the `RedirectableInput` trait. The following operations
are supported:

| operator | alternative  | parameter type                  | what it does  |
|----------|--------------|---------------------------------|---------------|
| `<`      | `fromFile`   | `java.nio.file.Path`            | Natively attach a source file to STDIN  |
| `<`      | `fromStream` | `ZStream[Any, ProxError, Byte]` | Attach a _ZIO byte stream_ to STDIN |
| `!<`     | `fromStream` | `ZStream[Any, ProxError, Byte]` | Attach a _ZIO byte stream_ to STDIN and **flush** after each chunk |

### Output redirection
Output redirection is enabled by the `RedirectableOutput` trait. 
The following operations are supported:

| operator | alternative    | parameter type                                                                 | result type | what it does  |
|----------|----------------|--------------------------------------------------------------------------------|-------------| --------------|
| `>`      | `toFile`       | `java.nio.file.Path`                                                           | `Unit`      | Natively attach STDOUT to a file |
| `>>`     | `appendToFile` | `java.nio.file.Path`                                                           | `Unit`      | Natively attach STDOUT to a file in append mode |
| `>`      | `toSink`       | `TransformAndSink[Byte, _]`                                                    | `Unit`      | Drains the STDOUT through the given sink |
| `>#`     | `toFoldMonoid` | `[O: Identity](ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`   | `O`         | Sends STDOUT through the stream and folds the result using its _monoid_ instance
| `>?`     | `toVector`     | `ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`                 | `Vector[O]` | Sends STDOUT through the stream and collects the results |
|          | `drainOutput`  | `ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`                 | `Unit`      | Drains the STDOUT through the given stream |
|          | `foldOutput`   | `ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O]), R, (R, O) => R` | `R`         | Sends STDOUT through the stream and folds the result using a custom fold function |

All the variants that accept a _stream transformation_ (`ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`) are also usable by directly passing
a `ZPipeline`.

`TransformAndSink` encapsulates a _stream transformation_ and a _unit sink_. It is possible to use a sink directly if transformation is not needed.

```scala
case class TransformAndSink[A, B](transform: ZStream[Any, ProxError, A] => ZStream[Any, ProxError, B],
                                  sink: ZSink[Any, ProxError, B, Any, Unit])
```

### Error redirection
Error redirection is enabled by the `RedirectableError` trait. 
The following operations are supported:

| operator  | alternative         | parameter type                                                                 | result type | what it does  |
|-----------|---------------------|--------------------------------------------------------------------------------|-------------| --------------|
| `!>`      | `errorToFile`       | `java.nio.file.Path`                                                           | `Unit`      | Natively attach STDERR to a file |
| `!>>`     | `appendErrorToFile` | `java.nio.file.Path`                                                           | `Unit`      | Natively attach STDERR to a file in append mode |
| `!>`      | `errorToSink`       | `TransformAndSink[Byte, _]`                                                    | `Unit`      | Drains the STDERR through the given sink |
| `!>#`     | `errorToFoldMonoid` | `[O: Monoid](ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`     | `O`         | Sends STDERR through the pipe and folds the result using its _monoid_ instance
| `!>?`     | `errorToVector`     | `ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`                 | `Vector[O]` | Sends STDERR through the pipe and collects the results |
|           | `drainError`        | `ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`                 | `Unit`      | Drains the STDERR through the given pipe |
|           | `foldError`         | `ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O]), R, (R, O) => R` | `R`         | Sends STDERR through the pipe and folds the result using a custom fold function |

### Redirection for process groups 
[Process groups](processgroups) are two or more processes attached together through pipes.
This connection is internally implemented using the above described redirection capabilities. 
This means that all but the first process has their _inputs_ bound, and all but the last one has
their _outputs_ bound. Redirection of input and output for a _process group_ is thus a well defined
operation meaning redirection of input of the _first_ process and redirection of output of the _last process_.

For this reason the class created via _process piping_ implements the `RedirectableInput` and 
`RedirectableOutput` traits described above.

For the sake of simplicity the library does not support anymore the fully customizable
per-process error redirection for process groups, but a reduced but still quite expressive 
version described by the `RedirectableErrors` trait.

The methods in this trait define error redirection for **all process in the group at once**:

| operator  | alternative          | parameter type                                                                 | result type | what it does  |
|-----------|----------------------|--------------------------------------------------------------------------------|-------------| --------------|
| `!>`      | `errorsToSink`       | `TransformAndSink[Byte, _]`                                                    | `Unit`      | Drains the STDERR through the given sink |
| `!>#`     | `errorsToFoldMonoid` | `[O: Monoid](ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`     | `O`         | Sends STDERR through the stream and folds the result using its _monoid_ instance
| `!>?`     | `errorsToVector`     | `ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`                 | `Vector[O]` | Sends STDERR through the stream and collects the results |
|           | `drainErrors`        | `ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`                 | `Unit`      | Drains the STDERR through the given stream |
|           | `foldErrors`         | `ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O]), R, (R, O) => R` | `R`         | Sends STDERR through the stream and folds the result using a custom fold function |

Redirection to file is not possible through this interface as only a single path could be
provided.
The result of these redirections is accessible through the `ProcessGroupResult` interface as 
it is described in the [running processes section](running).

By using the `RedirectableErrors.customizedPerProcess` interface (having the type
`RedirectableErrors.CustomizedPerProcess`) it is possible to customize the redirection 
targets per process while keeping their types uniform:

| operator  | alternative          | parameter type                                                                            | result type | what it does  |
|-----------|----------------------|-------------------------------------------------------------------------------------------|-------------| --------------|
|           | `errorsToFile`       | `Process => java.nio.file.Path`                                                           | `Unit`      | Natively attach STDERR to a file |
|           | `appendErrorsToFile` | `Process => java.nio.file.Path`                                                           | `Unit`      | Natively attach STDERR to a file in append mode |
|           | `errorsToSink`       | `Process => TransformAndSink[Byte, _]`                                                    | `Unit`      | Drains the STDERR through the given sink |
|           | `errorsToFoldMonoid` | `Process => [O: Monoid](ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`     | `O`         | Sends STDERR through the stream and folds the result using its _monoid_ instance
|           | `errorsToVector`     | `Process => ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`                 | `Vector[O]` | Sends STDERR through the stream and collects the results |
|           | `drainErrors`        | `Process => ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O])`                 | `Unit`      | Drains the STDERR through the given stream |
|           | `foldErrors`         | `Process => ZStream[Any, ProxError, Byte] => ZStream[Any, ProxError, O]), R, (R, O) => R` | `R`         | Sends STDERR through the stream and folds the result using a custom fold function |

Let's see an example of how this works!

First we define a queue where we want to send _error lines_ from all the involved
processes, then we define the two processes separately, connect them with a pipe and 
customize the error redirection where we prefix the parsed lines based on which
process they came from:


```scala mdoc:silent

for {
  errors <- Queue.unbounded[String]
  parseLines = (s: ZStream[Any, ProxError, Byte]) => s.via(ZPipeline.utf8Decode.mapError(UnknownProxError.apply) >>> ZPipeline.splitLines)
 
  p1 = Process("proc1")
  p2 = Process("proc2")
  group = (p1 | p2).customizedPerProcess.errorsToSink {
    case p if p == p1 => TransformAndSink(parseLines.andThen(_.map(s => "P1: " + s)), ZSink.foreach(errors.offer))
    case p if p == p2 => TransformAndSink(parseLines.andThen(_.map(s => "P2: " + s)), ZSink.foreach(errors.offer))
  }
} yield ()
```

### Creating reusable functions
The `Process` object contains several useful _type aliases_ for writing functions that work with any process by
only specifying what redirection channels we want _unbounded_. 

The `UnboundProcess` represents a process which is fully unbound, no redirection has been done yet. It is 
defined as follows:

```scala
type UnboundProcess = Process[Unit, Unit]
    with RedirectableInput[UnboundOEProcess]
    with RedirectableOutput[UnboundIEProcess[*]]
    with RedirectableError[UnboundIOProcess[*]]
```

where `UnboundIOProcess[E]` for example represents a process which has its _error output_ already bound.

These type aliases can be used to define functions performing redirection on arbitrary processes, for example:

```scala mdoc
def logErrors[P <: Process.UnboundEProcess[_]](proc: P) = {
   val target = TransformAndSink(
     ZPipeline.utf8Decode.mapError(UnknownProxError.apply) >>> ZPipeline.splitLines,
     ZSink.foreach((line: String) => ZIO.debug(line))) 
   proc !> target 
}

val proc4 = logErrors(Process("something"))
``` 