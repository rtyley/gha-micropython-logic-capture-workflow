package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.effect.{IO, IOApp}
import com.madgag.micropython.logiccapture.model.{CaptureResult, JobDef}
import com.madgag.micropython.logiccapture.worker.aws.StepFuncClient.GetTaskResponse
import com.madgag.micropython.logiccapture.worker.aws.{AWS, StepFuncActivityClient}
import com.pi4j.Pi4J
import com.pi4j.boardinfo.util.BoardInfoHelper
import com.pi4j.context.Context
import com.pi4j.io.IOType
import com.pi4j.platform.Platform
import com.pi4j.plugin.gpiod.provider.gpio.digital.{GpioDDigitalInputProvider, GpioDDigitalOutput, GpioDDigitalOutputProvider}
import upickle.default.*

object Funk {
  def makePi(): Context = {
    val digitalInputProvider = GpioDDigitalInputProvider.newInstance()
    val digitalOutputProvider = GpioDDigitalOutputProvider.newInstance()

    val pi4j = Pi4J.newContextBuilder()
      .add(digitalInputProvider, digitalOutputProvider)
      .setGpioChipName("gpiochip0").build()

    println("Board model: " + pi4j.boardInfo.getBoardModel.getLabel)
    println("Operating system: " + pi4j.boardInfo.getOperatingSystem)
    println("Java versions: " + pi4j.boardInfo.getJavaInfo)
    // This info is also available directly from the BoardInfoHelper,
    // and with some additional realtime data.
    println("Board model: " + BoardInfoHelper.current.getBoardModel.getLabel)
    println("Raspberry Pi model with RP1 chip (Raspberry Pi 5): " + BoardInfoHelper.usesRP1)
    println("OS is 64-bit: " + BoardInfoHelper.is64bit)
    println("Board temperature (°C): " + BoardInfoHelper.getBoardReading.getTemperatureInCelsius)
    pi4j
  }

  def getDatOut():GpioDDigitalOutput = {
    val pi4j = Funk.makePi()

    val pin:GpioDDigitalOutput = pi4j.digitalOutput[GpioDDigitalOutputProvider]().create(21)
    println(s"pin=$pin")
    pin
  }
}

object Worker extends IOApp.Simple {

  private val activityClient: StepFuncActivityClient =
    new StepFuncActivityClient(AWS.SFN, AWS.awsAccount, "pico-logic-capture")

  {
    val runPin: GpioDDigitalOutput = Funk.getDatOut()
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
