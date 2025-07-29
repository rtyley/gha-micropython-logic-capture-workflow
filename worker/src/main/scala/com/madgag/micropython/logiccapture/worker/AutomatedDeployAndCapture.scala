package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import com.madgag.logic.fileformat.Foo
import com.madgag.logic.fileformat.gusmanb.{GusmanBCaptureCSV, GusmanBConfig}
import com.madgag.logic.fileformat.saleae.csv.SaleaeCsv
import com.madgag.logic.{ChannelMapping, TimeParser}
import com.madgag.micropython.logiccapture.model.*
import com.madgag.micropython.logiccapture.worker.aws.Fail
import os.*
import upickle.default.*

import java.io.StringWriter
import java.time.Duration
import scala.io.Source
import scala.util.Try

object AutomatedDeployAndCapture {

  sealed trait Error {
    def causeDescription: String
    def asFail: Fail = Fail(this.getClass.getSimpleName, causeDescription)
  }

  def process(sourceDir: Path, executeAndCaptureDef: ExecuteAndCaptureDef): IO[CaptureResult] = {
    println(s"path is ${sys.env("PATH")}")
    
    def repoSubPath(target: RelPath): SubPath = (target resolveFrom configDir) subRelativeTo workRoot

    val mountFolder: SubPath = repoSubPath(executionDef.mountFolder)

    println(s"mountFolder: ${os.list(workRoot / mountFolder)}")

    os.makeDir.all(resultsDir)
    val captureResultsFile: Path = resultsDir / "capture.csv"
    println(s"captureResultsFile=$captureResultsFile")
    val gusmanbConfigFile = workRoot / captureDef

    val gusmanbConfig = GusmanBConfig.read(os.read(gusmanbConfigFile))
    println(s"sampleIntervalDuration=${gusmanbConfig.sampleIntervalDuration}")

    (for {
      mpremoteProcess <- Resource.fromAutoCloseable(IO(connectMPRemote(workRoot, executeAndCaptureDef.execution)))
      captureProcess <- Resource.fromAutoCloseable(IO(connectCapture(gusmanbConfigFile, captureResultsFile)))
    } yield (mpremoteProcess, captureProcess)).use { case (mpremoteProcess, captureProcess) =>
      IO.blocking(captureProcess.waitFor(10000)) >> IO {
        CaptureResult(captureProcess.stdout.trim(), compactCapture(captureResultsFile, gusmanbConfig))
      }
    }
  }

  private def connectCapture(gusmanbConfigFile: Path, captureResultsFile: Path) = 
    os.proc("TerminalCapture", "capture", "/dev/ttyACM0", gusmanbConfigFile, captureResultsFile).spawn()

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

  private def connectMPRemote(workRoot: Path, executionDef: ExecutionDef) = os.proc(
    "mpremote",
    "connect", "id:560ca184b37d9ae2",
    "mount", workRoot / executionDef.mountFolder,
    "exec", executionDef.exec
  ).spawn(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
}
