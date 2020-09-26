val scala212 = "2.12.12"
val scala213 = "2.13.3"

val scalacOptions212 = Seq("-Ypartial-unification", "-deprecation")
val scalacOptions213 = Seq("-deprecation")

import microsites.ConfigYml
import sbt.enablePlugins
import xerial.sbt.Sonatype._

import scala.xml.{Node => XmlNode, NodeSeq => XmlNodeSeq, _}
import scala.xml.transform.{RewriteRule, RuleTransformer}

val commonSettings = Seq(
  organization := "io.github.vigoo",
  scalaVersion := scala213,
  crossScalaVersions := List(scala212, scala213),
  addCompilerPlugin("org.typelevel" %% s"kind-projector" % "0.11.0" cross CrossVersion.full),

  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.2.0"
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
    description := "A Scala library for working with system processes",
    dynverSonatypeSnapshots in ThisBuild := true,
  )
  .aggregate(proxCore, proxFS2, proxJava9)

lazy val proxCore = Project("prox-core", file("prox-core"))
  .settings(commonSettings)
  .enablePlugins(GhpagesPlugin, SiteScaladocPlugin, MicrositesPlugin)
  .settings(
    scalacOptions += "-target:jvm-1.8",

    git.remoteRepo := "git@github.com:vigoo/prox.git",

    micrositeUrl := "https://vigoo.github.io",
    micrositeBaseUrl := "/prox",
    micrositeHomepage := "https://vigoo.github.io/prox/",
    micrositeDocumentationUrl := "/prox/docs",
    micrositeAuthor := "Daniel Vigovszky",
    micrositeTwitterCreator := "@dvigovszky",
    micrositeGithubOwner := "vigoo",
    micrositeGithubRepo := "prox",
    micrositeGitterChannel := false,
    micrositeDataDirectory := file("src/microsite/data"),
    micrositeStaticDirectory := file("src/microsite/static"),
    micrositeImgDirectory := file("src/microsite/img"),
    micrositeCssDirectory := file("src/microsite/styles"),
    micrositeSassDirectory := file("src/microsite/partials"),
    micrositeJsDirectory := file("src/microsite/scripts"),
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
  )

lazy val proxFS2 = Project("prox-fs2", file("prox-fs2")).settings(commonSettings).settings(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "2.2.0",
    "co.fs2" %% "fs2-core" % "2.4.4",
    "co.fs2" %% "fs2-io" % "2.4.4",

    "dev.zio" %% "zio" % "1.0.1" % "test",
    "dev.zio" %% "zio-test" % "1.0.1" % "test",
    "dev.zio" %% "zio-test-sbt" % "1.0.1" % "test",
    "dev.zio" %% "zio-interop-cats" % "2.1.4.0" % "test",
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
).dependsOn(proxCore)

lazy val proxZStream = Project("prox-zstream", file("prox-zstream")).settings(commonSettings).settings(
  resolvers +=
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % "1.0.1",
    "dev.zio" %% "zio-streams" % "1.0.1",
    "dev.zio" %% "zio-prelude" % "0.0.0+348-336de77f-SNAPSHOT",

    "dev.zio" %% "zio-test" % "1.0.1" % "test",
    "dev.zio" %% "zio-test-sbt" % "1.0.1" % "test",
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
).dependsOn(proxCore)

lazy val proxJava9 = Project("prox-java9", file("prox-java9")).settings(commonSettings).settings(
  scalacOptions += "-target:jvm-1.9"
).dependsOn(proxCore)