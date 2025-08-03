package com.madgag.micropython.logiccapture.model

import com.madgag.logic.GpioPin
import org.eclipse.jgit.lib.ObjectId
import os.SubPath
import scodec.bits.BitVector
import upickle.default.*

given ReadWriter[SubPath] = readwriter[String].bimap[SubPath](_.toString, SubPath(_))
given ReadWriter[ObjectId] = readwriter[String].bimap[ObjectId](_.toString, ObjectId.fromString)
given ReadWriter[BitVector] = readwriter[String].bimap[BitVector](_.toBin, BitVector.fromValidBin(_))
given ReadWriter[GpioPin] = readwriter[Int].bimap[GpioPin](_.number, GpioPin(_))


case class GitSpec(gitUrl: String, commitId: ObjectId) derives ReadWriter {
  val httpsGitUrl: String = "https" + gitUrl.stripPrefix("git")
}

sealed trait Trigger derives ReadWriter

object Trigger {
  case class Pattern(bits: BitVector, baseGpioPin: GpioPin) extends Trigger
  case class Edge(gpioPin: GpioPin, goingTo: Boolean) extends Trigger
}

case class CaptureDef(
  sampling: Sampling,
  gpioPins: Set[GpioPin],
  trigger: Trigger
) derives ReadWriter

case class GitSource(githubToken: String, gitSpec: GitSpec) derives ReadWriter

case class ExecutionDef(mountFolder: SubPath, exec: String) derives ReadWriter

case class ExecuteAndCaptureDef(execution: ExecutionDef, capture: CaptureDef) derives ReadWriter

case class JobDef(sourceDef: GitSource, executeAndCapture: ExecuteAndCaptureDef) derives ReadWriter
