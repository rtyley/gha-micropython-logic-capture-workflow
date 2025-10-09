package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.effect.{IO, IOApp, Resource, ResourceApp}
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

object Worker extends ResourceApp.Forever {

  private val activityClient: StepFuncActivityClient =
    new StepFuncActivityClient(AWS.SFN, AWS.awsAccount, "pico-logic-capture")

  val activityWorker: LogicCaptureWorker = new LogicCaptureWorker()

  private val boom: IO[Unit] = for {
    taskOpt: Option[GetTaskResponse] <- activityClient.fetchTaskIfAvailable()
    rem <- taskOpt.fold(IO(()))(handleTask)
  } yield rem

  def run(args: List[String]) = for {
    pi4j <- PicoResetControl.pi4JResource
    _ <- Resource.eval(boom.foreverM)
  } yield ()

  def handleTask(task: GetTaskResponse): IO[Unit] = for {
    result <- activityWorker.process(read[JobDef](task.input))(using task.lease.heartbeat)
    wrappedUntilWeHaveFailure = Right(result)
    _ <- wrappedUntilWeHaveFailure.fold(task.lease.sendFail, res => task.lease.sendSuccess(writeJs(res)))
  } yield ()

}
