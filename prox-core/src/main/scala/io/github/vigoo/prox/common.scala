package io.github.vigoo.prox

import java.nio.file.Path

trait CommonModule {
  this: ProxRuntime =>

  /** Common base trait for processes and process groups, used in constraints in the redirection traits */
  trait ProcessLike

  trait ProcessLikeConfiguration {
    val workingDirectory: Option[Path]
    val environmentVariables: Map[String, String]
    val removedEnvironmentVariables: Set[String]

    type Self <: ProcessLikeConfiguration

    protected def applyConfiguration(workingDirectory: Option[Path],
                                     environmentVariables: Map[String, String],
                                     removedEnvironmentVariables: Set[String]): Self

    /**
      * Changes the working directory of the process
      *
      * @param workingDirectory the working directory
      * @return a new process with the working directory set
      */
    def in(workingDirectory: Path): Self =
      applyConfiguration(workingDirectory = Some(workingDirectory), environmentVariables, removedEnvironmentVariables)

    /**
      * Use the inherited working directory of the process instead of an explicit one
      *
      * @return a new process with the working directory cleared
      */
    def inInheritedWorkingDirectory(): Self =
      applyConfiguration(workingDirectory = None, environmentVariables, removedEnvironmentVariables)

    /**
      * Adds an environment variable to the process
      *
      * @param nameValuePair A pair of name and value
      * @return a new process with the working directory set
      */
    def `with`(nameValuePair: (String, String)): Self =
      applyConfiguration(workingDirectory, environmentVariables = environmentVariables + nameValuePair, removedEnvironmentVariables)

    /**
      * Removes an environment variable from the process
      *
      * Usable to remove variables inherited from the parent process.
      *
      * @param name Name of the environment variable
      * @return a new process with the working directory set
      */
    def without(name: String): Self =
      applyConfiguration(workingDirectory, environmentVariables, removedEnvironmentVariables = removedEnvironmentVariables + name)

  }
}