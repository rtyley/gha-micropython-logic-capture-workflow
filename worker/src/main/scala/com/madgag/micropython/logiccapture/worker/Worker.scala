package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.effect.{IO, IOApp}
import com.madgag.micropython.logiccapture.model.{CaptureResult, JobDef}
import com.madgag.micropython.logiccapture.worker.aws.StepFuncClient.GetTaskResponse
import com.madgag.micropython.logiccapture.worker.aws.{AWS, StepFuncActivityClient}
import com.pi4j.Pi4J
import com.pi4j.boardinfo.util.BoardInfoHelper
import com.pi4j.plugin.gpiod.provider.gpio.digital.{GpioDDigitalOutput, GpioDDigitalOutputProvider}
import upickle.default.*

object Worker extends IOApp.Simple {

  private val activityClient: StepFuncActivityClient =
    new StepFuncActivityClient(AWS.SFN, AWS.awsAccount, "pico-logic-capture")

  {
    val pi4j = Pi4J.newAutoContext()
    val platforms = pi4j.platforms
    println("Pi4J PLATFORMS")
    platforms.describe.print(System.out)
    println("Board model: " + pi4j.boardInfo.getBoardModel.getLabel)
    println("Operating system: " + pi4j.boardInfo.getOperatingSystem)
    println("Java versions: " + pi4j.boardInfo.getJavaInfo)
    // This info is also available directly from the BoardInfoHelper, 
    // and with some additional realtime data.
    println("Board model: " + BoardInfoHelper.current.getBoardModel.getLabel)
    println("Raspberry Pi model with RP1 chip (Raspberry Pi 5): " + BoardInfoHelper.usesRP1)
    println("OS is 64-bit: " + BoardInfoHelper.is64bit)
    println("JVM memory used (MB): " + BoardInfoHelper.getJvmMemory.getUsedInMb)
    println("Board temperature (°C): " + BoardInfoHelper.getBoardReading.getTemperatureInCelsius)

    val runPin: GpioDDigitalOutput = pi4j.digitalOutput[GpioDDigitalOutputProvider]().create(21)
    runPin.low()


  }

  val activityWorker: LogicCaptureWorker = new LogicCaptureWorker()

  private val boom: IO[Unit] = for {
    taskOpt: Option[GetTaskResponse] <- activityClient.fetchTaskIfAvailable()
    rem <- taskOpt.fold(IO(()))(handleTask)
  } yield rem

  override val run: IO[Unit] = boom.foreverM

  def handleTask(task: GetTaskResponse): IO[Unit] = for {
    result <- activityWorker.process(read[JobDef](task.input))(using task.lease.heartbeat)
    wrappedUntilWeHaveFailure = Right(result)
    _ <- wrappedUntilWeHaveFailure.fold(task.lease.sendFail, res => task.lease.sendSuccess(writeJs(res)))
  } yield ()

}
