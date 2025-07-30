package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.effect.{IO, IOApp}
import com.madgag.micropython.logiccapture.model.JobDef
import com.madgag.micropython.logiccapture.worker.aws.StepFuncClient.GetTaskResponse
import com.madgag.micropython.logiccapture.worker.aws.{AWS, StepFuncActivityClient}
import upickle.default.*

object Worker extends IOApp.Simple {

  private val activityClient: StepFuncActivityClient =
    new StepFuncActivityClient(AWS.SFN, AWS.awsAccount, "pico-logic-capture")

  val activityWorker: LogicCaptureWorker = new LogicCaptureWorker()

  private val boom: IO[Unit] = for {
    taskOpt: Option[GetTaskResponse] <- activityClient.fetchTaskIfAvailable()
    rem <- taskOpt.fold(IO(()))(handleTask)
  } yield rem

  override val run: IO[Unit] = boom.foreverM

  def handleTask(task: GetTaskResponse): IO[Unit] = for {
    result <- activityWorker.process(read[JobDef](task.input))(using task.lease.heartbeat)
    _ <- result.fold(task.lease.sendFail, res => task.lease.sendSuccess(writeJs(res)))
  } yield ()

}
