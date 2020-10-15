---
layout: docs
title: Migration
---
# Migration

### from 0.1.x to 0.2

- The `start` method on processes now requires a `blockingExecutionContext` argument
- `Ignore` has been renamed to `Drain`
- `Log` has been renamed to `ToVector`

### from 0.2 to 0.4

- `Process` now takes the effect type as parameter, so in case of cats-effect, `Process(...)` becomes `Process[IO](...)`
- The `start` method on processes now gets a `Blocker` instead of an execution context

### from 0.4 to 0.5

0.5 is a complete rewrite of the original library, and the API changed a lot, especially
if the process types were used in code to pass around / wrap them. Please refer to the other
sections of the documentation to learn how to reimplement them. For simple use cases where
constructing and running the processes directly the main differences are:

- Different operators / methods for different source and target types, see [the page about redirection](redirection)
- The need of an implicit [process runner](running) in scope
- New ways to start and wait for the process, see [the page about runnning processes](running)

### from 0.5 to 0.6

0.6 introduces the native ZIO/ZStream version of the library. For existing code the following differences apply:

- Instead of `prox`, the artifact is now called `prox-fs2`
- Instead of _global imports_, the FS2 prox module now has to be constructed with the `FS2` constructor and the API is imported from that
- Because the `FS2` module captures the `F[_]` and the `Blocker`, they are no longer needed to pass on to the API functions and types
