enablePlugins(JavaServerAppPackaging, SystemdPlugin)

scalaVersion := "3.3.6"

val awsSdkVersion = "2.31.54"

libraryDependencies ++= Seq(
  "software.amazon.awssdk" % "sfn" % awsSdkVersion,
  "com.lihaoyi" %% "upickle" % "4.2.1",
  "org.virtuslab" %% "scala-yaml" % "0.3.0",
  "org.typelevel" %% "cats-effect" % "3.6.1",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "7.2.1.202505142326-r",
  "com.lihaoyi" %% "os-lib" % "0.11.4"
)