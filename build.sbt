val scala212 = "2.12.18"
val scala213 = "2.13.12"
val scala3 = "3.3.5"

val zio2Version = "2.1.17"

def scalacOptions212(jdk: Int) = Seq(
  "-Ypartial-unification",
  "-deprecation",
  "-Xsource:3",
  "-release",
  jdk.toString
)
def scalacOptions213(jdk: Int) =
  Seq("-deprecation", "-Xsource:3", "-release", jdk.toString)
def scalacOptions3(jdk: Int) =
  Seq("-deprecation", "-Ykind-projector", "-release", jdk.toString)

import microsites.ConfigYml
import xerial.sbt.Sonatype._

import scala.xml.{Node => XmlNode, NodeSeq => XmlNodeSeq, _}
import scala.xml.transform.{RewriteRule, RuleTransformer}

ThisBuild / dynverSonatypeSnapshots := true

def commonSettings(jdk: Int) = Seq(
  organization := "io.github.vigoo",
  scalaVersion := scala213,
  crossScalaVersions := List(scala212, scala213, scala3),
  libraryDependencies ++=
    (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _)) => Seq.empty
      case _ =>
        Seq(
          compilerPlugin(
            "org.typelevel" % "kind-projector" % "0.13.3" cross CrossVersion.full
          )
        )
    }),
  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.13.0"
  ),
  Test / compile / coverageEnabled := true,
  Compile / compile / coverageEnabled := false,
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 12)) => scalacOptions212(jdk)
    case Some((2, 13)) => scalacOptions213(jdk)
    case Some((3, _))  => scalacOptions3(jdk)
    case _             => Nil
  }),

  // Publishing

  publishMavenStyle := true,
  licenses := Seq(
    "APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")
  ),
  sonatypeProjectHosting := Some(
    GitHubHosting("vigoo", "prox", "daniel.vigovszky@gmail.com")
  ),
  developers := List(
    Developer(
      id = "vigoo",
      name = "Daniel Vigovszky",
      email = "daniel.vigovszky@gmail.com",
      url = url("https://vigoo.github.io")
    )
  ),
  sonatypeCredentialHost := "s01.oss.sonatype.org",
  sonatypeRepository := "https://s01.oss.sonatype.org/service/local",
  credentials ++=
    (for {
      username <- Option(System.getenv().get("SONATYPE_USERNAME"))
      password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
    } yield Credentials(
      "Sonatype Nexus Repository Manager",
      "s01.oss.sonatype.org",
      username,
      password
    )).toSeq
)

lazy val prox = project
  .in(file("."))
  .settings(commonSettings(8))
  .settings(
    name := "prox",
    organization := "io.github.vigoo",
    publish / skip := true
  )
  .aggregate(proxCore, proxFS23, proxZStream2, proxJava9)

lazy val proxCore =
  Project("prox-core", file("prox-core")).settings(commonSettings(8))

lazy val proxFS23 = Project("prox-fs2-3", file("prox-fs2-3"))
  .settings(commonSettings(8))
  .settings(
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % "3.12.0",
      "co.fs2" %% "fs2-io" % "3.12.0",
      "dev.zio" %% "zio" % zio2Version % "test",
      "dev.zio" %% "zio-test" % zio2Version % "test",
      "dev.zio" %% "zio-test-sbt" % zio2Version % "test",
      "dev.zio" %% "zio-interop-cats" % "23.1.0.3" % "test"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .dependsOn(proxCore)

lazy val proxZStream2 = Project("prox-zstream-2", file("prox-zstream-2"))
  .settings(commonSettings(8))
  .settings(
    resolvers +=
      "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zio2Version,
      "dev.zio" %% "zio-streams" % zio2Version,
      "dev.zio" %% "zio-prelude" % "1.0.0-RC28",
      "dev.zio" %% "zio-test" % zio2Version % "test",
      "dev.zio" %% "zio-test-sbt" % zio2Version % "test"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
  .dependsOn(proxCore)

lazy val proxJava9 = Project("prox-java9", file("prox-java9"))
  .settings(commonSettings(9))
  .dependsOn(proxCore)

lazy val docs = project
  .enablePlugins(
    GhpagesPlugin,
    SiteScaladocPlugin,
    ScalaUnidocPlugin,
    MicrositesPlugin
  )
  .settings(commonSettings(9))
  .settings(
    addCompilerPlugin(
      "org.typelevel" %% s"kind-projector" % "0.13.2" cross CrossVersion.full
    ),
    publishArtifact := false,
    publish / skip := true,
    scalaVersion := scala213,
    name := "prox",
    description := "A Scala library for working with system processes",
    git.remoteRepo := "git@github.com:vigoo/prox.git",
    ScalaUnidoc / siteSubdirName := "api",
    addMappingsToSiteDir(
      ScalaUnidoc / packageDoc / mappings,
      ScalaUnidoc / siteSubdirName
    ),
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      proxCore,
      proxFS23,
      proxZStream2,
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
    makeSite / includeFilter := "*.html" | "*.css" | "*.png" | "*.jpg" | "*.gif" | "*.js" | "*.swf" | "*.txt" | "*.xml" | "*.svg",
    // Temporary fix to avoid including mdoc in the published POM

    // skip dependency elements with a scope
    pomPostProcess := { (node: XmlNode) =>
      new RuleTransformer(new RewriteRule {
        override def transform(node: XmlNode): XmlNodeSeq = node match {
          case e: Elem
              if e.label == "dependency" && e.child.exists(child =>
                child.label == "artifactId" && child.text.startsWith("mdoc_")
              ) =>
            val organization =
              e.child.filter(_.label == "groupId").flatMap(_.text).mkString
            val artifact =
              e.child.filter(_.label == "artifactId").flatMap(_.text).mkString
            val version =
              e.child.filter(_.label == "version").flatMap(_.text).mkString
            Comment(
              s"dependency $organization#$artifact;$version has been omitted"
            )
          case _ => node
        }
      }).transform(node).head
    }
  )
  .dependsOn(proxCore, proxFS23, proxZStream2, proxJava9)
