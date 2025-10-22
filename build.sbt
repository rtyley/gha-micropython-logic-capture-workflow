ThisBuild / scalaVersion := "3.3.6"

val awsSdkVersion = "2.35.7"

lazy val root = (project in file(".")).aggregate(common, worker, client)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19" % Test

lazy val common = (project in file("common")).settings(
  libraryDependencies ++= Seq(
    "org.eclipse.jgit" % "org.eclipse.jgit" % "7.3.0.202506031305-r",
    "com.fazecast" % "jSerialComm" % "2.11.2",
    "software.amazon.awssdk" % "sfn" % awsSdkVersion,
    "com.lihaoyi" %% "upickle" % "4.4.0",
    "org.typelevel" %% "cats-effect" % "3.6.3",
    "com.github.cb372" %% "cats-retry" % "4.0.0",
    "com.gu" %% "logic-signals" % "4.1.0",
    scalaTest
  )
)

lazy val client = (project in file("client")).dependsOn(common)

lazy val sample = (project in file("sample-project")).dependsOn(client).settings(
  libraryDependencies ++= Seq(
    scalaTest,
    "org.typelevel" %% "cats-effect-testkit" % "3.6.3" % Test
  )
)

lazy val worker = (project in file("worker")).dependsOn(common).enablePlugins(JavaServerAppPackaging, SystemdPlugin)
  .settings(
    name := "pico-logic-capture-worker", // https://www.scala-sbt.org/sbt-native-packager/formats/debian.html#settings
    maintainer := "Roberto Tyley <52038+rtyley@users.noreply.github.com>",
    packageSummary := "Pico Logic Capture worker",
    packageDescription := "Description for Pico Logic Capture worker",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.11.5",
      scalaTest
    ) ++ Seq("core", "plugin-raspberrypi", "plugin-gpiod").map(a => "com.pi4j" % s"pi4j-$a" % "3.0.3")


    // Debian / debianPackageDependencies ++= Seq("openjdk-17-jdk-headless")
  )
