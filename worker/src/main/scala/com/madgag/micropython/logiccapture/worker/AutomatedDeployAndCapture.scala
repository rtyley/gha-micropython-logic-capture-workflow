package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.syntax.all.*
import cats.effect.{IO, Resource}
import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import com.madgag.logic.fileformat.Foo
import com.madgag.logic.fileformat.gusmanb.{GusmanBCaptureCSV, GusmanBConfig}
import com.madgag.logic.fileformat.saleae.csv.SaleaeCsv
import com.madgag.logic.{ChannelMapping, TimeParser}
import com.madgag.micropython.logiccapture.worker.AutomatedDeployAndCapture.Error.{InvalidYaml, MissingConfig}
import com.madgag.micropython.logiccapture.worker.aws.{Fail, Heartbeat}
import org.virtuslab.yaml.*
import os.*
import upickle.default.*

import java.io.StringWriter
import scala.io.Source
import scala.util.Try
import com.madgag.scala.collection.decorators.*

case class CaptureResult(captureProcessOutput: String, capturedData: Option[String]) derives ReadWriter

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

  def process(workRoot: Path, captureConfigFileSubPath: SubPath, resultsDir: Path)(using Heartbeat): IO[Either[YamlConfigFile.Error, Map[String, CaptureResult]]] = {
    println(s"path is ${sys.env("PATH")}")
    val captureConfigFile = workRoot / captureConfigFileSubPath

    val errorOrConfig = YamlConfigFile.read[CaptureSchedule](workRoot, captureConfigFileSubPath)

    errorOrConfig.map { config =>
      val configDir = os.Path((workRoot / captureConfigFileSubPath).toNIO.getParent)

      execute(workRoot, resultsDir, configDir, config)
        .flatTap(result => IO(println(s"Captured stuff was: ${result.mapV(_.capturedData.map(_.take(40)))}")))
    }.sequence
  }

  private def execute(workRoot: Path, resultsDir: Path, configDir: Path, cap: CaptureSchedule)(using heartbeat: Heartbeat): IO[Map[String, CaptureResult]] = {
    def repoSubPath(target: RelPath): SubPath = (target resolveFrom configDir) subRelativeTo workRoot

    val mountFolder: SubPath = repoSubPath(cap.mountFolder)
    val captureDef: SubPath = repoSubPath(cap.captureDef)

    println(s"mountFolder: ${os.list(workRoot / mountFolder)}")

    os.makeDir.all(resultsDir)
    val gusmanbConfigFile = workRoot / captureDef

    val gusmanbConfig = GusmanBConfig.read(os.read(gusmanbConfigFile))
    println(s"sampleIntervalDuration=${gusmanbConfig.sampleIntervalDuration}")

    def workItBaby(name: String, capInvocation: CaptureInvocation): IO[(String, CaptureResult)] = {
      val captureResultsFile: Path = resultsDir / SubPath(s"$name.csv")
      (for {
        captureProcess <- Resource.fromAutoCloseable(IO(connectCapture(gusmanbConfigFile, captureResultsFile)))
        mpremoteProcess <- Resource.fromAutoCloseable(IO(connectMPRemote(workRoot, mountFolder, capInvocation)))
      } yield (mpremoteProcess, captureProcess)).use { case (mpremoteProcess, captureProcess) =>
        IO.blocking(captureProcess.join(10000)) >> IO {
          name -> CaptureResult(captureProcess.stdout.trim(), compactCapture(captureResultsFile, gusmanbConfig))
        }.flatTap(_ => heartbeat.send())
      }
    }

    cap.captures.toSeq.traverse(workItBaby).map(_.toMap)
  }

  private def connectCapture(gusmanbConfigFile: Path, captureResultsFile: Path) = {
    os.proc("TerminalCapture", "capture", "/dev/ttyACM0", gusmanbConfigFile, captureResultsFile).spawn()
  }

  private def compactCapture(captureResultsFile: Path, gusmanbConfig: GusmanBConfig) = {
    val saleaeFormattedCsvExport: Option[String] = for {
      gusmanbCaptureResults <- Try(os.read(captureResultsFile)).toOption
    } yield {
      val channelMapping = ChannelMapping[Int](gusmanbConfig.captureChannels.map(cc => cc.channelName -> cc.channelNumber) *)
      val csvDetails = GusmanBCaptureCSV.csvDetails(gusmanbConfig.sampleIntervalDuration, channelMapping)

      val signals = Foo.read(csvDetails.format)(CSVReader.open(Source.fromString(gusmanbCaptureResults)))
      println(signals)
      val writer = new StringWriter()
      Foo.write(signals, SaleaeCsv.csvDetails(TimeParser.DeltaParser, channelMapping))(CSVWriter.open(writer)(SaleaeCsv.CsvFormat))
      writer.toString
    }
    saleaeFormattedCsvExport
  }

  private def connectMPRemote(workRoot: Path, mountFolder: SubPath, cap: CaptureInvocation) = {
    os.proc(
      "mpremote",
      "connect", "id:560ca184b37d9ae2",
      "mount", workRoot / mountFolder,
      "exec", cap.exec
    ).spawn(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
  }

  given YamlCodec[os.RelPath] = YamlCodec.make[String].mapInvariant(os.RelPath(_))(_.toString)

  case class CaptureInvocation(exec: String) derives YamlCodec
  
  case class CaptureSchedule(
    mountFolder: os.RelPath,
    captureDef: os.RelPath,
    captures: Map[String, CaptureInvocation]
  ) derives YamlCodec
}
