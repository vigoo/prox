package io.github.vigoo.prox

import java.nio.file.{Path, Paths}

/** Small helper package to work with Java NIO paths */
package object path {
  /** The home directory */
  val home: Path = Paths.get(java.lang.System.getProperty("user.home"))

  /** The root directory */
  val root: Path = Paths.get("/")

  /** Extension methods for [[java.nio.file.Path]] */
  implicit class PathOps(value: Path) {
    /** Resolves a child of the given path
      *
      * @param child The child's name
      * @return Returns the path to the given child
      */
    def /(child: String): Path = value.resolve(child)
  }

}
