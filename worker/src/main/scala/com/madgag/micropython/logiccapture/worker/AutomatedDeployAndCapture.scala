package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.effect.{IO, Resource}
import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import com.madgag.logic.fileformat.Foo
import com.madgag.logic.fileformat.gusmanb.{GusmanBCaptureCSV, GusmanBConfig}
import com.madgag.logic.fileformat.saleae.csv.SaleaeCsv
import com.madgag.logic.{ChannelMapping, GpioPin, TimeParser}
import com.madgag.micropython.logiccapture.TimeExpectation
import com.madgag.micropython.logiccapture.TimeExpectation.timeVsExpectation
import com.madgag.micropython.logiccapture.model.*
import com.madgag.micropython.logiccapture.worker.GusmanBConfigSupport.*
import com.madgag.micropython.logiccapture.worker.aws.Fail
import os.*

import java.io.StringWriter
import java.time.Duration
import scala.io.Source
import scala.util.Try

object AutomatedDeployAndCapture {

  sealed trait Error {
    def causeDescription: String
    def asFail: Fail = Fail(this.getClass.getSimpleName, causeDescription)
  }

  def process(sourceDir: Path, captureDir: Path, executeAndCaptureDef: ExecuteAndCaptureDef): IO[CaptureResult] = {
    println(s"path is ${sys.env("PATH")}")
    
    val mountFolder = sourceDir /  executeAndCaptureDef.execution.mountFolder

    println(s"mountFolder: ${os.list(mountFolder).mkString("\n")}")

    os.makeDir.all(captureDir)
    val captureResultsFile: Path = captureDir / "capture.csv"
    println(s"captureResultsFile=$captureResultsFile")
    
    val gusmanbConfig: GusmanBConfig = executeAndCaptureDef.capture.toGusmanB
    
    val gusmanbConfigFile = captureDir / "captureDef.tcs"

    val gusConfString = GusmanBConfig.write(gusmanbConfig)

    os.write(gusmanbConfigFile, gusConfString)

    println(s"sampleIntervalDuration=${gusmanbConfig.sampleIntervalDuration}")
    println(s"gusConfString=$gusConfString")

    (for {
      mpremoteProcess <- Resource.fromAutoCloseable(IO(connectMPRemote(mountFolder, executeAndCaptureDef.execution)))
      captureProcess <- Resource.fromAutoCloseable(IO(connectCapture(gusmanbConfigFile, captureResultsFile)))
    } yield (mpremoteProcess, captureProcess)).use { case (mpremoteProcess, captureProcess) =>
      println(s"Well, I got mpremoteProcess=$mpremoteProcess & captureProcess=$captureProcess")
      timeVsExpectation(Duration.ofSeconds(2).plus(executeAndCaptureDef.capture.sampling.postTriggerDuration.multipliedBy(3).dividedBy(2))) {
        dur => IO.blocking(captureProcess.waitFor(dur.toMillis))
      } >> IO {
        println(s"Finished waiting for captureProcess, cap exists=${captureResultsFile.toIO.exists()}")
        val cc = compactCapture(captureResultsFile, gusmanbConfig)
        println(s"cc=${cc.mkString.take(50)}")
        println(s"captureProcess.stdout=${captureProcess.stdout}")
        val capProcOutput = captureProcess.stdout.trim()
        println(s"capProcOutput=$capProcOutput")
        CaptureResult(capProcOutput, cc)
      }
    }
  }

  private def connectCapture(gusmanbConfigFile: Path, captureResultsFile: Path) = 
    os.proc("TerminalCapture", "capture", "/dev/ttyACM0", gusmanbConfigFile, captureResultsFile).spawn()

  private def compactCapture(captureResultsFile: Path, gusmanbConfig: GusmanBConfig) = {
    val saleaeFormattedCsvExport: Option[String] = for {
      gusmanbCaptureResults <- Try(os.read(captureResultsFile)).toOption
    } yield {
      val channelMapping = ChannelMapping[GpioPin](gusmanbConfig.captureChannels.map(cc => cc.channelName -> cc.channelNumber.gpioPin) *)
      val csvDetails = GusmanBCaptureCSV.csvDetails(gusmanbConfig.sampleIntervalDuration, channelMapping)

      val signals = Foo.read(csvDetails.format)(CSVReader.open(Source.fromString(gusmanbCaptureResults)))
      println(signals)
      val writer = new StringWriter()
      Foo.write(signals, SaleaeCsv.csvDetails(TimeParser.DeltaParser, channelMapping))(CSVWriter.open(writer)(SaleaeCsv.CsvFormat))
      writer.toString
    }
    saleaeFormattedCsvExport
  }

  private def connectMPRemote(mountFolder: Path, executionDef: ExecutionDef) = os.proc(
    "mpremote",
    "connect", "id:560ca184b37d9ae2",
    "mount", mountFolder,
    "exec", executionDef.exec
  ).spawn(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
}
