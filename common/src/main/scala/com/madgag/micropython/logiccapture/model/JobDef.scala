package com.madgag.micropython.logiccapture.model

import org.eclipse.jgit.lib.ObjectId
import os.SubPath
import scodec.bits.BitVector
import upickle.default.*

given ReadWriter[SubPath] = readwriter[String].bimap[SubPath](_.toString, SubPath(_))
given ReadWriter[ObjectId] = readwriter[String].bimap[ObjectId](_.toString, ObjectId.fromString)
given ReadWriter[BitVector] = readwriter[String].bimap[BitVector](_.toBin, BitVector.fromValidBin(_))

case class GitSpec(gitUrl: String, commitId: ObjectId, subFolder: SubPath) derives ReadWriter {
  val httpsGitUrl: String = "https" + gitUrl.stripPrefix("git")
}

sealed trait Trigger derives ReadWriter

object Trigger {
  case class Pattern(bits: BitVector, baseGpioPin: Int) extends Trigger
  case class Edge(gpioPin: Int, goingTo: Boolean) extends Trigger
}

case class CaptureDef(
  sampling: Sampling,
  gpioPins: Set[Int],
  trigger: Trigger
) derives ReadWriter

case class GitSource(githubToken: String, gitSpec: GitSpec) derives ReadWriter

case class ExecutionDef(mountFolder: SubPath, exec: String) derives ReadWriter

case class ExecuteAndCaptureDef(execution: ExecutionDef, capture: CaptureDef) derives ReadWriter

case class JobDef(sourceDef: GitSource, executeAndCapture: ExecuteAndCaptureDef) derives ReadWriter
