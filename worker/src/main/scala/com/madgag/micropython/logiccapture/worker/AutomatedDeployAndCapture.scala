package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig
import com.madgag.micropython.logiccapture.worker.AutomatedDeployAndCapture.Error.{InvalidYaml, MissingConfig}
import com.madgag.micropython.logiccapture.worker.aws.Fail
import org.virtuslab.yaml.*
import os.*
import upickle.default.*

import scala.util.Try

case class CaptureResult(captureProcessOutput: String, capturedData: Option[StoreCompressed]) derives ReadWriter

object AutomatedDeployAndCapture {

  sealed trait Error {
    def causeDescription: String
    def asFail: Fail = Fail(this.getClass.getSimpleName, causeDescription)
  }
  object Error {
    case class MissingConfig(captureConfigFileSubPath: SubPath) extends Error {
      override def causeDescription = s"Config file does not exist: $captureConfigFileSubPath"
    }

    case class InvalidYaml(yamlError: YamlError) extends Error {
      override def causeDescription = yamlError.msg
    }
  }

  def process(workRoot: Path, captureConfigFileSubPath: SubPath, resultsDir: Path): IO[Either[YamlConfigFile.Error, CaptureResult]] = {
    println(s"path is ${sys.env("PATH")}")
    val captureConfigFile = workRoot / captureConfigFileSubPath

    val errorOrConfig = YamlConfigFile.read[CaptureConfig](workRoot, captureConfigFileSubPath)

    errorOrConfig.map { config =>
      val configDir = os.Path((workRoot / captureConfigFileSubPath).toNIO.getParent)

      execute(workRoot, resultsDir, configDir, config)
        .flatTap(result => IO(println(s"Captured stuff was: ${result.capturedData.map(_.uncompressed.take(40))}")))
    }.sequence
  }

  private def execute(workRoot: Path, resultsDir: Path, configDir: Path, cap: CaptureConfig): IO[CaptureResult] = {
    def repoSubPath(target: RelPath): SubPath = (target resolveFrom configDir) subRelativeTo workRoot

    val mountFolder: SubPath = repoSubPath(cap.mountFolder)
    val captureDef: SubPath = repoSubPath(cap.captureDef)

    println(s"mountFolder: ${os.list(workRoot / mountFolder)}")

    os.makeDir.all(resultsDir)
    val captureResultsFile: Path = resultsDir / "capture.csv"
    println(s"captureResultsFile=$captureResultsFile")
    val gusmanbConfigFile = workRoot / captureDef

    val gusmanbConfig = GusmanBConfig.read(os.read(gusmanbConfigFile))
    println(s"sampleIntervalDuration=${gusmanbConfig.sampleIntervalDuration}")

    (for {
      mpremoteProcess <- Resource.fromAutoCloseable(IO(connectMPRemote(workRoot, mountFolder, cap)))
      captureProcess <- Resource.fromAutoCloseable(IO(os.proc("TerminalCapture", "capture", "/dev/ttyACM0", gusmanbConfigFile, captureResultsFile).spawn()))
    } yield (mpremoteProcess, captureProcess)).use { case (mpremoteProcess, captureProcess) =>
      IO.blocking(captureProcess.waitFor(10000)) >> IO(CaptureResult(captureProcess.stdout.trim(), Try(os.read(captureResultsFile)).toOption.map(StoreCompressed(_))))
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
