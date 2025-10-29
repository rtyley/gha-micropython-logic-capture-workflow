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
import com.madgag.micropython.logiccapture.TimeExpectation
import com.madgag.micropython.logiccapture.TimeExpectation.timeVsExpectation
import com.madgag.micropython.logiccapture.aws.Fail
import com.madgag.micropython.logiccapture.model.*
import com.madgag.micropython.logiccapture.model.GusmanBConfigSupport.*
import com.madgag.micropython.logiccapture.worker.PicoResetControl.ResetTime
import com.madgag.micropython.logiccapture.worker.PicoResetControl.ResetTime.{logTimeSR, sleepUntilAfterReset}
import com.madgag.micropython.logiccapture.worker.serialport.*
import os.*
import retry.*
import retry.ResultHandler.*
import retry.RetryPolicies.*

import java.io.StringWriter
import java.nio.file.Files
import java.time.Duration
import scala.concurrent.duration.*

case class CaptureFilePaths(captureDir: Path) {
  val gusmanbConfig: Path = captureDir / "captureDef.tcs"
  val results: Path = captureDir / "capture.csv"
}

object CaptureFilePaths {
  def setupFor(captureDir: Path, captureDef: CaptureDef): IO[CaptureFilePaths] = IO.blocking {
    val gusmanbConfig: GusmanBConfig = captureDef.toGusmanB
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
//
//  def process(sourceDir: Path, captureDir: Path, executeAndCaptureDef: ExecuteAndCaptureDef): IO[CaptureResult] = for {
//    captureFilePaths <- CaptureFilePaths.setupFor(captureDir, executeAndCaptureDef.capture).logTime("Capture files setup")
//    captureResult <- execAndCap(executeAndCaptureDef, captureFilePaths, sourceDir).logTime("execAndCap")
//  } yield captureResult

  def execAndCap(executeAndCaptureDef: ExecuteAndCaptureDef, captureFilePaths: CaptureFilePaths, sourceDir: Path)(using ResetTime): IO[CaptureProcessReport] =
    execAndCapture(
      CaptureContext(executeAndCaptureDef.capture, captureFilePaths),
      ExecContext(executeAndCaptureDef.execution, sourceDir)
    )

  private def execAndCapture(
    captureContext: CaptureContext,
    execContext: ExecContext
  )(using ResetTime): IO[CaptureProcessReport] = (for {
    captureProcess <- captureProcessResource(captureContext.paths) // we _should_ ensure that the capture process is ready before the program executes
    mpremoteProcess <- mpremoteProcessResource(execContext)
  } yield (mpremoteProcess, captureProcess)).use { case (mpremoteProcess, captureProcess) =>
    println(s"Well, I got mpremoteProcess=$mpremoteProcess & captureProcess=$captureProcess")
    for {
      captureHasTerminated <- waitALimitedTimeForTerminationOf(captureProcess, captureContext.captureDef).logTimeSR("waiting for termination")
    } yield CaptureProcessReport(captureProcess.stdout.trim(), Option.when(captureHasTerminated)(captureContext))
  }

  private def waitALimitedTimeForTerminationOf(captureProcess: SubProcess, captureDef: CaptureDef) =
    timeVsExpectation(Duration.ofSeconds(4).plus(captureDef.sampling.postTriggerDuration.multipliedBy(3).dividedBy(2))) {
      dur => IO.blocking(captureProcess.waitFor(dur.toMillis))
    }

  private def mpremoteProcessResource(execContext: ExecContext)(using ResetTime): Resource[IO, SubProcess] =
    Resource.fromAutoCloseable(IO.blocking {
      os.proc(
        "mpremote",
        "connect", "id:560ca184b37d9ae2",
        "mount", execContext.mountFolder,
        "exec", execContext.executionDef.exec
      ).spawn(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
    }.logTimeSR("Creating mpremote process"))

  // eg "/dev/ttyACM0"
  def getUsbSystemPortPath(usbId: UsbId)(using resetTime: ResetTime): IO[Option[String]] =
    sleepUntilAfterReset(540.millis) >> // Fastest seen is 548ms
      EitherT(retryingOnFailures(IO.blocking(SerialPort.getCommPorts.find(_.usbId.contains(usbId)).map(_.getSystemPortPath)))(
        limitRetriesByCumulativeDelay(4.seconds, fullJitter[IO](10.millis)),
        retryUntilSuccessful(_.isDefined, log = ResultHandler.noop)
      )).merge

  private def captureProcessResource(captureFilePaths: CaptureFilePaths)(using ResetTime): Resource[IO, SubProcess] = Resource.fromAutoCloseable(
    for {
      systemPortPath <- getUsbSystemPortPath(GusmanBUsbId).logTimeSR("getUsbSystemPortPath")
      _ <- IO.println(s"Detected GusmanB systemPortPath=$systemPortPath")
      subProcess <- captureProcessFor(captureFilePaths, systemPortPath.get).logTimeSR("creating captureProcessFor") // TODO - maybe start recording time since Pico reset?
    } yield subProcess
  ).evalTap { cap =>
    IO.blocking {
      val str = cap.stdout.readLine()
      println(s"Cap gave me $str")
    }.logTimeSR("Terminal first line")
  }

  private def captureProcessFor(captureFilePaths: CaptureFilePaths, systemPortPath: String) = 
    IO(os.proc("TerminalCapture", "capture", systemPortPath, captureFilePaths.gusmanbConfig, captureFilePaths.results).spawn())

  def compactCapture(captureContext: CaptureContext): IO[Option[String]] = OptionT.whenF(Files.exists(captureContext.paths.results.toNIO)) {
    val gusmanBConfig = captureContext.captureDef.toGusmanB
    val channelMapping = ChannelMapping[GpioPin](gusmanBConfig.captureChannels.map(cc => cc.channelName -> cc.channelNumber.gpioPin) *)
    val csvDetails = GusmanBCaptureCSV.csvDetails(gusmanBConfig.sampleIntervalDuration, channelMapping)

    IO.blocking {
      val signals = Foo.read(csvDetails.format)(CSVReader.open(captureContext.paths.results.toIO))
      println(s"signals.summary=${signals.summary}")
      val writer = new StringWriter()
      Foo.write(signals, SaleaeCsv.csvDetails(TimeParser.DeltaParser, channelMapping))(CSVWriter.open(writer)(SaleaeCsv.CsvFormat))
      writer.toString
    }
  }.value

}
