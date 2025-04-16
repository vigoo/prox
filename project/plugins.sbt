addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.2.2")
addSbtPlugin("com.47deg" % "sbt-microsites" % "1.4.4")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.7.1")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.6.1")
addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
