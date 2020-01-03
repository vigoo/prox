# prox
[![Build Status](https://travis-ci.org/vigoo/prox.svg?branch=master)](https://travis-ci.org/vigoo/prox)
[![codecov](https://codecov.io/gh/vigoo/prox/branch/master/graph/badge.svg)](https://codecov.io/gh/vigoo/prox)
[![Apache 2 License License](http://img.shields.io/badge/license-APACHE2-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)
[![Latest version](https://index.scala-lang.org/vigoo/prox/prox/latest.svg)](https://index.scala-lang.org/vigoo/prox/prox)
![Maven central](https://img.shields.io/maven-central/v/io.github.vigoo/prox_2.13.svg?style=flat-square)

**prox** is a small library that helps you starting system processes and redirecting their input/output/error streams,
either to files, [fs2](https://github.com/functional-streams-for-scala/fs2) streams or each other.

**TODO** Version 0.5 is a complete redesign of the library. Documentation is coming.

## Migration

### from 0.1.x to 0.2

- The `start` method on processes now requires a `blockingExecutionContext` argument
- `Ignore` has been renamed to `Drain`
- `Log` has been renamed to `ToVector`

### from 0.2 to 0.4

- `Process` now takes the effect type as parameter, so in case of cats-effect, `Process(...)` becomes `Process[IO](...)`
- The `start` method on processes now gets a `Blocker` instead of an execution context

### from 0.4 to 0.5
TODO