package com.madgag.micropython.logiccapture.worker.aws

import cats.effect.IO
import software.amazon.awssdk.services.sfn.model.{SendTaskFailureResponse, SendTaskHeartbeatResponse, SendTaskSuccessResponse}

trait TaskLease {
  val heartbeat: Heartbeat

  def sendSuccess(output: ujson.Value): IO[SendTaskSuccessResponse]

  def sendFail(fail: Fail): IO[SendTaskFailureResponse]
}

trait Heartbeat {
  def send(): IO[SendTaskHeartbeatResponse]
}

case class Fail(error: String, cause: String) {
  require(cause.length <= 32768)
  require(error.length <= 256)
}

trait ActivityWorker[In, Out] {
  def process(input: In)(using heartbeat: Heartbeat): IO[Either[Fail, Out]]
}
