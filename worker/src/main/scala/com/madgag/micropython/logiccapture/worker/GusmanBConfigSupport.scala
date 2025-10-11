package com.madgag.micropython.logiccapture.worker

import com.madgag.logic.GpioPin
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig.Trigger.TriggerType
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig.CaptureChannel
import com.madgag.micropython.logiccapture.model.{CaptureDef, Trigger}

object GusmanBConfigSupport {
  
  extension (gpioPin: GpioPin)
    def toGusmanBChannel: GusmanBConfig.Channel = GusmanBConfig.Channel.ChannelsByGpioPin(gpioPin)

    def toGusmanBCaptureChannel: GusmanBConfig.CaptureChannel =
      GusmanBConfig.CaptureChannel(gpioPin.toGusmanBChannel, gpioPin.toString)


  extension (trigger: Trigger)
    def toGusmanB: GusmanBConfig.Trigger = trigger match {
      case tp: Trigger.Pattern =>
        GusmanBConfig.Trigger.withOptimalTypeForPattern(tp.bits, tp.baseGpioPin.toGusmanBChannel).get
      case te: Trigger.Edge => GusmanBConfig.Trigger(
        triggerType = TriggerType.Edge,
        triggerChannel = te.gpioPin.toGusmanBChannel, // TODO - find out what works
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
      captureChannels = captureDef.gpioPins.toSeq.sorted.map(_.toGusmanBCaptureChannel)
    )

}