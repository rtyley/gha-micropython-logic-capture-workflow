package com.madgag.micropython.logiccapture.worker

import cats.*
import cats.effect.{IO, IOApp}
import com.madgag.micropython.logiccapture.worker.AWS.awsAccount
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.transport.{CredentialsProvider, TransportHttp, UsernamePasswordCredentialsProvider}
import org.virtuslab.yaml.*
import os.SubPath
import software.amazon.awssdk.services.sfn.model.GetActivityTaskRequest
import upickle.default.*

import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.jdk.CollectionConverters.*

given uriRW: ReadWriter[SubPath] = readwriter[String].bimap[SubPath](_.toString, SubPath(_))

case class Base64Encoded[T: ReadWriter](value: T) {
  val asBase64: String = Base64.getEncoder.encodeToString(write(value).getBytes(UTF_8))
}

object Base64Encoded {
  given [T: ReadWriter]: ReadWriter[Base64Encoded[T]] =
    readwriter[String].bimap[Base64Encoded[T]](_.asBase64, s => {
      println(s"Base64 string: $s")
      Base64Encoded(read[T](s"\"${new String(Base64.getDecoder.decode(s))}\""))
    })
}

given YamlCodec[os.RelPath] = YamlCodec.make[String].mapInvariant(os.RelPath(_))(_.toString)

case class CaptureConfig(
  mountFolder: os.RelPath,
  startImport: String,
  captureDef: os.RelPath
) derives YamlCodec

case class JobDef(githubToken: String, repoGitUrl: String, captureConfigFile: Base64Encoded[SubPath]) derives ReadWriter {
  val repoHttpsGitUrl = "https"+repoGitUrl.stripPrefix("git")
}
case class CaptureTask(token: String, jobDef: JobDef)
case class State(input: String) derives ReadWriter

object Worker extends IOApp.Simple {

  override val run: IO[Unit] = (for {
    captureTaskOpt <- fetchTaskIfAvailable()
  } yield {
    println(s"Got a task: ${captureTaskOpt.isDefined}")
    for {
      task <- captureTaskOpt
    } {
      boom(task.jobDef)
    }
  }).foreverM

  private val activityTaskRequest: GetActivityTaskRequest =
    GetActivityTaskRequest.builder().activityArn(s"arn:aws:states:eu-west-1:$awsAccount:activity:pico-logic-capture").workerName("boom").build()

  def fetchTaskIfAvailable(): IO[Option[CaptureTask]] = for {
    resp <- IO.fromCompletableFuture(IO(AWS.SFN.getActivityTask(activityTaskRequest)))
  } yield for {
    token <- Option(resp.taskToken())
    input <- Option(resp.input())
  } yield CaptureTask(token, read[JobDef](read[State](input).input))

  def boom(jobDef: JobDef): Unit = {
    val git: CredentialsProvider = new UsernamePasswordCredentialsProvider(jobDef.githubToken, "")

    val tempDir: os.Path = os.temp.dir()

    println(s"going to try a clone... to $tempDir")
    val repository = Git.cloneRepository() // .setCredentialsProvider(git)
      .setTransportConfigCallback {
        case transportHttp: TransportHttp => transportHttp.setAdditionalHeaders(Map("Authorization" -> s"Bearer ${Base64.getEncoder.encodeToString(jobDef.githubToken.getBytes(UTF_8))}").asJava)
      }
      // .setBare(true)
      .setDirectory(tempDir.toIO).setURI(jobDef.repoHttpsGitUrl).call().getRepository.asInstanceOf[FileRepository]

    val repoDir = os.Path(repository.getWorkTree)
    val captureConfigFile = repoDir / jobDef.captureConfigFile.value

    require(os.exists(captureConfigFile))

    val captureConfig: Either[YamlError, CaptureConfig] = os.read(captureConfigFile).as[CaptureConfig]
    println(captureConfig)

    val configDir = os.Path(captureConfigFile.toNIO.getParent)

    for {
      cap: CaptureConfig <- captureConfig
    } {
      def repoSubPath(target: os.RelPath): SubPath = (target resolveFrom configDir) subRelativeTo repoDir

      val mountFolder: SubPath = repoSubPath(cap.mountFolder)
      val captureDef: SubPath = repoSubPath(cap.captureDef)

      println(s"mountFolder: ${os.list(repoDir / mountFolder)}")

      val captureResultsFile = tempDir / "capture.csv"

      val mpremoteProcess = os.proc(
        "mpremote",
        "connect", "id:560ca184b37d9ae2",
        "mount", repoDir / mountFolder,
        "exec", s"import ${cap.startImport}"
      ).spawn(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)

      val captureProcess =
        os.proc("TerminalCapture", "capture", "/dev/ttyACM0", repoDir / captureDef, captureResultsFile).spawn()

      captureProcess.waitFor(10000)
      mpremoteProcess.destroy()
      println(os.size(captureResultsFile))
    }
  }
}
