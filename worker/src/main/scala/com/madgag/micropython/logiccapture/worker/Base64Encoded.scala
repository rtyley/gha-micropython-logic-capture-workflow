package com.madgag.micropython.logiccapture.worker

import com.madgag.micropython.logiccapture.worker.StoreCompressed.compress
import upickle.default.*

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.zip.{DataFormatException, Deflater, Inflater}

case class Base64Encoded[T: ReadWriter](value: T) {
  val asBase64: String = Base64.getEncoder.encodeToString(write(value).getBytes(UTF_8))
}

object Base64Encoded {
  given [T: ReadWriter]: ReadWriter[Base64Encoded[T]] =
    readwriter[String].bimap[Base64Encoded[T]](_.asBase64, s => {
      println(s"Base64 string: $s")
      Base64Encoded(read[T](s"\"${new String(Base64.getDecoder.decode(s))}\""))
    })
}