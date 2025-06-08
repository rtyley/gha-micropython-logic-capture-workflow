package com.madgag.micropython.logiccapture.worker.aws

import cats.effect.IO
import com.madgag.micropython.logiccapture.worker.aws.StepFuncClient.{GetTaskResponse, State, Token}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.*
import upickle.default.*

import java.util.concurrent.CompletableFuture

class StepFuncActivityClient(sfn: SfnAsyncClient, awsAccount: String) {
  private val region: Region = sfn.serviceClientConfiguration().region()

  def fetchTaskIfAvailable(activityName: String): IO[Option[GetTaskResponse]] = {
    val activityTaskRequest =
      GetActivityTaskRequest.builder().activityArn(
        s"arn:aws:states:${region.id}:$awsAccount:activity:$activityName"
      ).workerName("boom").build()

    for {
      resp <- glurk(activityTaskRequest)(_.getActivityTask)
    } yield for {
      token <- Option(resp.taskToken())
      input <- Option(resp.input())
    } yield GetTaskResponse(Token(token), ujson.read(read[State](input).input))
  }

  def sendSuccess(token: Token, output: ujson.Value): IO[SendTaskSuccessResponse] = {
    val request =
      SendTaskSuccessRequest.builder().taskToken(token.str).output(ujson.write(output)).build()
    glurk(request)(_.sendTaskSuccess)
  }

  def sendFail(token: Token, fail: Fail): IO[SendTaskFailureResponse] = {
    val request =
      SendTaskFailureRequest.builder().taskToken(token.str).cause(fail.cause).error(fail.error).build()
    glurk(request)(_.sendTaskFailure)
  }

  def sendHeartbeat(token: Token): IO[SendTaskHeartbeatResponse] = {
    val request = SendTaskHeartbeatRequest.builder().taskToken(token.str).build()
    glurk(request)(_.sendTaskHeartbeat)
  }

  private def glurk[A <: SfnRequest, B](request: A)(f: SfnAsyncClient => A => CompletableFuture[B]): IO[B] =
    IO.fromCompletableFuture(IO(f(sfn)(request)))
}

object StepFuncClient {
  opaque type Token = String

  case class State(input: String) derives ReadWriter

  object Token:
    def apply(t: String): Token = t

  extension (x: Token)
    def str: String = x

  case class GetTaskResponse(token: Token, input: ujson.Value)
}