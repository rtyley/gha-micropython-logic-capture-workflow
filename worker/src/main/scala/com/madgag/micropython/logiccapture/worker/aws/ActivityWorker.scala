package com.madgag.micropython.logiccapture.worker.aws

trait Heartbeat {
  def send(): Unit
}

case class Fail(error: String, cause: String) {
  require(cause.length <= 32768)
  require(error.length <= 256)
}

trait ActivityWorker[In, Out] {
  def process(input: In, heartbeat: Heartbeat): Either[Fail, Out]
}
