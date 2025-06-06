package com.madgag.micropython.logiccapture.worker.git

import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.TransportHttp

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.jdk.CollectionConverters.*

object BearerAuthTransportConfig {
  def bearerAuth(token: String): TransportConfigCallback = {
    case http: TransportHttp =>
      http.setAdditionalHeaders(Map("Authorization" -> s"Bearer ${Base64.getEncoder.encodeToString(token.getBytes(UTF_8))}").asJava)
  }
}