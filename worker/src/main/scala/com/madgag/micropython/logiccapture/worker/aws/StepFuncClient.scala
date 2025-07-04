package com.madgag.micropython.logiccapture.worker.aws

import cats.effect.IO
import com.madgag.micropython.logiccapture.worker.aws.StepFuncClient.{GetTaskResponse, State}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.*
import ujson.Value
import upickle.default.*

import java.util.concurrent.CompletableFuture

class StepFuncActivityClient(sfn: SfnAsyncClient, awsAccount: String, activityName: String) {
  private val region: Region = sfn.serviceClientConfiguration().region()

  private val activityTaskRequest: GetActivityTaskRequest =
    GetActivityTaskRequest.builder().activityArn(
      s"arn:aws:states:${region.id}:$awsAccount:activity:$activityName"
    ).workerName("boom").build()

  def fetchTaskIfAvailable(): IO[Option[GetTaskResponse]] = for {
    resp <- glurk(activityTaskRequest)(_.getActivityTask)
  } yield {
    println(resp)
    for {
      tokenText <- Option(resp.taskToken())
      input <- Option(resp.input())
    } yield new GetTaskResponse(ujson.read(read[State](input).input), new TaskLease {
      override val heartbeat: Heartbeat = () => glurk(SendTaskHeartbeatRequest.builder().taskToken(tokenText).build())(_.sendTaskHeartbeat)

      override def sendSuccess(output: Value): IO[SendTaskSuccessResponse] =
        glurk(SendTaskSuccessRequest.builder().taskToken(tokenText).output(ujson.write(output)).build())(_.sendTaskSuccess)

      override def sendFail(fail: Fail): IO[SendTaskFailureResponse] =
        glurk(SendTaskFailureRequest.builder().taskToken(tokenText).cause(fail.cause).error(fail.error).build())(_.sendTaskFailure)
    })
  }

  private def glurk[A <: SfnRequest, B](request: A)(f: SfnAsyncClient => A => CompletableFuture[B]): IO[B] =
    IO(println(s"Sending $request")) >> IO.fromCompletableFuture(IO(f(sfn)(request)))
}

object StepFuncClient {
  opaque type Token = String

  case class State(input: String) derives ReadWriter

  object Token:
    def apply(t: String): Token = t

  extension (x: Token)
    def str: String = x

  class GetTaskResponse(val input: ujson.Value, val lease: TaskLease)
}