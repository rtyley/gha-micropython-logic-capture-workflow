package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.data.*
import cats.effect.{IO, Resource}
import com.fazecast.jSerialComm.SerialPort
import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import com.gu.time.duration.formatting.*
import com.madgag.logic.fileformat.Foo
import com.madgag.logic.fileformat.gusmanb.{GusmanBCaptureCSV, GusmanBConfig}
import com.madgag.logic.fileformat.saleae.csv.SaleaeCsv
import com.madgag.logic.{ChannelMapping, GpioPin, TimeParser}
import com.madgag.micropython.logiccapture.TimeExpectation.timeVsExpectation
import com.madgag.micropython.logiccapture.aws.Fail
import com.madgag.micropython.logiccapture.model.*
import com.madgag.micropython.logiccapture.model.GusmanBConfigSupport.*
import com.madgag.micropython.logiccapture.worker.AutomatedDeployAndCapture.{compactCapture, waitALimitedTimeForTerminationOf}
import com.madgag.micropython.logiccapture.worker.serialport.*
import com.madgag.micropython.logiccapture.{TimeExpectation, logTime}
import os.*
import retry.*
import retry.ResultHandler.*
import retry.RetryPolicies.*

import java.io.StringWriter
import java.time.Duration
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Try

case class CaptureFilePaths(captureDir: Path) {
  val gusmanbConfig: Path = captureDir / "captureDef.tcs"
  val results: Path = captureDir / "capture.csv"
}

object CaptureFilePaths {
  def setupFor(captureDir: Path, gusmanbConfig: GusmanBConfig): IO[CaptureFilePaths] = IO.delay {
    os.makeDir.all(captureDir)

    val captureFilePaths = CaptureFilePaths(captureDir)

    println(s"sampleIntervalDuration=${gusmanbConfig.sampleIntervalDuration.format()}")
    println(s"postTriggerDuration=${gusmanbConfig.postTriggerDuration.format()}")
    os.write(captureFilePaths.gusmanbConfig, GusmanBConfig.write(gusmanbConfig))

    captureFilePaths
  }
}

case class CaptureContext(captureDef: CaptureDef, paths: CaptureFilePaths)
case class ExecContext(executionDef: ExecutionDef, sourceDir: Path) {
  val mountFolder: Path = sourceDir / executionDef.mountFolder
}

object AutomatedDeployAndCapture {

  val GusmanBUsbId: UsbId = UsbId(0x1209, 0x3020)

  sealed trait Error {
    def causeDescription: String
    def asFail: Fail = Fail(this.getClass.getSimpleName, causeDescription)
  }

  def process(sourceDir: Path, captureDir: Path, executeAndCaptureDef: ExecuteAndCaptureDef): IO[CaptureResult] = {
    val gusmanBConfig = executeAndCaptureDef.capture.toGusmanB
    for {
      captureFilePaths <- CaptureFilePaths.setupFor(captureDir, gusmanBConfig).logTime("Capture files setup")
      captureResult <- execAndCapture(
        CaptureContext(executeAndCaptureDef.capture, captureFilePaths),
        ExecContext(executeAndCaptureDef.execution, sourceDir)
      ).logTime("execAndCapture")
    } yield captureResult
  }

  private def execAndCapture(
    captureContext: CaptureContext,
    execContext: ExecContext
  ): IO[CaptureResult] = (for {
    captureProcess <- captureProcessResource(captureContext.paths)
    mpremoteProcess <- mpremoteProcessResource(execContext)
  } yield (mpremoteProcess, captureProcess)).use { case (mpremoteProcess, captureProcess) =>
    println(s"Well, I got mpremoteProcess=$mpremoteProcess & captureProcess=$captureProcess")
    for {
      captureHasTerminated <- waitALimitedTimeForTerminationOf(captureProcess, captureContext.captureDef).logTime("waiting for termination")
      _ <- IO.println(s"captureHasTerminated=$captureHasTerminated capture-file-exists=${captureContext.paths.results.toIO.exists()}")
      captureResultOpt <- if (!captureHasTerminated) IO.pure(None) else IO.blocking(
        compactCapture(captureContext)
      ).logTime("compactCapture")
    } yield CaptureResult(captureProcess.stdout.trim(), captureResultOpt)
  }

  private def waitALimitedTimeForTerminationOf(captureProcess: SubProcess, captureDef: CaptureDef) =
    timeVsExpectation(Duration.ofSeconds(4).plus(captureDef.sampling.postTriggerDuration.multipliedBy(3).dividedBy(2))) {
      dur => IO.blocking(captureProcess.waitFor(dur.toMillis))
    }

  private def mpremoteProcessResource(execContext: ExecContext): Resource[IO, SubProcess] =
    Resource.fromAutoCloseable(IO.blocking {
      os.proc(
        "mpremote",
        "connect", "id:560ca184b37d9ae2",
        "mount", execContext.mountFolder,
        "exec", execContext.executionDef.exec
      ).spawn(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
    }.logTime("Creating mpremote process"))

  // eg "/dev/ttyACM0"
  def getUsbSystemPortPath(usbId: UsbId): IO[Option[String]] = 
    EitherT(retryingOnFailures(IO.blocking(SerialPort.getCommPorts.find(_.usbId.contains(usbId)).map(_.getSystemPortPath)))(
      limitRetriesByCumulativeDelay(4.seconds, fullJitter[IO](100.millis)),
      retryUntilSuccessful(_.isDefined, log = ResultHandler.noop)
    )).merge

  private def captureProcessResource(captureFilePaths: CaptureFilePaths): Resource[IO, SubProcess] = Resource.fromAutoCloseable(
    for {
      systemPortPath <- getUsbSystemPortPath(GusmanBUsbId).logTime("getUsbSystemPortPath")
      _ <- IO.println(s"Detected GusmanB systemPortPath=$systemPortPath")
      subProcess <- captureProcessFor(captureFilePaths, systemPortPath.get).logTime("creating captureProcessFor") // TODO !
    } yield subProcess
  ).evalTap { cap =>
    IO.blocking {
      val str = cap.stdout.readLine()
      println(s"Cap gave me $str")
    }.logTime("Terminal first line")
  }

  private def captureProcessFor(captureFilePaths: CaptureFilePaths, systemPortPath: String) = 
    IO(os.proc("TerminalCapture", "capture", systemPortPath, captureFilePaths.gusmanbConfig, captureFilePaths.results).spawn())

  private def compactCapture(captureContext: CaptureContext) = {
    val saleaeFormattedCsvExport: Option[String] = for {
      gusmanbCaptureResults <- Try(os.read(captureContext.paths.results)).toOption
    } yield {
      val gusmanBConfig = captureContext.captureDef.toGusmanB
      val channelMapping = ChannelMapping[GpioPin](gusmanBConfig.captureChannels.map(cc => cc.channelName -> cc.channelNumber.gpioPin) *)
      val csvDetails = GusmanBCaptureCSV.csvDetails(gusmanBConfig.sampleIntervalDuration, channelMapping)

      val signals = Foo.read(csvDetails.format)(CSVReader.open(Source.fromString(gusmanbCaptureResults)))
      println(s"signals.summary=${signals.summary}")
      val writer = new StringWriter()
      Foo.write(signals, SaleaeCsv.csvDetails(TimeParser.DeltaParser, channelMapping))(CSVWriter.open(writer)(SaleaeCsv.CsvFormat))
      writer.toString
    }
    saleaeFormattedCsvExport
  }

}
