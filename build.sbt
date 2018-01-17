name := "prox"
organization := "io.github.vigoo"

version := "0.1-SNAPSHOT"

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "0.5",
  "co.fs2" %% "fs2-core" % "0.10.0-M8",
  "co.fs2" %% "fs2-io" % "0.10.0-M8",
  "com.chuusai" %% "shapeless" % "2.3.2",

  "org.specs2" %% "specs2-core" % "4.0.0" % "test"
)

coverageEnabled in(Test, compile) := true
coverageEnabled in(Compile, compile) := false

scalacOptions ++= Seq("-Ypartial-unification", "-deprecation")
scalacOptions in Test ++= Seq("-Yrangepos")

// Publishing

publishMavenStyle := true

pomIncludeRepository := { _ => false }

isSnapshot := version.value endsWith "SNAPSHOT"

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

pomExtra := (
  <url>https://github.com/vigoo/prox</url>
    <licenses>
      <license>
        <name>Apache 2</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:vigoo/prox.git</url>
      <connection>scm:git:git@github.com:vigoo/prox.git</connection>
    </scm>
    <developers>
      <developer>
        <id>vigoo</id>
        <name>Daniel Vigovszky</name>
        <url>https://github.com/vigoo</url>
      </developer>
    </developers>)

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
