package com.madgag.micropython.logiccapture.worker

import com.madgag.logic.fileformat.gusmanb.GusmanBConfig
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig.Trigger.TriggerType
import com.madgag.logic.fileformat.gusmanb.GusmanBConfig.gusmanbChannel
import com.madgag.micropython.logiccapture.model.{CaptureDef, Trigger}

extension (so: GusmanBConfig.Trigger.type)
  def from(trigger: Trigger): GusmanBConfig.Trigger = trigger match {
    case tp: Trigger.Pattern => GusmanBConfig.Trigger(
      triggerType = TriggerType.Complex,
      triggerChannel = gusmanbChannel(tp.baseGpioPin),
      triggerBitCount = tp.bits.intSize,
      triggerPattern = Some(tp.bits.toInt(signed = false))
    )
    case te: Trigger.Edge => GusmanBConfig.Trigger(
      triggerType = TriggerType.Edge,
      triggerChannel = gusmanbChannel(te.gpioPin),
      triggerInverted = Some(!te.goingTo)
    )
  }

extension (so: GusmanBConfig.type)
  def from(captureDef: CaptureDef): GusmanBConfig.Trigger = trigger match {
    case tp: Trigger.Pattern => GusmanBConfig.Trigger(
      triggerType = TriggerType.Complex,
      triggerChannel = gusmanbChannel(tp.baseGpioPin),
      triggerBitCount = tp.bits.intSize,
      triggerPattern = Some(tp.bits.toInt(signed = false))
    )
    case te: Trigger.Edge => GusmanBConfig.Trigger(
      triggerType = TriggerType.Edge,
      triggerChannel = gusmanbChannel(te.gpioPin),
      triggerInverted = Some(!te.goingTo)
    )
  }