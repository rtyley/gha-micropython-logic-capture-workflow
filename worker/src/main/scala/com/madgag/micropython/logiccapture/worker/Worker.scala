package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.effect.{IO, IOApp}
import com.madgag.micropython.logiccapture.worker.LogicCaptureWorker.JobDef
import com.madgag.micropython.logiccapture.worker.aws.StepFuncClient.{GetTaskResponse, Token}
import com.madgag.micropython.logiccapture.worker.aws.{AWS, Fail, StepFuncActivityClient}
import software.amazon.awssdk.awscore.AwsResponse
import software.amazon.awssdk.core.SdkPojo
import software.amazon.awssdk.services.sfn.model.{SendTaskFailureResponse, SendTaskSuccessResponse, SfnResponse}
import software.amazon.awssdk.utils.builder.{CopyableBuilder, ToCopyableBuilder}
import upickle.default.*

object Worker extends IOApp.Simple {

  private val activityClient: StepFuncActivityClient = new StepFuncActivityClient(AWS.SFN, AWS.awsAccount)

  val activityWorker: LogicCaptureWorker = new LogicCaptureWorker()

  override val run: IO[Unit] = (for {
    captureTaskOpt <- activityClient.fetchTaskIfAvailable("pico-logic-capture")
  } yield captureTaskOpt.fold(IO(()), handleTask)).foreverM
  
  def handleTask(task: GetTaskResponse): IO[Unit] = for {
    result <- activityWorker.process(read[JobDef](task.input), () => activityClient.sendHeartbeat(task.token))
  } yield result.fold(
    fail => activityClient.sendFail(task.token, fail),
    res => activityClient.sendSuccess(task.token, res)
  )

}
