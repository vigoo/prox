val scala212 = "2.12.15"
val scala213 = "2.13.8"
val scala3 = "3.2.1"

val zioVersion = "1.0.16"
val zio2Version = "2.0.2"

val scalacOptions212 = Seq("-Ypartial-unification", "-deprecation", "-target:jvm-1.8")
val scalacOptions213 = Seq("-deprecation", "-target:jvm-1.8")
def scalacOptions3(jdk: Int) = Seq("-deprecation", "-Ykind-projector", "-release", jdk.toString)

import microsites.ConfigYml
import sbt.enablePlugins
import xerial.sbt.Sonatype._

import scala.xml.{Node => XmlNode, NodeSeq => XmlNodeSeq, _}
import scala.xml.transform.{RewriteRule, RuleTransformer}

dynverSonatypeSnapshots in ThisBuild := true

def commonSettings(jdk: Int) = Seq(
  organization := "io.github.vigoo",
  scalaVersion := scala213,
  crossScalaVersions := List(scala212, scala213, scala3),
  libraryDependencies ++= 
     (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _)) => Seq.empty
    case _ => Seq(
        compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full),
      )
  }),
  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.8.1"
  ),

  coverageEnabled in(Test, compile) := true,
  coverageEnabled in(Compile, compile) := false,

  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) => scalacOptions212
    case Some((2, 13)) => scalacOptions213
    case Some((3, _)) => scalacOptions3(jdk)
    case _ => Nil
  }),

  // Publishing

  publishMavenStyle := true,

  licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),

  sonatypeProjectHosting := Some(GitHubHosting("vigoo", "prox", "daniel.vigovszky@gmail.com")),

  developers := List(
    Developer(id = "vigoo", name = "Daniel Vigovszky", email = "daniel.vigovszky@gmail.com", url = url("https://vigoo.github.io"))
  ),

  sonatypeCredentialHost := "s01.oss.sonatype.org",
  sonatypeRepository     := "https://s01.oss.sonatype.org/service/local",
  credentials ++=
    (for {
      username <- Option(System.getenv().get("SONATYPE_USERNAME"))
      password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
    } yield
      Credentials(
        "Sonatype Nexus Repository Manager",
        "s01.oss.sonatype.org",
        username,
        password)).toSeq
)

lazy val prox = project.in(file("."))
  .settings(commonSettings(8))
  .settings(
    name := "prox",
    organization := "io.github.vigoo",
    skip in publish := true
  )
  .aggregate(proxCore, proxFS2, proxFS23, proxZStream, proxZStream2, proxJava9)

lazy val proxCore = Project("prox-core", file("prox-core")).settings(commonSettings(8))

lazy val proxFS2 = Project("prox-fs2", file("prox-fs2")).settings(commonSettings(8)).settings(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "2.5.5",
    "co.fs2" %% "fs2-core" % "2.5.11",
    "co.fs2" %% "fs2-io" % "2.5.11",

    "dev.zio" %% "zio" % zioVersion % "test",
    "dev.zio" %% "zio-test" % zioVersion % "test",
    "dev.zio" %% "zio-test-sbt" % zioVersion % "test",
    "dev.zio" %% "zio-interop-cats" % "2.5.1.0" % "test",
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
).dependsOn(proxCore)

lazy val proxFS23 = Project("prox-fs2-3", file("prox-fs2-3")).settings(commonSettings(8)).settings(
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % "3.6.0",
    "co.fs2" %% "fs2-io" % "3.6.0",

    "dev.zio" %% "zio" % zioVersion % "test",
    "dev.zio" %% "zio-test" % zioVersion % "test",
    "dev.zio" %% "zio-test-sbt" % zioVersion % "test",
    "dev.zio" %% "zio-interop-cats" % "3.2.9.1" % "test",
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
).dependsOn(proxCore)

lazy val proxZStream = Project("prox-zstream", file("prox-zstream")).settings(commonSettings(8)).settings(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % zioVersion,
    "dev.zio" %% "zio-streams" % zioVersion,
    "dev.zio" %% "zio-prelude" % "1.0.0-RC8",

    "dev.zio" %% "zio-test" % zioVersion % "test",
    "dev.zio" %% "zio-test-sbt" % zioVersion % "test",
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
).dependsOn(proxCore)

lazy val proxZStream2 = Project("prox-zstream-2", file("prox-zstream-2")).settings(commonSettings(8)).settings(
  resolvers +=
    "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio" % zio2Version,
    "dev.zio" %% "zio-streams" % zio2Version,
    "dev.zio" %% "zio-prelude" % "1.0.0-RC15",

    "dev.zio" %% "zio-test" % zio2Version % "test",
    "dev.zio" %% "zio-test-sbt" % zio2Version % "test",
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
).dependsOn(proxCore)

lazy val proxJava9 = Project("prox-java9", file("prox-java9")).settings(commonSettings(9)).dependsOn(proxCore)


lazy val docs = project
  .enablePlugins(GhpagesPlugin, SiteScaladocPlugin, ScalaUnidocPlugin, MicrositesPlugin)
  .settings(commonSettings(8))
  .settings(
    addCompilerPlugin("org.typelevel" %% s"kind-projector" % "0.13.2" cross CrossVersion.full),
    publishArtifact := false,
    skip in publish := true,
    scalaVersion := scala213,
    name := "prox",
    description := "A Scala library for working with system processes",
    git.remoteRepo := "git@github.com:vigoo/prox.git",
    siteSubdirName in ScalaUnidoc := "api",
    addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), siteSubdirName in ScalaUnidoc),
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      proxCore,
      proxFS2,
      proxZStream,
      proxJava9
    ),
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
    micrositePushSiteWith := GitHub4s,
    micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
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
  ).dependsOn(proxCore, proxFS2/* todo , proxFS23 */, proxZStream, proxJava9)
