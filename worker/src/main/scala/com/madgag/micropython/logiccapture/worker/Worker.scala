package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.effect.{IO, IOApp}
import com.madgag.micropython.logiccapture.worker.LogicCaptureWorker.JobDef
import com.madgag.micropython.logiccapture.worker.aws.{AWS, StepFuncActivityClient}
import upickle.default.*

object Worker extends IOApp.Simple {

  private val activityClient: StepFuncActivityClient = new StepFuncActivityClient(AWS.SFN, AWS.awsAccount)

  val activityWorker: LogicCaptureWorker = new LogicCaptureWorker()

  override val run: IO[Unit] = (for {
    captureTaskOpt <- activityClient.fetchTaskIfAvailable("pico-logic-capture")
  } yield {
    println(s"Got a task: ${captureTaskOpt.isDefined}")
    for {
      task <- captureTaskOpt
    } activityWorker.process(read[JobDef](task.input), () => activityClient.sendHeartbeat(task.token))
  }).foreverM
}
