package com.example

import cats.effect.unsafe.implicits.global
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig
import com.madgag.logic.{ChannelMapping, GpioPin}
import com.madgag.micropython.logiccapture.aws.AWSIO
import com.madgag.micropython.logiccapture.client.RemoteCaptureClient
import com.madgag.micropython.logiccapture.model.*
import org.eclipse.jgit.lib.ObjectId
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.Region.EU_WEST_1
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.SfnRequest

class TestFunk extends AnyFlatSpec with Matchers with ScalaFutures with Inspectors with OptionValues {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(
    timeout = scaled(Span(300, Seconds)),
    interval = scaled(Span(500, Millis))
  )

  private val awsAccountId: String = sys.env("AWS_ACCOUNT_ID")
  private val awsRegion: Region = sys.env.get("AWS_REGION").map(Region.of).getOrElse(EU_WEST_1)

  private val token: String = sys.env("PICO_CAPTURE_GITHUB_TOKEN")

  val remoteCaptureClient = new RemoteCaptureClient(
    awsIo = new AWSIO[SfnAsyncClient, SfnRequest](SfnAsyncClient.create()),
    stateMachineArn = s"arn:aws:states:${awsRegion.id}:$awsAccountId:stateMachine:pico-logic-capture"
  )
  
  "TestFunk" should "check that the Pico does as it is supposed to" in {
    val freqSamples = for {
      freq <- Seq(3200, 80000)
      samples <- Seq(6000, 60000)
    } yield FreqSample(freq, samples)

    whenReady(remoteCaptureClient.capture(jobDef(freqSamples), ChannelMapping[GpioPin](
      GusmanBConfig.Channel.AllAvailableGpioPins.map(gpioPin => gpioPin.toString -> gpioPin).toSeq *
    )).unsafeToFuture()) { signals =>
      forAll(signals)(_.value.isConstant shouldBe false)
    }
  }

  val gitSource = GitSource(
    token,
    GitSpec(
      "git://github.com/rtyley/gha-micropython-logic-capture-workflow.git",
      ObjectId.fromString("7e0b8dba90043b32e3ccc287fbd0591966380d12")
    )
  )

  private def jobDef(freqSamples: Seq[FreqSample]) = JobDef(
    gitSource,
    freqSamples.map { fs =>
      ExecuteAndCaptureDef(
        ExecutionDef("sample-project/device-fs", "import pio_blink"),
        CaptureDef(
          Sampling(frequency = fs.freq, preTriggerSamples = 512, postTriggerSamples = fs.samples),
          ((2 to 22) ++ (26 to 28)).map(GpioPin(_)).toSet,
          Trigger.Edge(GpioPin(2), goingTo = false)
        )
      )
    }
  )
}

case class FreqSample(freq: Long, samples: Int)