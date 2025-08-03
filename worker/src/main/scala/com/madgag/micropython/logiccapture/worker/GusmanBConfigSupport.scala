package com.madgag.micropython.logiccapture.worker

import com.madgag.logic.GpioPin
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig.Trigger.TriggerType
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig.CaptureChannel
import com.madgag.micropython.logiccapture.model.{CaptureDef, Trigger}

object GusmanBConfigSupport {
  
  val AllGpio: Seq[GpioPin] = ((2 to 22) ++ (26 to 28)).map(GpioPin(_))

  extension (trigger: Trigger)
    def toGusmanB: GusmanBConfig.Trigger = trigger match {
      case tp: Trigger.Pattern => GusmanBConfig.Trigger(
        triggerType = TriggerType.Complex,
        triggerChannel =  GusmanBConfig.Channel.ChannelsByGpioPin(tp.baseGpioPin),
        triggerBitCount = tp.bits.intSize,
        triggerPattern = Some(tp.bits.toInt(signed = false))
      )
      case te: Trigger.Edge => GusmanBConfig.Trigger(
        triggerType = TriggerType.Edge,
        triggerChannel = GusmanBConfig.Channel.ChannelsByGpioPin(te.gpioPin),
        triggerInverted = Some(!te.goingTo)
      )
    }

  extension (captureDef: CaptureDef)
    def toGusmanB: GusmanBConfig = GusmanBConfig(
      frequency = captureDef.sampling.frequency,
      preTriggerSamples = captureDef.sampling.preTriggerSamples,
      postTriggerSamples = captureDef.sampling.postTriggerSamples,
      totalSamples = captureDef.sampling.totalSamples,
      trigger = captureDef.trigger.toGusmanB,
      captureChannels = captureDef.gpioPins.toSeq.sorted.map {
        gpioPin => GusmanBConfig.CaptureChannel(GusmanBConfig.Channel.ChannelsByGpioPin(gpioPin), gpioPin.toString)
      }
    )

}