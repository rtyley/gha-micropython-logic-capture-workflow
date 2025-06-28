package com.madgag.micropython.logiccapture.worker

import cats.effect.{IO, Resource}
import com.madgag.micropython.logiccapture.worker.AutomatedDeployAndCapture.Error.{InvalidYaml, MissingConfig}
import com.madgag.micropython.logiccapture.worker.aws.Fail
import org.virtuslab.yaml.*
import os.*
import cats.*
import cats.data.*
import cats.syntax.all.*

import scala.util.Try

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

  def process(workRoot: Path, captureConfigFileSubPath: SubPath, resultsDir: Path): IO[Either[YamlConfigFile.Error, Option[String]]] = {
    println(s"path is ${sys.env("PATH")}")
    val captureConfigFile = workRoot / captureConfigFileSubPath

    val errorOrConfig = YamlConfigFile.read[CaptureConfig](workRoot, captureConfigFileSubPath)

    errorOrConfig.map { config =>
      val configDir = os.Path((workRoot / captureConfigFileSubPath).toNIO.getParent)

      val foo: IO[Option[String]] = execute(workRoot, resultsDir, configDir, config)
      foo.flatTap(result => IO(println(s"Captured stuff was: ${result.map(_.take(40))}")))
    }.sequence
  }

  private def execute(workRoot: Path, resultsDir: Path, configDir: Path, cap: CaptureConfig): IO[Option[String]] = {
    def repoSubPath(target: RelPath): SubPath = (target resolveFrom configDir) subRelativeTo workRoot

    val mountFolder: SubPath = repoSubPath(cap.mountFolder)
    val captureDef: SubPath = repoSubPath(cap.captureDef)

    println(s"mountFolder: ${os.list(workRoot / mountFolder)}")

    val captureResultsFile = resultsDir / "capture.csv"

    (for {
      mpremoteProcess <- Resource.fromAutoCloseable(IO(connectMPRemote(workRoot, mountFolder, cap)))
      captureProcess <- Resource.fromAutoCloseable(IO(os.proc("TerminalCapture", "capture", "/dev/ttyACM0", workRoot / captureDef, captureResultsFile).spawn()))
    } yield (mpremoteProcess, captureProcess)).use { case (mpremoteProcess, captureProcess) =>
      IO.blocking(captureProcess.waitFor(10000)) >> IO(Try(os.read(captureResultsFile)).toOption)
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
