package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.data.*
import cats.effect.{IO, Resource, ResourceApp}
import cats.syntax.all.*
import com.madgag.logic.fileformat.gusmanb.BoardDef
import com.madgag.micropython.logiccapture.aws.AWS
import com.madgag.micropython.logiccapture.model.{CaptureResult, JobDef, JobOutput}
import com.madgag.micropython.logiccapture.worker.aws.StepFuncClient.GetTaskResponse
import com.madgag.micropython.logiccapture.worker.aws.StepFuncActivityClient
import upickle.default.*

object Worker extends ResourceApp.Forever {

  private val activityClient: StepFuncActivityClient =
    new StepFuncActivityClient(AWS.SFN, AWS.awsAccountId, "pico-logic-capture")

  def run(args: List[String]): Resource[IO, Unit] = for {
    picoResetControl <- PicoResetControl.resource
    activityWorkerHarness = new ActivityWorkerHarness(new LogicCaptureWorker(picoResetControl, BoardDef.Pico2))
    _ <- Resource.eval {
      activityClient.fetchTaskIfAvailable().flatMap(_.traverse_(activityWorkerHarness.handleTask)).foreverM
    }
  } yield ()

}
