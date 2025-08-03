package com.example

import cats.effect.unsafe.implicits.global
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig
import com.madgag.logic.{ChannelMapping, GpioPin}
import com.madgag.micropython.logiccapture.aws.AWSIO
import com.madgag.micropython.logiccapture.client.RemoteCaptureClient
import com.madgag.micropython.logiccapture.model.*
import org.eclipse.jgit.lib.ObjectId
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.Region.EU_WEST_1
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.SfnRequest

class TestFunk extends AnyFlatSpec with Matchers with ScalaFutures with IntegrationPatience {

  private val awsAccountId: String = sys.env("AWS_ACCOUNT_ID")
  private val awsRegion: Region = sys.env.get("AWS_REGION").map(Region.of).getOrElse(EU_WEST_1)

  val remoteCaptureClient = new RemoteCaptureClient(
    awsIo = new AWSIO[SfnAsyncClient, SfnRequest](SfnAsyncClient.create()),
    stateMachineArn = s"arn:aws:states:${awsRegion.id}:$awsAccountId:stateMachine:pico-logic-capture"
  )
  
  "TestFunk" should "check that the Pico does as it is supposed to" in {
    val jobDef = JobDef(
      GitSource(
        "ghs_",
        GitSpec(
          "git@github.com:rtyley/gha-micropython-logic-capture-workflow.git",
          ObjectId.fromString("2bf20e9671410d9b08bd86daf02799ac4e1f669c")
        )
      ),
      ExecuteAndCaptureDef(
        ExecutionDef("sample-project/device-fs", "import pio_blink"),
        CaptureDef(
          Sampling(frequency = 3200, preTriggerSamples = 512, postTriggerSamples = 65512),
          ((2 to 22) ++ (26 to 28)).map(GpioPin(_)).toSet,
          Trigger.Edge(GpioPin(2), goingTo = false)
        )
      )
    )
    
    whenReady(remoteCaptureClient.capture(jobDef, ChannelMapping[GpioPin](
      GusmanBConfig.Channel.AllAvailableGpioPins.map(gpioPin => gpioPin.toString -> gpioPin).toSeq*
    )).unsafeToFuture()) { signals =>
      signals.isConstant shouldBe false
    }
  }
}