name := "prox"
organization := "io.github.vigoo"

dynverSonatypeSnapshots in ThisBuild := true

val scala212 = "2.12.8"
val scala213 = "2.13.0"

scalaVersion := scala213
crossScalaVersions := List(scala212, scala213)

addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "2.0.0",
  "co.fs2" %% "fs2-core" % "2.0.1",
  "co.fs2" %% "fs2-io" % "2.0.1",
  "com.chuusai" %% "shapeless" % "2.3.3",
  "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.2",

  "org.specs2" %% "specs2-core" % "4.7.1" % "test",
  "dev.zio" %% "zio" % "1.0.0-RC14" % "test",
  "dev.zio" %% "zio-interop-cats" % "2.0.0.0-RC6" % "test",
)

coverageEnabled in(Test, compile) := true
coverageEnabled in(Compile, compile) := false

val scalacOptions212 = Seq("-Ypartial-unification", "-deprecation")
val scalacOptions213 = Seq("-deprecation")

scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, 12)) => scalacOptions212
  case Some((2, 13)) => scalacOptions213
  case _ => Nil
})

scalacOptions in Test ++= Seq("-Yrangepos")

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

git.remoteRepo := "git@github.com:vigoo/prox.git"
