name := "prox"
organization := "io.github.vigoo"

version := "0.2.2-SNAPSHOT"

scalaVersion := "2.12.7"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "1.2.0",
  "co.fs2" %% "fs2-core" % "1.0.4",
  "co.fs2" %% "fs2-io" % "1.0.4",
  "com.chuusai" %% "shapeless" % "2.3.3",

  "org.specs2" %% "specs2-core" % "4.5.0" % "test"
)

coverageEnabled in(Test, compile) := true
coverageEnabled in(Compile, compile) := false

scalacOptions ++= Seq("-Ypartial-unification", "-deprecation")
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
