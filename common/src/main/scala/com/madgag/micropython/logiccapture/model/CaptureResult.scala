package com.madgag.micropython.logiccapture.model

import upickle.default.*

case class CaptureResult(captureProcessOutput: String, capturedData: Option[String]) derives ReadWriter
