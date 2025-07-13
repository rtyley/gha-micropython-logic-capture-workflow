package com.madgag.micropython.logiccapture.worker

import com.madgag.micropython.logiccapture.worker.StoreCompressed.compress
import upickle.default.*

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.zip.{DataFormatException, Deflater, Inflater}

case class StoreCompressed(uncompressed: String) {
  val asCompressed: String = {
    val compressedBytes: Array[Byte] = compress(uncompressed.getBytes(UTF_8))
    Base64.getEncoder.encodeToString(compressedBytes)
  }
}

object StoreCompressed {
  given ReadWriter[StoreCompressed] = readwriter[String].bimap[StoreCompressed](_.asCompressed, s => uncompress(s))

  def uncompress(s: String): StoreCompressed = {
    println(s"Compressed string: ${s.length}")
    val compressedBytes: Array[Byte] = Base64.getDecoder.decode(s)
    StoreCompressed(new String(decompress(compressedBytes), UTF_8))
  }

  def compress(input: Array[Byte]): Array[Byte] = {
    val deflater = new Deflater()
    deflater.setInput(input)
    deflater.finish()
    val outputStream = new ByteArrayOutputStream
    val buffer = new Array[Byte](1024)
    while (!deflater.finished) {
      val compressedSize = deflater.deflate(buffer)
      outputStream.write(buffer, 0, compressedSize)
    }
    outputStream.toByteArray
  }

  @throws[DataFormatException]
  def decompress(input: Array[Byte]): Array[Byte] = {
    val inflater = new Inflater()
    inflater.setInput(input)
    val outputStream = new ByteArrayOutputStream()
    val buffer = new Array[Byte](1024)
    while (!inflater.finished) {
      val decompressedSize = inflater.inflate(buffer)
      outputStream.write(buffer, 0, decompressedSize)
    }
    outputStream.toByteArray
  }
}