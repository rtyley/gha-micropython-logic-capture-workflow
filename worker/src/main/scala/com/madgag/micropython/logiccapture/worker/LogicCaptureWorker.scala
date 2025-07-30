package com.madgag.micropython.logiccapture.worker

import cats.effect.IO
import com.madgag.micropython.logiccapture.model.{CaptureResult, GitSource, JobDef}
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
      sourceDir <- cloneRepo(jobDef.sourceDef, tempDir / "repo")
      res <- AutomatedDeployAndCapture.process(sourceDir, tempDir  / "capture", jobDef.executeAndCapture)
    } yield Right(res)
  }

  def cloneRepo(gitSource: GitSource, repoContainerDir: Path)(using heartbeat: Heartbeat): IO[Path] = IO {
    println(s"going to try a clone... to $repoContainerDir")
    val repository = Git.cloneRepository()
      .setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", gitSource.githubToken))
      .setTransportConfigCallback(bearerAuth(gitSource.githubToken))
      .setDirectory(repoContainerDir.toIO).setURI(gitSource.gitSpec.httpsGitUrl).call().getRepository.asInstanceOf[FileRepository]

    println(s"Clone is done, right? $repository")
    os.Path(repository.getWorkTree)

  }.flatTap(_ => heartbeat.send())
}
