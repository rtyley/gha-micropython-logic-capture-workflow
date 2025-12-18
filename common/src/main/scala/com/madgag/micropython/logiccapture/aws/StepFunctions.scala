package com.madgag.micropython.logiccapture.aws

import cats.*
import cats.effect.IO
import cats.syntax.all.*
import software.amazon.awssdk.awscore.{AwsClient, AwsRequest}
import upickle.default.*

import java.util.concurrent.CompletableFuture

class AWSIO[Client <: AwsClient, Req <: AwsRequest](client: Client) {

  def glurk[A <: Req, B](request: A)(f: Client => A => CompletableFuture[B]): IO[B] =
    IO(println(s"Sending ${request.getClass.getSimpleName}")) >> IO.fromCompletableFuture(IO(f(client)(request)))

}


case class Fail(error: String, cause: String) derives ReadWriter {
  require(cause.length <= 32768)
  require(error.length <= 256)
}
