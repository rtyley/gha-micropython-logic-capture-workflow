package com.example

import cats.effect.unsafe.implicits.global
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig
import com.madgag.logic.{ChannelMapping, GpioPin}
import com.madgag.micropython.logiccapture.aws.{AWS, AWSIO}
import com.madgag.micropython.logiccapture.client.RemoteCaptureClient
import com.madgag.micropython.logiccapture.model.*
import org.eclipse.jgit.lib.ObjectId
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{Inspectors, OptionValues}
import scodec.bits.BitVector
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.SfnRequest

import java.time.Duration
import java.time.Duration.{ofMillis, ofNanos, ofSeconds}
import scala.collection.immutable.SortedSet

class TestFunk extends AnyFlatSpec with Matchers with ScalaFutures with Inspectors with OptionValues {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(
    timeout = scaled(Span(300, Seconds)),
    interval = scaled(Span(500, Millis))
  )

  private val token: String = sys.env("LOGIC_CAPTURE_REPO_CLONE_GITHUB_TOKEN")

  val aws = new AWS(profile = "logic-capture-client")

  val remoteCaptureClient = new RemoteCaptureClient(
    awsIo = new AWSIO[SfnAsyncClient, SfnRequest](aws.SFN),
    stateMachineArn = s"arn:aws:states:${AWS.region.id}:${AWS.awsAccountId}:stateMachine:pico-logic-capture"
  )
  
  "TestFunk" should "check that the Pico does as it is supposed to" in {
    val freqSamples = for {
      postTriggerDuration <- Seq(ofMillis(1), ofMillis(19))
      sampleInterval <- Seq(ofNanos(50), ofNanos(10000))
    } yield FreqSample.givenTiming(postTriggerDuration, sampleInterval)

    whenReady(remoteCaptureClient.capture(jobDef(freqSamples), ChannelMapping[GpioPin](
      GusmanBConfig.Channel.AllAvailableGpioPins.map(gpioPin => gpioPin.toString -> gpioPin).toSeq *
    )).value.map(_.left.map(err => new RuntimeException(err.toString)).toTry.get).unsafeToFuture()) { signals =>
      (freqSamples.zip(signals)).foreach((freqSample, signal) => println(s"$freqSample : ${signal.value.summary}"))

      forAll(signals)(_.value.isConstant shouldBe false)
    }
  }

  val gitSource = GitSource(
    token,
    GitSpec(
      "git://github.com/rtyley/gha-micropython-logic-capture-workflow.git",
      ObjectId.fromString("38806ae0df4d7fcad904868f8388031a462f5d41")
    )
  )

  private def jobDef(freqSamples: Seq[FreqSample]) = JobDef(
    gitSource,
    freqSamples.map { fs =>
      ExecuteAndCaptureDef(
        ExecutionDef("sample-project/device-fs", "import pio_blink"),
        CaptureDef(
          Sampling(frequency = fs.freq, preTriggerSamples = 512, postTriggerSamples = fs.samples),
          SortedSet.from((2 to 8).map(GpioPin(_))),
          // SortedSet.from((2 to 22) ++ (26 to 28)).map(GpioPin(_)),
          // Trigger.Edge(GpioPin(2), goingTo = false)
          Trigger.Pattern(BitVector.bits(Seq(false, false, true)), GpioPin(2))
        )
      )
    }
  )
}

case class FreqSample(freq: Long, samples: Int)

object FreqSample {
  def givenTiming(postTriggerDuration: Duration, sampleInterval: Duration) = FreqSample(
    freq = ofSeconds(1).dividedBy(sampleInterval),
    samples = postTriggerDuration.dividedBy(sampleInterval).toInt
  )
}