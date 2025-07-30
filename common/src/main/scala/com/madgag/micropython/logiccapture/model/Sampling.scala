package com.madgag.micropython.logiccapture.model

import upickle.default.ReadWriter

import java.time.Duration
import java.time.Duration.ofSeconds

case class Sampling(
  frequency: Long,
  preTriggerSamples: Int,
  postTriggerSamples: Int
) derives ReadWriter {
  val sampleIntervalDuration: Duration = ofSeconds(1).dividedBy(frequency)
  val postTriggerDuration: Duration = sampleIntervalDuration.multipliedBy(postTriggerSamples)
  val totalSamples: Int = preTriggerSamples + postTriggerSamples
}
