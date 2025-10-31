import ReleaseTransformations.*
import sbtversionpolicy.withsbtrelease.ReleaseVersion

ThisBuild / scalaVersion := "3.3.6"

val awsSdkVersion = "2.36.3"

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19" % Test

ThisBuild / scalacOptions := Seq("-deprecation", "-release:21")

val weaverCats = "org.typelevel" %% "weaver-cats" % "0.10.1"  % Test

val artifactProducingSettings = Seq(
  organization := "com.madgag.logic-capture",
  licenses := Seq(License.Apache2),
  libraryDependencies += scalaTest
)

lazy val common = (project in file("common")).settings(artifactProducingSettings).settings(
  libraryDependencies ++= Seq(
    "org.eclipse.jgit" % "org.eclipse.jgit" % "7.4.0.202509020913-r",
    "com.softwaremill.sttp.client4" %% "core" % "4.0.13",
    "com.fazecast" % "jSerialComm" % "2.11.2",
    "software.amazon.awssdk" % "sfn" % awsSdkVersion,
    "com.lihaoyi" %% "upickle" % "4.4.0",
    "com.gu.duration-formatting" %% "core" % "0.0.2",
    "org.typelevel" %% "cats-effect" % "3.6.3",
    "com.github.cb372" %% "cats-retry" % "4.0.0",
    "co.fs2" %% "fs2-io" % "3.12.2",
    "com.madgag" %% "logic-signals" % "5.1.0",
    scalaTest
  )
)

lazy val client = (project in file("client")).dependsOn(common).settings(artifactProducingSettings)

lazy val sample = (project in file("sample-project")).dependsOn(client).settings(
  libraryDependencies ++= Seq(
    scalaTest,
    "org.typelevel" %% "cats-effect-testkit" % "3.6.3" % Test
  )
)

lazy val worker = (project in file("worker")).dependsOn(common).enablePlugins(JavaServerAppPackaging, SystemdPlugin)
  .settings(
    publish / skip := true,
    name := "pico-logic-capture-worker", // https://www.scala-sbt.org/sbt-native-packager/formats/debian.html#settings
    maintainer := "Roberto Tyley <52038+rtyley@users.noreply.github.com>",
    packageSummary := "Pico Logic Capture worker",
    packageDescription := "Description for Pico Logic Capture worker",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.11.5",
      weaverCats,
      scalaTest
    ) ++ Seq("core", "plugin-raspberrypi", "plugin-gpiod").map(a => "com.pi4j" % s"pi4j-$a" % "3.0.3")

    // Debian / debianPackageDependencies ++= Seq("openjdk-17-jdk-headless")
  )


lazy val root = (project in file(".")).aggregate(common, worker, client).settings(
  publish / skip := true,
  releaseVersion := ReleaseVersion.fromAggregatedAssessedCompatibilityWithLatestRelease().value,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    setNextVersion,
    commitNextVersion
  )
)
