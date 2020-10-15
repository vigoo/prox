---
layout: docs
title: Customizing environment
section: zstream
---

# Customizing the environment

```scala mdoc:invisible
import io.github.vigoo.prox.zstream._
``` 

The type returned by the `Process` constructor also implements the `ProcessConfiguration` trait,
adding three methods that can be used to customize the working environment of the process to be started:

### Working directory

The `in` method can be used to customize the working directory:

```scala mdoc
import io.github.vigoo.prox.path._

val dir = home / "tmp"
val proc1 = Process("ls") in dir 
```

Not that `dir` has the type `java.nio.file.Path`, and the `home / tmp` syntax is just a thin 
syntax extension to produce such values.

### Adding environment variables

The `with` method can be used to add environment variables to the process in the following
way:

```scala mdoc
val proc2 = Process("echo", List("$TEST")) `with` ("TEST" -> "Hello world")
```

### Removing environment variables

The subprocess inherits the parent process environment, so it may be necessary to
_remove_ some already defined environment variables with the `without` method:

```scala mdoc
val proc3 = Process("echo" , List("$PATH")) `without` "PATH"
``` 

### Writing reusable functions 

Because these methods are part of the `ProcessConfiguration` _capability_, writing reusable functions require us to define
a polymorphic function that requires this capability:

```scala mdoc
import java.nio.file.Path

def withHome[P <: ProcessLike with ProcessLikeConfiguration](home: Path, proc: P): P#Self = 
  proc `with` ("HOME" -> home.toString)
```

Then we can use it on any kind of process or process group (read about [redirection](redirection) to understand
why there are multiple concrete process types):

```scala mdoc
val proc4 = Process("echo", List("$HOME"))
val proc5 = withHome(home, proc4)

val group1 = Process("grep", List("ERROR")) | Process("sort")
val group2 = withHome(home, group1)
```