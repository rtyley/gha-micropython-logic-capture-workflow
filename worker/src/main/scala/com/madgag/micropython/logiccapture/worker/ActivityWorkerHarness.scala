package com.madgag.micropython.logiccapture.worker

import cats.effect.IO
import com.madgag.micropython.logiccapture.model.JobDef
import com.madgag.micropython.logiccapture.worker.aws.ActivityWorker
import com.madgag.micropython.logiccapture.worker.aws.StepFuncClient.GetTaskResponse
import upickle.default.*

class ActivityWorkerHarness[In: Reader, Out: Writer](activityWorker: ActivityWorker[In, Out]) {
  def handleTask(task: GetTaskResponse): IO[Unit] = for {
    result <- activityWorker.process(read[In](task.input))(using task.lease.heartbeat)
    wrappedUntilWeHaveFailure = Right(result)
    _ <- wrappedUntilWeHaveFailure.fold(task.lease.sendFail, res => task.lease.sendSuccess(writeJs(res)))
  } yield ()
}
