package com.madgag.micropython.logiccapture.worker

import cats.effect.IO
import com.madgag.micropython.logiccapture.model.JobDef
import com.madgag.micropython.logiccapture.worker.aws.ActivityWorker
import com.madgag.micropython.logiccapture.worker.aws.StepFuncClient.GetTaskResponse
import upickle.default.*

class ActivityWorkerHarness[In: Reader, Out: Writer](activityWorker: ActivityWorker[In, Out]) {
  def handleTask(task: GetTaskResponse): IO[_] = {
    val lease = task.lease
    activityWorker.process(read[In](task.input))(using lease.heartbeat).foldF(
      lease.sendFail,
      res => lease.sendSuccess(writeJs(res))
    )
  }
}
