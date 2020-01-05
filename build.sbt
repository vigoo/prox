name := "prox"
description := "A Scala library for working with system processes"
organization := "io.github.vigoo"

dynverSonatypeSnapshots in ThisBuild := true

val scala212 = "2.12.10"
val scala213 = "2.13.1"

scalaVersion := scala213
crossScalaVersions := List(scala212, scala213)

addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "2.0.0",
  "co.fs2" %% "fs2-core" % "2.1.0",
  "co.fs2" %% "fs2-io" % "2.1.0",
  "com.chuusai" %% "shapeless" % "2.3.3",
  "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.3",

  "dev.zio" %% "zio" % "1.0.0-RC17" % "test",
  "dev.zio" %% "zio-test"     % "1.0.0-RC17" % "test",
  "dev.zio" %% "zio-test-sbt" % "1.0.0-RC17" % "test",
  "dev.zio" %% "zio-interop-cats" % "2.0.0.0-RC10" % "test",
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

coverageEnabled in(Test, compile) := true
coverageEnabled in(Compile, compile) := false

val scalacOptions212 = Seq("-Ypartial-unification", "-deprecation")
val scalacOptions213 = Seq("-deprecation")

scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, 12)) => scalacOptions212
  case Some((2, 13)) => scalacOptions213
  case _ => Nil
})

// Publishing

publishMavenStyle := true

licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

publishTo := sonatypePublishTo.value

import xerial.sbt.Sonatype._
sonatypeProjectHosting := Some(GitHubHosting("vigoo", "prox", "daniel.vigovszky@gmail.com"))

developers := List(
  Developer(id="vigoo", name="Daniel Vigovszky", email="daniel.vigovszky@gmail.com", url=url("https://vigoo.github.io"))
)

credentials ++=
  (for {
    username <- Option(System.getenv().get("SONATYPE_USERNAME"))
    password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
  } yield
    Credentials(
      "Sonatype Nexus Repository Manager",
      "oss.sonatype.org",
      username,
      password)).toSeq

enablePlugins(GhpagesPlugin)
enablePlugins(SiteScaladocPlugin)
enablePlugins(MicrositesPlugin)

git.remoteRepo := "git@github.com:vigoo/prox.git"

micrositeUrl := "https://vigoo.github.io"
micrositeBaseUrl := "/prox"
micrositeHomepage := "https://vigoo.github.io/prox/"
micrositeDocumentationUrl := "/prox/docs"
micrositeAuthor := "Daniel Vigovszky"
micrositeTwitterCreator := "@dvigovszky"
micrositeGithubOwner := "vigoo"
micrositeGithubRepo := "prox"
micrositeGitterChannel := false
micrositeDataDirectory := file("src/site/data")
micrositeStaticDirectory := file("src/site/static")
micrositeImgDirectory := file("src/site/img")
micrositeCssDirectory := file("src/site/styles")
micrositeSassDirectory := file("src/site/partials")
micrositeJsDirectory := file("src/site/scripts")
micrositeTheme := "light"
micrositeHighlightLanguages ++= Seq("scala", "sbt")
