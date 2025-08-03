package com.madgag.micropython.logiccapture.worker

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StoreCompressedTest extends AnyFlatSpec with Matchers {
  "StoreCompressed" should "fetch" in {

    StoreCompressed.uncompress(StoreCompressed("bang").asCompressed).uncompressed shouldBe "bang"
  }
}
