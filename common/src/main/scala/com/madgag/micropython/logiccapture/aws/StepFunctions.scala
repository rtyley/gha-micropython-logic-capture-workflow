package com.madgag.micropython.logiccapture.aws

import cats.effect.IO
import software.amazon.awssdk.awscore.{AwsClient, AwsRequest}

import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class AWSIO[Client <: AwsClient, Req <: AwsRequest](client: Client) {

  def glurk[A <: Req, B](request: A)(f: Client => A => CompletableFuture[B]): IO[B] =
    IO(println(s"Sending $request")) >> IO.fromCompletableFuture(IO(f(client)(request)))

//  def burk[B, Resp](building: B => B)(f: Client => Consumer[B] => CompletableFuture[Resp]): IO[Resp] =
//    IO(println(s"Sending")) >> IO.fromCompletableFuture(IO(f(client)(request)))
}
