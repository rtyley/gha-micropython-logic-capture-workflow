package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.data.*
import cats.syntax.all.*
import cats.effect.IO
import com.madgag.micropython.logiccapture.model.{CaptureDef, CaptureResult, ExecuteAndCaptureDef, GitSource, JobDef, JobOutput}
import com.madgag.micropython.logiccapture.worker.LogicCaptureWorker.{MaxCaptureTime, MaxExecutions, MaxTotalExecutionTime, failFor, thresholds}
import com.madgag.micropython.logiccapture.worker.aws.{ActivityWorker, Fail, Heartbeat}
import com.madgag.micropython.logiccapture.worker.git.BearerAuthTransportConfig
import com.madgag.micropython.logiccapture.worker.git.BearerAuthTransportConfig.bearerAuth
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import os.{Path, SubPath}
import upickle.default.*

import scala.math.Ordering.Implicits.*
import java.time.Duration
import com.gu.time.duration.formatting.*
import com.madgag.logic.fileformat.gusmanb.{BoardDef, GusmanBConfig, SamplingIssue}
import com.madgag.logic.fileformat.gusmanb.BoardDef.Pico2
import com.madgag.micropython.logiccapture.model.GusmanBConfigSupport.*
import com.madgag.scala.collection.decorators.MapDecorator

import java.time.Duration.ofSeconds
import scala.collection.immutable.SortedMap

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

class LogicCaptureWorker(picoResetControl: PicoResetControl, board: BoardDef) extends ActivityWorker[JobDef, JobOutput] {

  override def process(jobDef: JobDef)(using heartbeat: Heartbeat): EitherT[IO, Fail, JobOutput] = {
    thresholds.flatMap(_.failFor(jobDef)).headOption.orElse(failFor(jobDef, board)).fold {
      val tempDir: Path = os.temp.dir()
      EitherT.right(for {
        sourceDir <- cloneRepo(jobDef.sourceDef, tempDir / "repo")
        res <- jobDef.execs.traverseWithIndexM { (executeAndCapture, index) =>
          picoResetControl.reset() >>
            AutomatedDeployAndCapture.process(sourceDir, tempDir / s"capture-$index", executeAndCapture).flatTap(_ => heartbeat.send())
        }
      } yield res) // TODO return fail... if appropriate
    }(EitherT.leftT(_))
  }

  def cloneRepo(gitSource: GitSource, repoContainerDir: Path)(using heartbeat: Heartbeat): IO[Path] = IO {
    val httpsGitUrl = gitSource.gitSpec.httpsGitUrl
    println(s"going to try to clone '$httpsGitUrl' to $repoContainerDir")
    val repository = Git.cloneRepository()
      .setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", gitSource.githubToken))
      .setTransportConfigCallback(bearerAuth(gitSource.githubToken))
      .setDirectory(repoContainerDir.toIO).setURI(httpsGitUrl).call().getRepository.asInstanceOf[FileRepository]

    println(s"Clone is done, right? $repository")
    os.Path(repository.getWorkTree)

  }.flatTap(_ => heartbeat.send())
}
