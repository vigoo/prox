val scala212 = "2.12.12"
val scala213 = "2.13.3"

val scalacOptions212 = Seq("-Ypartial-unification", "-deprecation")
val scalacOptions213 = Seq("-deprecation")

import microsites.ConfigYml
import sbt.enablePlugins
import xerial.sbt.Sonatype._

import scala.xml.{Node => XmlNode, NodeSeq => XmlNodeSeq, _}
import scala.xml.transform.{RewriteRule, RuleTransformer}

dynverSonatypeSnapshots in ThisBuild := true

val commonSettings = Seq(
  organization := "io.github.vigoo",
  scalaVersion := scala213,
  crossScalaVersions := List(scala212, scala213),
  addCompilerPlugin("org.typelevel" %% s"kind-projector" % "0.11.1" cross CrossVersion.full),
  scalacOptions += "-target:jvm-1.8",

  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.3.0"
  ),

  coverageEnabled in(Test, compile) := true,
  coverageEnabled in(Compile, compile) := false,

  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) => scalacOptions212
    case Some((2, 13)) => scalacOptions213
    case _ => Nil
  }),

  // Publishing

  publishMavenStyle := true,

  licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),

  sonatypeProjectHosting := Some(GitHubHosting("vigoo", "prox", "daniel.vigovszky@gmail.com")),

  developers := List(
    Developer(id = "vigoo", name = "Daniel Vigovszky", email = "daniel.vigovszky@gmail.com", url = url("https://vigoo.github.io"))
  ),

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
)

lazy val prox = project.in(file("."))
  .settings(
    name := "prox",
    organization := "io.github.vigoo",
    skip in publish := true
  )
  .aggregate(proxCore, proxFS2, proxZStream, proxJava9)

lazy val proxCore = Project("prox-core", file("prox-core")).settings(commonSettings)

lazy val proxFS2 = Project("prox-fs2", file("prox-fs2")).settings(commonSettings).settings(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "2.2.0",
    "co.fs2" %% "fs2-core" % "2.4.5",
    "co.fs2" %% "fs2-io" % "2.4.5",

    "dev.zio" %% "zio" % "1.0.3" % "test",
    "dev.zio" %% "zio-test" % "1.0.3" % "test",
    "dev.zio" %% "zio-test-sbt" % "1.0.3" % "test",
    "dev.zio" %% "zio-interop-cats" % "2.2.0.1" % "test",
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
).dependsOn(proxCore)

lazy val proxZStream = Project("prox-zstream", file("prox-zstream")).settings(commonSettings).settings(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % "1.0.3",
    "dev.zio" %% "zio-streams" % "1.0.3",
    "dev.zio" %% "zio-prelude" % "1.0.0-RC1",

    "dev.zio" %% "zio-test" % "1.0.3" % "test",
    "dev.zio" %% "zio-test-sbt" % "1.0.3" % "test",
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
).dependsOn(proxCore)

lazy val proxJava9 = Project("prox-java9", file("prox-java9")).settings(commonSettings).dependsOn(proxCore)


lazy val docs = project
  .enablePlugins(GhpagesPlugin, SiteScaladocPlugin, ScalaUnidocPlugin, MicrositesPlugin)
  .settings(
    addCompilerPlugin("org.typelevel" %% s"kind-projector" % "0.11.1" cross CrossVersion.full),
    publishArtifact := false,
    skip in publish := true,
    scalaVersion := scala213,
    name := "prox",
    description := "A Scala library for working with system processes",
    git.remoteRepo := "git@github.com:vigoo/prox.git",
    siteSubdirName in ScalaUnidoc := "api",
    addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), siteSubdirName in ScalaUnidoc),
    micrositeUrl := "https://vigoo.github.io",
    micrositeBaseUrl := "/prox",
    micrositeHomepage := "https://vigoo.github.io/prox/",
    micrositeDocumentationUrl := "/prox/docs",
    micrositeAuthor := "Daniel Vigovszky",
    micrositeTwitterCreator := "@dvigovszky",
    micrositeGithubOwner := "vigoo",
    micrositeGithubRepo := "prox",
    micrositeGitterChannel := false,
    micrositeDataDirectory := baseDirectory.value / "src/microsite/data",
    micrositeStaticDirectory := baseDirectory.value / "src/microsite/static",
    micrositeImgDirectory := baseDirectory.value / "src/microsite/img",
    micrositeCssDirectory := baseDirectory.value / "src/microsite/styles",
    micrositeSassDirectory := baseDirectory.value / "src/microsite/partials",
    micrositeJsDirectory := baseDirectory.value / "src/microsite/scripts",
    micrositeTheme := "light",
    micrositeHighlightLanguages ++= Seq("scala", "sbt"),
    micrositeConfigYaml := ConfigYml(
      yamlCustomProperties = Map(
        "url" -> "https://vigoo.github.io",
        "plugins" -> List("jemoji", "jekyll-sitemap")
      )
    ),
    micrositeAnalyticsToken := "UA-56320875-3",
    includeFilter in makeSite := "*.html" | "*.css" | "*.png" | "*.jpg" | "*.gif" | "*.js" | "*.swf" | "*.txt" | "*.xml" | "*.svg",
    // Temporary fix to avoid including mdoc in the published POM

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
  ).dependsOn(proxCore, proxFS2, proxZStream, proxJava9)
