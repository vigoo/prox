name := "prox"
organization := "io.github.vigoo"

version := "0.1"

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "0.5",
  "co.fs2" %% "fs2-core" % "0.10.0-M8",
  "co.fs2" %% "fs2-io" % "0.10.0-M8",
  "com.chuusai" %% "shapeless" % "2.3.2",

  "org.specs2" %% "specs2-core" % "4.0.0" % "test"
)

coverageEnabled := true

scalacOptions ++= Seq("-Ypartial-unification")
scalacOptions in Test ++= Seq("-Yrangepos")
