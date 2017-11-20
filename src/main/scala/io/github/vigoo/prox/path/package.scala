package io.github.vigoo.prox

import java.nio.file.{FileSystem, FileSystems, Path, Paths}

package object path {
  val home: Path = Paths.get(java.lang.System.getProperty("user.home"))
  val root: Path = Paths.get("/")

  implicit class PathOps(value: Path) {
    def /(child: String): Path = value.resolve(child)
  }
}
