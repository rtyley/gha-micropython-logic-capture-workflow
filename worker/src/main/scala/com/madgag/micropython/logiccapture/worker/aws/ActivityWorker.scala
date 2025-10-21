package com.madgag.micropython.logiccapture.worker.aws

import cats.*
import cats.data.*
import cats.effect.IO
import com.madgag.micropython.logiccapture.aws.Fail
import software.amazon.awssdk.services.sfn.model.{SendTaskFailureResponse, SendTaskHeartbeatResponse, SendTaskSuccessResponse}

trait TaskLease {
  val heartbeat: Heartbeat

  def sendSuccess(output: ujson.Value): IO[SendTaskSuccessResponse]

  def sendFail(fail: Fail): IO[SendTaskFailureResponse]
}

trait Heartbeat {
  def send(): IO[SendTaskHeartbeatResponse]
}

trait ActivityWorker[In, Out] {
  def process(input: In)(using heartbeat: Heartbeat): EitherT[IO, Fail, Out]
}
