package com.madgag.micropython.logiccapture.worker.aws

import _root_.ujson.Value
import cats.*
import cats.data.*
import cats.effect.IO
import cats.syntax.all.*
import com.madgag.micropython.logiccapture.aws.{AWSIO, Fail}
import com.madgag.micropython.logiccapture.worker.aws.StepFuncClient.{GetTaskResponse, State}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.*
import upickle.default.*

class StepFuncActivityClient(sfn: SfnAsyncClient, awsAccount: String, activityName: String) {
  val awsIo: AWSIO[SfnAsyncClient, SfnRequest] = new AWSIO(sfn)
  
  private val region: Region = sfn.serviceClientConfiguration().region()

  private val activityTaskRequest: GetActivityTaskRequest =
    GetActivityTaskRequest.builder().activityArn(
      s"arn:aws:states:${region.id}:$awsAccount:activity:$activityName"
    ).workerName("boom").build()

  def fetchTaskIfAvailable(): IO[Option[GetTaskResponse]] = for {
    resp <- awsIo.glurk(activityTaskRequest)(_.getActivityTask)
  } yield {
    println(resp)
    for {
      tokenText <- Option(resp.taskToken())
      input <- Option(resp.input())
    } yield {
      println(s"I've got input=$input")
      new GetTaskResponse(ujson.read(read[State](input).input), new TaskLease {
        override val heartbeat: Heartbeat = () => awsIo.glurk(SendTaskHeartbeatRequest.builder().taskToken(tokenText).build())(_.sendTaskHeartbeat)

        override def sendSuccess(output: Value): IO[SendTaskSuccessResponse] =
          awsIo.glurk(SendTaskSuccessRequest.builder().taskToken(tokenText).output(ujson.write(output)).build())(_.sendTaskSuccess)

        override def sendFail(fail: Fail): IO[SendTaskFailureResponse] =
          awsIo.glurk(SendTaskFailureRequest.builder().taskToken(tokenText).cause(fail.cause).error(fail.error).build())(_.sendTaskFailure)
      })
    }
  }

}

object StepFuncClient {
  opaque type Token = String

  case class State(input: ujson.Value) derives ReadWriter

  object Token:
    def apply(t: String): Token = t

  extension (x: Token)
    def str: String = x

  class GetTaskResponse(val input: ujson.Value, val lease: TaskLease)
}