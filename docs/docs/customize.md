---
layout: docs
title: Customizing environment
---

# Customizing the environment


```scala mdoc:invisible
import cats.effect._
import scala.concurrent.ExecutionContext
import io.github.vigoo.prox._
import io.github.vigoo.prox.syntax._

implicit val contextShift = IO.contextShift(ExecutionContext.global)
```

The type returned by the `Process` constructor also implements the `ProcessConfiguration` trait,
adding three methods that can be used to customize the working environment of the process to be started:

### Working directory

The `in` method can be used to customize the working directory:

```scala mdoc
import io.github.vigoo.prox.path._

val dir = home / "tmp"
val proc1 = Process[IO]("ls") in dir 
```

Not that `dir` has the type `java.nio.file.Path`, and the `home / tmp` syntax is just a thin 
syntax extension to produce such values.

### Adding environment variables

The `with` method can be used to add environment variables to the process in the following
way:

```scala mdoc
val proc2 = Process[IO]("echo", List("$TEST")) `with` ("TEST" -> "Hello world")
```

### Removing environment variables

The subprocess inherits the parent process environment so it may be necessary to
_remove_ some already defined environment variables with the `without` method:

```scala mdoc
val proc3 = Process[IO]("echo" , List("$PATH")) `without` "PATH"
``` 

### Writing reusable functions 

Because these methods are not part of the `Process` trait itself but of the 
`ProcessConfiguration` _capability_, writing reusable functions require us to define
a polymorphic function that requires this capability:

```scala mdoc
import java.nio.file.Path

class Decorators[F[_]] private {
  def withHome[P <: Process[F, _, _] with ProcessConfiguration[F, P]](home: Path, proc: P): P = 
    proc `with` ("HOME" -> home.toString)
}
object Decorators {
  def apply[F[_]]: Decorators[F] = new Decorators
} 
```

We also created a wrapper class to fix the effect type as it is not inferable from the two parameters `Path` and `P`.
Then we can use it on any kind of process (read about [redirection](redirection) to understand
why there are multiple concrete process types):

```scala mdoc
val proc4 = Process[IO]("echo", List("$HOME"))
val proc5 = Decorators[IO].withHome(home, proc4)
```
