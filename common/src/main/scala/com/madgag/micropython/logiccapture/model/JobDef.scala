package com.madgag.micropython.logiccapture.model

import cats.*
import cats.data.*
import cats.syntax.all.*
import cats.*
import cats.data.*
import cats.syntax.all.*
import com.madgag.logic.GpioPin
import com.madgag.logic.fileformat.gusmanb.{BoardDef, CaptureMode}
import org.eclipse.jgit.lib.{Constants, ObjectId}
import os.SubPath
import scodec.bits.BitVector
import upickle.default.*
import com.madgag.micropython.logiccapture.model.GusmanBConfigSupport.toGusmanBChannel
import GusmanBConfigSupport.given
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository

import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Duration.ZERO
import scala.collection.immutable.SortedSet
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

given ReadWriter[SubPath] = readwriter[String].bimap[SubPath](_.toString, SubPath(_))
given ReadWriter[ObjectId] = readwriter[String].bimap[ObjectId](_.name, ObjectId.fromString)
given ReadWriter[BitVector] = readwriter[String].bimap[BitVector](_.toBin, BitVector.fromValidBin(_))
given ReadWriter[GpioPin] = readwriter[Int].bimap[GpioPin](_.number, GpioPin(_))


case class GitSpec(gitUrl: String, commitId: ObjectId) derives ReadWriter {
  // require(gitUrl.startsWith("git://") && gitUrl.endsWith(".git"))

  // val httpsGitUrl: String = "https" + gitUrl.stripPrefix("git")
}

object GitSpec {
  def forPathInThisRepo(path: os.SubPath) = {
    extension (paths: java.util.Set[String])
      def relevantPaths: Set[SubPath] =
        paths.asScala.toSet.map(f => SubPath(f.split('/').toIndexedSeq)).filter(_.startsWith(path))

    val git = Git.open(new File(""))
    // git.diff().setPathFilter(TreeFilter)
    val status = git.status().call()
    val uncommitted = status.getUncommittedChanges.relevantPaths
    require(uncommitted.isEmpty)
    require(status.getUntracked.relevantPaths.isEmpty)
    val remoteUris = git.remoteList().call().asScala.head.getURIs.asScala
    println(remoteUris)
    val headCommit = git.getRepository.resolve(Constants.HEAD)
    println(headCommit)
    GitSpec(remoteUris.head.toString, headCommit)
  }
}

sealed trait Trigger derives ReadWriter

object Trigger {
  /**
   *
   * @param bits
   * @param baseGpioPin - independent of what is being captured, this is the pin that is the base of the consecutive
   *                    pins read for the trigger pattern
   */
  case class Pattern(bits: BitVector, baseGpioPin: GpioPin) extends Trigger {
    lazy val stateByPin: Map[GpioPin, Boolean] = 
      bits.toIndexedSeq.zipWithIndex.map((state, index) => GpioPin(index + baseGpioPin.number) -> state).toMap
  }
  case class Edge(gpioPin: GpioPin, goingTo: Boolean) extends Trigger
}

case class CaptureDef(
  sampling: Sampling,
  gpioPins: SortedSet[GpioPin],
  trigger: Trigger
) derives ReadWriter {
  lazy val captureMode: CaptureMode = CaptureMode.forChannels(NonEmptySet.fromSet(gpioPins.map(_.toGusmanBChannel)).get)
  def isValidFor(boardDef: BoardDef): Boolean = {
    sampling.totalSamples <= boardDef.maxSamplesFor(captureMode)
  }
}

case class GitSource(githubToken: String, gitSpec: GitSpec) derives ReadWriter

case class ExecutionDef(mountFolder: SubPath, exec: String) derives ReadWriter

case class ExecuteAndCaptureDef(execution: ExecutionDef, capture: CaptureDef) derives ReadWriter

given Monoid[Duration] = Monoid.instance(ZERO, _ plus _)

case class JobDef(sourceDef: GitSource, execs: Seq[ExecuteAndCaptureDef]) derives ReadWriter {
  val minimumTotalExecutionTime: Duration = execs.foldMap(_.capture.sampling.postTriggerDuration.plusSeconds(4)).plusSeconds(3)
}

type JobOutput = Seq[CaptureResult]
