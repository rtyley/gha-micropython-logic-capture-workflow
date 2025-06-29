package com.madgag.micropython.logiccapture.worker

import cats.effect.IO
import com.madgag.micropython.logiccapture.worker.LogicCaptureWorker.JobDef
import com.madgag.micropython.logiccapture.worker.aws.{ActivityWorker, Fail, Heartbeat}
import com.madgag.micropython.logiccapture.worker.git.BearerAuthTransportConfig
import com.madgag.micropython.logiccapture.worker.git.BearerAuthTransportConfig.bearerAuth
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import os.{Path, SubPath}
import upickle.default.*

class LogicCaptureWorker extends ActivityWorker[JobDef, CaptureResult] {

  override def process(jobDef: JobDef)(using heartbeat: Heartbeat): IO[Either[Fail, CaptureResult]] = {
    val tempDir: Path = os.temp.dir()
    for {
      repoDir <- cloneRepo(jobDef, tempDir / "repo")
      res <- AutomatedDeployAndCapture.process(repoDir, jobDef.captureConfigFile.value, tempDir / "results").map(_.left.map(_.asFail))
    } yield res
  }

  def cloneRepo(jobDef: JobDef, repoContainerDir: Path)(using heartbeat: Heartbeat): IO[Path] = {
    IO {
      println(s"going to try a clone... to $repoContainerDir")
      val repository = Git.cloneRepository()
        .setCredentialsProvider(new UsernamePasswordCredentialsProvider("", ""))
        .setTransportConfigCallback(bearerAuth(jobDef.githubToken))
        .setDirectory(repoContainerDir.toIO).setURI(jobDef.repoHttpsGitUrl).call().getRepository.asInstanceOf[FileRepository]

      println(s"Clone is done, right? $repository")
      os.Path(repository.getWorkTree)

    }.flatTap(_ => heartbeat.send())
  }
}

object LogicCaptureWorker {
  case class JobDef(githubToken: String, repoGitUrl: String, captureConfigFile: Base64Encoded[SubPath]) derives ReadWriter {
    val repoHttpsGitUrl: String = "https" + repoGitUrl.stripPrefix("git")
  }

  object JobDef {
    given subPathRW: ReadWriter[SubPath] = readwriter[String].bimap[SubPath](_.toString, SubPath(_))
  }
}
