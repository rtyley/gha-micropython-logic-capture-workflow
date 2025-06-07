package com.madgag.micropython.logiccapture.worker

import cats.effect.{IO, Resource}
import org.virtuslab.yaml.*
import os.*

object AutomatedDeployAndCapture {

  def process(workRoot: Path, captureConfigFileSubPath: SubPath, resultsDir: Path): Unit = {
    println(s"path is ${sys.env("PATH")}")
    val captureConfigFile = workRoot / captureConfigFileSubPath

    require(os.exists(captureConfigFile))

    val captureConfig: Either[YamlError, CaptureConfig] = os.read(captureConfigFile).as[CaptureConfig]
    println(captureConfig)

    val configDir = os.Path(captureConfigFile.toNIO.getParent)

    for {
      cap: CaptureConfig <- captureConfig
    } {
      def repoSubPath(target: os.RelPath): SubPath = (target resolveFrom configDir) subRelativeTo workRoot

      val mountFolder: SubPath = repoSubPath(cap.mountFolder)
      val captureDef: SubPath = repoSubPath(cap.captureDef)

      println(s"mountFolder: ${os.list(workRoot / mountFolder)}")

      val captureResultsFile = resultsDir / "capture.csv"

      for {
        mpremoteProcess <- Resource.fromAutoCloseable(IO(connectMPRemote(workRoot, mountFolder, cap)))
        captureProcess <- Resource.fromAutoCloseable(IO(os.proc("TerminalCapture", "capture", "/dev/ttyACM0", workRoot / captureDef, captureResultsFile).spawn()))
      } yield {
        captureProcess.waitFor(10000)
        println(os.size(captureResultsFile))
      }
    }
  }

  private def connectMPRemote(workRoot: Path, mountFolder: SubPath, cap: CaptureConfig) = {
    os.proc(
      "mpremote",
      "connect", "id:560ca184b37d9ae2",
      "mount", workRoot / mountFolder,
      "exec", s"import ${cap.startImport}"
    ).spawn(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
  }

  given YamlCodec[os.RelPath] = YamlCodec.make[String].mapInvariant(os.RelPath(_))(_.toString)

  case class CaptureConfig(
    mountFolder: os.RelPath,
    startImport: String,
    captureDef: os.RelPath
  ) derives YamlCodec
}
