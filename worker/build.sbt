enablePlugins(JavaServerAppPackaging, SystemdPlugin)

scalaVersion := "3.3.6"

val awsSdkVersion = "2.31.54"

libraryDependencies ++= Seq(
  "software.amazon.awssdk" % "sfn" % awsSdkVersion,
  "com.lihaoyi" %% "upickle" % "4.2.1",
  "org.virtuslab" %% "scala-yaml" % "0.3.0",
  "org.typelevel" %% "cats-effect" % "3.6.2",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "7.2.1.202505142326-r",
  "com.lihaoyi" %% "os-lib" % "0.11.4",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

name := "pico-logic-capture-worker"

version := "1.0"

maintainer := "Roberto Tyley <52038+rtyley@users.noreply.github.com>"

packageSummary := "Pico Logic Capture worker"
packageDescription := """Description for Pico Logic Capture worker"""

// Debian / debianPackageDependencies ++= Seq("openjdk-17-jdk-headless")
