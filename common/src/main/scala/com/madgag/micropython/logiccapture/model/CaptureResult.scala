package com.madgag.micropython.logiccapture.model

import com.madgag.micropython.logiccapture.worker.StoreCompressed
import upickle.default.*

case class CaptureResult(captureProcessOutput: String, capturedData: Option[StoreCompressed]) derives ReadWriter
