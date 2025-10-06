package com.madgag.micropython.logiccapture.worker

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FunkTest  extends AnyFlatSpec with Matchers {
  "Making Pi" should "work" in {
    val pin = Funk.getDatOut()
    println(s"Got me a pin: $pin")
  }
}
