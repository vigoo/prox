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
  "org.typelevel" %% "cats-effect" % "2.1.3",
  "co.fs2" %% "fs2-core" % "2.4.0",
  "co.fs2" %% "fs2-io" % "2.4.0",
  "com.chuusai" %% "shapeless" % "2.3.3",
  "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.6",

  "dev.zio" %% "zio" % "1.0.0-RC20" % "test",
  "dev.zio" %% "zio-test"     % "1.0.0-RC20" % "test",
  "dev.zio" %% "zio-test-sbt" % "1.0.0-RC20" % "test",
  "dev.zio" %% "zio-interop-cats" % "2.0.0.0-RC14" % "test",
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

import microsites.ConfigYml
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
micrositeDataDirectory := file("src/microsite/data")
micrositeStaticDirectory := file("src/microsite/static")
micrositeImgDirectory := file("src/microsite/img")
micrositeCssDirectory := file("src/microsite/styles")
micrositeSassDirectory := file("src/microsite/partials")
micrositeJsDirectory := file("src/microsite/scripts")
micrositeTheme := "light"
micrositeHighlightLanguages ++= Seq("scala", "sbt")
micrositeConfigYaml := ConfigYml(
  yamlCustomProperties = Map("plugins" -> List("jemoji"))
)

// Temporary fix to avoid including mdoc in the published POM
import scala.xml.{Node => XmlNode, NodeSeq => XmlNodeSeq, _}
import scala.xml.transform.{RewriteRule, RuleTransformer}

// skip dependency elements with a scope
pomPostProcess := { (node: XmlNode) =>
  new RuleTransformer(new RewriteRule {
    override def transform(node: XmlNode): XmlNodeSeq = node match {
      case e: Elem if e.label == "dependency" && e.child.exists(child => child.label == "artifactId" && child.text.startsWith("mdoc_")) =>
        val organization = e.child.filter(_.label == "groupId").flatMap(_.text).mkString
        val artifact = e.child.filter(_.label == "artifactId").flatMap(_.text).mkString
        val version = e.child.filter(_.label == "version").flatMap(_.text).mkString
        Comment(s"dependency $organization#$artifact;$version has been omitted")
      case _ => node
    }
  }).transform(node).head
}
