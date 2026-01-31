package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.data.*
import cats.effect.{IO, Resource}
import com.fazecast.jSerialComm.SerialPort
import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import com.gu.time.duration.formatting.*
import com.madgag.logic.Time.Delta
import com.madgag.logic.fileformat.Foo
import com.madgag.logic.fileformat.gusmanb.{GusmanBCaptureCSV, GusmanBConfig}
import com.madgag.logic.fileformat.saleae.csv.SaleaeCsv
import com.madgag.logic.{ChannelMapping, ChannelSignals, GpioPin, TimeParser}
import com.madgag.micropython.logiccapture.TimeExpectation.timeVsExpectation
import com.madgag.micropython.logiccapture.aws.Fail
import com.madgag.micropython.logiccapture.model.*
import com.madgag.micropython.logiccapture.model.GusmanBConfigSupport.*
import com.madgag.micropython.logiccapture.worker.PicoResetControl.ResetTime
import com.madgag.micropython.logiccapture.worker.PicoResetControl.ResetTime.{logTimeSR, sleepUntilAfterReset}
import com.madgag.micropython.logiccapture.worker.serialport.*
import os.*
import os.PathConvertible.JavaIoFileConvertible
import retry.*
import retry.ResultHandler.*
import retry.RetryPolicies.*

import java.io.StringWriter
import java.nio.file.Files
import java.time.Duration
import java.time.Duration.ofNanos
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
  } yield (mpremoteProcess, captureProcess)).use { case (mpremoteProcess, captureProcess) => for {
      captureHasTerminated <- waitALimitedTimeForTerminationOf(captureProcess, captureContext.captureDef)
      captureOutput <- IO.blocking(captureProcess.stdout.trim()).timeoutAndForget(100.millis)
  } yield CaptureProcessReport(captureOutput, Option.when(captureHasTerminated)(captureContext))
  }

  private def waitALimitedTimeForTerminationOf(captureProcess: SubProcess, captureDef: CaptureDef)(using ResetTime): IO[Boolean] =
    timeVsExpectation(reasonableExecutionTimeFor(captureDef)) {
      dur => IO.blocking(captureProcess.waitFor(dur.toMillis)).flatTap { terminated =>
        IO.println(s"captureProcess terminated=$terminated") >>
        IO.blocking(captureProcess.destroy(100, false))
      }
    }.logTimeSR(s"waiting for termination (samples=${captureDef.sampling.totalSamples})")

  private def reasonableExecutionTimeFor(captureDef: CaptureDef): Duration = {
    val reasonableMaxTimeForProgramToTriggerCapture = Duration.ofSeconds(5)
    val timeToWriteOutSamples = ofNanos(5000).multipliedBy(captureDef.sampling.totalSamples) // generous for large quantities

    reasonableMaxTimeForProgramToTriggerCapture plus
      captureDef.sampling.postTriggerDuration plus timeToWriteOutSamples
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
    sleepUntilAfterReset(600.millis) >> // Fastest seen is 604ms
      EitherT(retryingOnFailures(IO.blocking(SerialPort.getCommPorts.find(_.usbId.contains(usbId)).map(_.getSystemPortPath)).logTimeSR("Checking for USB system port"))(
        limitRetriesByCumulativeDelay(4.seconds, fullJitter[IO](5.millis)),
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

  def compactCapture(captureContext: CaptureContext): IO[Option[String]] = {
    val results = captureContext.paths.results
    IO.blocking(!Files.exists(results.toNIO)).ifM(
      IO.println("No capture results found").as(None), {
        val gusmanBConfig = captureContext.captureDef.toGusmanB
        val channelMapping = ChannelMapping[GpioPin](gusmanBConfig.captureChannels.map(cc => cc.channelName -> cc.channelNumber.gpioPin) *)
        val csvDetails = GusmanBCaptureCSV.csvDetails(gusmanBConfig.sampleIntervalDuration, channelMapping)

        val resultsFile = results.toIO
        for {
          fileSize <- IO.blocking(resultsFile.length())
          signals <- IO.blocking(Foo.read(csvDetails.format)(CSVReader.open(resultsFile)))
          compactCsv = compactCsvFor(signals, channelMapping)
          compactCompressed = StoreCompressed(compactCsv).asCompressed.length
          _ <- IO.println(s"fileSize=$fileSize\ncompactCsv=${compactCsv.length}\ncompactCompressed=$compactCompressed\nsignals.summary=${signals.summary}")
        } yield Some(compactCsv)
      }
    )
  }

  private def compactCsvFor(signals: ChannelSignals[Delta, GpioPin], channelMapping: ChannelMapping[GpioPin]) = {
    val writer = new StringWriter()
    Foo.write(signals, SaleaeCsv.csvDetails(TimeParser.DeltaParser, channelMapping))(CSVWriter.open(writer)(SaleaeCsv.CsvFormat))
    writer.toString
  }
}
