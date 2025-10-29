package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.data.*
import cats.effect.IO
import cats.syntax.all.*
import com.gu.time.duration.formatting.*
import com.madgag.logic.fileformat.gusmanb.{BoardDef, GusmanBConfig, SamplingIssue}
import com.madgag.micropython.logiccapture.aws.Fail
import com.madgag.micropython.logiccapture.logTime
import com.madgag.micropython.logiccapture.model.GusmanBConfigSupport.*
import com.madgag.micropython.logiccapture.model.{CaptureResult, ExecuteAndCaptureDef, GitSource, JobDef, JobOutput}
import com.madgag.micropython.logiccapture.worker.LogicCaptureWorker.{failFor, thresholds}
import com.madgag.micropython.logiccapture.worker.PicoResetControl.ResetTime.logTimeSR
import com.madgag.micropython.logiccapture.worker.aws.{ActivityWorker, Heartbeat}
import com.madgag.micropython.logiccapture.worker.git.BearerAuthTransportConfig
import com.madgag.micropython.logiccapture.worker.git.BearerAuthTransportConfig.bearerAuth
import com.madgag.scala.collection.decorators.MapDecorator
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import os.Path

import java.time.Duration
import java.time.Duration.ofSeconds
import scala.collection.immutable.SortedMap
import scala.math.Ordering.Implicits.*

object LogicCaptureWorker {
  val MaxExecutions: Int = 50
  val MaxTotalExecutionTime: Duration = ofSeconds(270)
  val MaxCaptureTime: Duration = ofSeconds(70) // aligns with heartbeat: we send one after every capture

  case class ExecutionThreshold[T: Ordering](errorCode: String, max: T, failDescription: JobDef => Option[Any]) {
    def failFor(jobDef: JobDef): Option[Fail] = for {
      desc <- failDescription(jobDef)
    } yield Fail(errorCode, s"Max: $max Illegal: ${desc.toString}")
  }

  private def excessivelyLongCaptures(execs: Seq[ExecuteAndCaptureDef]): SortedMap[Int, Duration] = SortedMap.from(for {
    (exec, index) <- execs.zipWithIndex
    captureDuration = exec.capture.sampling.postTriggerDuration
    if captureDuration > MaxCaptureTime
  } yield (index, captureDuration))

  private def excessivelyBigCaptures(configs: Seq[GusmanBConfig], board: BoardDef): SortedMap[Int, SamplingIssue] = SortedMap.from(for {
    (config, index) <- configs.zipWithIndex
    issue <- config.issueWithBoard(board)
  } yield (index, issue))

  def failFor(jobDef: JobDef, b: BoardDef): Option[Fail] = {
    val bom = excessivelyBigCaptures(jobDef.execs.map(_.capture.toGusmanB), b)
    Option.when(bom.nonEmpty)(Fail("TooManySamples", bom.mapV(_.summary).toString()))
  }


  val thresholds = LazyList(
    ExecutionThreshold("TooManyExecutions", 50,
      jobDef => Option.when(jobDef.execs.size > 50)(jobDef.execs.size)),
    ExecutionThreshold("RequestedIndividualCaptureTimeTooLong", ofSeconds(70),
      jobDef => Option.when(excessivelyLongCaptures(jobDef.execs).nonEmpty)(excessivelyLongCaptures(jobDef.execs).mapV(_.format()))),
    ExecutionThreshold("PredictedTotalJobTimeTooLong",
      ofSeconds(270), jobDef => Option.when(jobDef.minimumTotalExecutionTime > ofSeconds(270))(jobDef.minimumTotalExecutionTime.format()))
  )
}


case class CaptureProcessReport(processOutput: String, detailsForCompletedCapture: Option[CaptureContext])


class LogicCaptureWorker(picoResetControl: PicoResetControl, board: BoardDef) extends ActivityWorker[JobDef, JobOutput] {

  override def process(jobDef: JobDef)(using heartbeat: Heartbeat): EitherT[IO, Fail, JobOutput] = {
    thresholds.flatMap(_.failFor(jobDef)).headOption.orElse(failFor(jobDef, board)).fold {
      val tempDir: Path = os.temp.dir(prefix = "pico-logic-capture-")
      EitherT.right(for {
        sourceDir <- cloneRepo(jobDef.sourceDef, tempDir / "repo")
        res <- fs2.Stream(jobDef.execs *).zipWithIndex.covary[IO].parEvalMap(2) { (executeAndCapture, index) =>
          CaptureFilePaths.setupFor(tempDir / s"capture-$index", executeAndCapture.capture).logTime(s"Capture $index: files setup").map(_ -> executeAndCapture)
        }.evalMap { (captureFilePaths, executeAndCapture) =>
          picoResetControl.reset.flatMap(implicit resetTime => AutomatedDeployAndCapture.execAndCap(executeAndCapture, captureFilePaths, sourceDir).logTimeSR(s"execAndCap ${captureFilePaths.results}"))
        }.parEvalMap(4) { captureProcessReport =>
          for {
            _ <- heartbeat.send()
            compactCap <- captureProcessReport.detailsForCompletedCapture.flatTraverse(AutomatedDeployAndCapture.compactCapture).logTime("parsing capture result")
          } yield CaptureResult(captureProcessReport.processOutput, compactCap)
        }.compile.toList
      } yield res) // TODO return fail... if appropriate
    }(EitherT.leftT(_))
  }

  // .flatTap(_ => heartbeat.send()).logTime(s"Capture $index")

  def cloneRepo(gitSource: GitSource, repoContainerDir: Path)(using heartbeat: Heartbeat): IO[Path] = IO {
    val cloneableUrl = gitSource.gitSpec.httpsGitUrl
    println(s"going to try to clone '$cloneableUrl' to $repoContainerDir")
    val repository = Git.cloneRepository()
      .setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", gitSource.githubToken))
      .setTransportConfigCallback(bearerAuth(gitSource.githubToken))
      .setDirectory(repoContainerDir.toIO).setURI(cloneableUrl.toString).call().getRepository.asInstanceOf[FileRepository]

    println(s"Clone is done, right? $repository")
    os.Path(repository.getWorkTree)

  }.flatTap(_ => heartbeat.send())
}
