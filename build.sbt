ThisBuild / scalaVersion := "3.3.6"

val awsSdkVersion = "2.32.8"

lazy val root = (project in file(".")).aggregate(common, worker, client)

lazy val common = (project in file("common")).settings(
  libraryDependencies ++= Seq(
    "org.eclipse.jgit" % "org.eclipse.jgit" % "7.3.0.202506031305-r",
    "software.amazon.awssdk" % "sfn" % awsSdkVersion,
    "com.lihaoyi" %% "upickle" % "4.2.1",
    "org.virtuslab" %% "scala-yaml" % "0.3.0",
    "org.typelevel" %% "cats-effect" % "3.6.3",
    "com.gu" %% "logic-signals" % "1.0.3",
    "org.scalatest" %% "scalatest" % "3.2.19" % Test
  )
)

lazy val client = (project in file("client")).dependsOn(common).settings(
  libraryDependencies += "com.github.cb372" %% "cats-retry" % "4.0.0"
)

lazy val worker = (project in file("worker")).dependsOn(common).enablePlugins(JavaServerAppPackaging, SystemdPlugin)
  .settings(
    maintainer := "Roberto Tyley <52038+rtyley@users.noreply.github.com>",
    packageSummary := "Pico Logic Capture worker",
    packageDescription := "Description for Pico Logic Capture worker",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.11.4"
    )

  // Debian / debianPackageDependencies ++= Seq("openjdk-17-jdk-headless")
  )
