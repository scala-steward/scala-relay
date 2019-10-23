import sbtrelease.ReleasePlugin.autoImport.{ReleaseStep, _}
import sbtrelease.ReleaseStateTransformations._
import scala.sys.process._

val sbtVersions = List("0.13.17", "1.3.3")

crossSbtVersions := sbtVersions

ThisBuild / intellijPluginName := "relay-slinky-ijext"
ThisBuild / intellijBuild := "192.6817.14"

lazy val root =
  project
    .in(file("."))
    .settings(commonSettings)
    .settings(PgpKeys.publishSigned := {}, publishLocal := {}, publishArtifact := false, publish := {})
    .settings(
      // crossScalaVersions must be set to Nil on the aggregating project
      crossScalaVersions := Nil,
      publish / skip := true
    )
    .aggregate(`sbt-relay-compiler`, `relay-macro`)

def RuntimeLibPlugins = Sonatype && PluginsAccessor.exclude(BintrayPlugin)
def SbtPluginPlugins  = BintrayPlugin && PluginsAccessor.exclude(Sonatype)

lazy val `sbt-relay-compiler` = project
  .in(file("sbt-plugin"))
  .enablePlugins(SbtPluginPlugins)
  .enablePlugins(SbtPlugin)
  .settings(bintraySettings)
  .settings(commonSettings)
  .settings(
    sbtPlugin := true,
    addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % Version.Scalajs),
    addSbtPlugin("ch.epfl.scala"      % "sbt-scalajs-bundler"      % Version.ScalajsBundler),
    addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "0.6.1"),
    scriptedLaunchOpts += "-Dplugin.version=" + version.value,
    scriptedBufferLog := false,
    scriptedDependencies := {
      val () = scriptedDependencies.value
      val () = (publishLocal in `relay-macro`).value
    },
    crossSbtVersions := sbtVersions,
    scalaVersion := {
      (sbtBinaryVersion in pluginCrossBuild).value match {
        case "0.13" => "2.10.6"
        case _      => Version.Scala212
      }
    },
    publishMavenStyle := isSnapshot.value,
    sourceGenerators in Compile += Def.task {
      Generators.version(version.value, (sourceManaged in Compile).value)
    }.taskValue
  )

lazy val `relay-macro` = project
  .in(file("relay-macro"))
  .enablePlugins(RuntimeLibPlugins && ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    publishMavenStyle := true,
    crossSbtVersions := Nil,
    scalaVersion := Version.Scala212,
    scalacOptions ++= {
      if (scalaJSVersion.startsWith("0.6.")) Seq("-P:scalajs:sjsDefinedByDefault")
      else Nil
    },
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
      else Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    crossScalaVersions := Seq(Version.Scala212),
    addCompilerPlugin("org.scalamacros"         % "paradise" % "2.1.1" cross CrossVersion.full),
    libraryDependencies ++= Seq(Library.sangria % Provided, Library.scalatest)
  )

lazy val `slinky-relay` = project
  .in(file("slinky-relay"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    resourceGenerators in Compile += Def.task {
      val rootFolder = (resourceManaged in Compile).value / "META-INF"
      rootFolder.mkdirs()

      IO.write(rootFolder / "intellij-compat.json", s"""{
           |  "artifact": "${organization.value} % slinky-relay-ijext_2.12 % ${version.value}"
           |}""".stripMargin)

      Seq(rootFolder / "intellij-compat.json")
    },
    scalacOptions += "-Xplugin-require:macroparadise",
    libraryDependencies ++= Vector(
      "org.scala-lang" % "scala-reflect"    % scalaVersion.value,
      "org.scala-js"   %% "scalajs-library" % scalaJSVersion
    ),
    Library.slinky
  )

lazy val `slinky-relay-ijext` = (project in file("slinky-relay-ijext"))
  .enablePlugins(SbtIdeaPlugin)
  .settings(commonSettings)
  .settings(
    intellijPluginName := name.value,
    intellijExternalPlugins += "org.intellij.scala".toPlugin,
    intellijInternalPlugins ++= Seq("java"),
    packageMethod := PackagingMethod.Standalone(), // This only works for proper plugins
    patchPluginXml := pluginXmlOptions { xml =>
      // This only works for proper plugins
      xml.version = version.value
      xml.sinceBuild = (intellijBuild in ThisBuild).value
      xml.untilBuild = "193.*"
    },
    resourceGenerators in Compile += Def.task {
      val rootFolder = (resourceManaged in Compile).value / "META-INF"
      rootFolder.mkdirs()
      val fileOut = rootFolder / "intellij-compat.xml"

      IO.write(fileOut, s"""
          |<!DOCTYPE intellij-compat PUBLIC "Plugin/DTD"
          |        "https://raw.githubusercontent.com/JetBrains/intellij-scala/idea183.x/scala/scala-impl/src/org/jetbrains/plugins/scala/components/libextensions/intellij-compat.dtd">
          |<intellij-compat>
          |    <name>Slinky Relay Intellij Support</name>
          |    <description>Expands Slinky relay macros</description>
          |    <version>${version.value}</version>
          |    <vendor>Goodcover</vendor>
          |    <ideaVersion since-build="2019.2.0" until-build="2019.4.0">
          |        <extension interface="org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.SyntheticMembersInjector"
          |                   implementation="slinkyrelay.SlinkyRelayInjector">
          |            <name>Slinky Relay Intellij Support</name>
          |            <description>Expansion for @reactRelay macro</description>
          |        </extension>
          |    </ideaVersion>
          |</intellij-compat>
          """.stripMargin)

      Seq(fileOut)
    }
  )

lazy val bintraySettings: Seq[Setting[_]] =
  Seq(
    bintrayOrganization := Some("dispalt"), // TODO - Coordinates
    bintrayRepository := "sbt-plugins",
    bintrayPackage := "sbt-relay-compiler",
    bintrayReleaseOnPublish := true
  )

lazy val commonSettings: Seq[Setting[_]] = Seq(
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-unchecked",
    "-Xlint",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-Xfuture"
  ),
  organization := "com.dispalt.relay",  // TODO - Coordinates
  sonatypeProfileName := "com.dispalt", // TODO - Coordinates
  pomExtra :=
    <developers>
        <developer>
          <id>dispalt</id>
          <name>Dan Di Spaltro</name>
          <url>http://dispalt.com</url>
        </developer>
      </developers>,
  homepage := Some(url(s"https://github.com/goodcover/scala-relay")),
  licenses := Seq("MIT License" -> url("http://opensource.org/licenses/mit-license.php")),
  scmInfo := Some(
    ScmInfo(url("https://github.com/goodcover/scala-relay"), "scm:git:git@github.com:goodcover/scala-relay.git")
  )
)

releasePublishArtifactsAction := PgpKeys.publishSigned.value

releaseCrossBuild := false

releaseProcess :=
  Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    releaseStepCommandAndRemaining("+test"),
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    if (sbtPlugin.value) releaseStepCommandAndRemaining("^ publishSigned")
    else releaseStepCommandAndRemaining("+ publishSigned"),
    releaseStepCommandAndRemaining("sonatypeReleaseAll"),
    doReleaseYarn,
    setNextVersion,
    commitNextVersion,
    pushChanges
  )

lazy val doReleaseYarn = { st: State =>
  val v = Project.extract(st)
  val pl = new ProcessLogger {
    override def err(s: => String): Unit = st.log.info(s)
    override def out(s: => String): Unit = st.log.info(s)
    override def buffer[T](f: => T): T   = st.log.buffer(f)
  }
  val versionString = v.get(version)
  val bd            = v.get(baseDirectory)

  Process(s"yarn", bd / "node-compiler").!
  val cmd = Process(s"yarn publish --new-version $versionString --no-git-tag-version", bd / "node-compiler")
  cmd.!(pl)
  st

}
