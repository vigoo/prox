package io.github.vigoo.prox

import java.io.File
import java.nio.file.Paths

import org.specs2.Specification
import path._

// scalastyle:off public.methods.have.type
// scalastyle:off public.member.have.type

class PathSpecs extends Specification { def is = s2"""
  The path package
    helps to get the root path        $rootPath
    helps to get the home directory   $homeDirectory
    enables composition with /        $composition
  """

  def rootPath = {
    root.getRoot must beEqualTo(root)
  }

  def homeDirectory = {
    val homeProp = System.getProperty("user.home")
    home.toFile.getAbsolutePath must beEqualTo(homeProp)
  }

  def composition = {
    val a = Paths.get("/first")
    (a / "second").toFile.getAbsolutePath must beEqualTo(s"/first${File.separator}second")
  }
}
