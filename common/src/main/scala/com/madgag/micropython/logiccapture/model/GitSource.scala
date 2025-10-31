package com.madgag.micropython.logiccapture.model

import com.madgag.micropython.logiccapture.git.BearerAuthTransportConfig
import com.madgag.micropython.logiccapture.git.BearerAuthTransportConfig.bearerAuth
import org.eclipse.jgit.api.{TransportCommand, TransportConfigCallback}
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import upickle.default.*

case class GitSource(githubToken: String, gitSpec: GitSpec) derives ReadWriter {
  lazy val credentialsProvider = new UsernamePasswordCredentialsProvider("x-access-token", githubToken)
  lazy val transportConfigCallback: TransportConfigCallback = bearerAuth(githubToken)
}

//TransportCommand<C extends GitCommand, T> extends GitCommand < T >
object GitSource {
  extension [C <: TransportCommand[C, T], T] (tc: TransportCommand[C, T])
    def authWith(gitSource: GitSource): C =
      tc.setCredentialsProvider(gitSource.credentialsProvider)
      .setTransportConfigCallback(gitSource.transportConfigCallback)

}
