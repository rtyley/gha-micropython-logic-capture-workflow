package com.madgag.micropython.logiccapture.aws

import software.amazon.awssdk.auth.credentials.*
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.Region.EU_WEST_1
import software.amazon.awssdk.services.sfn.{SfnAsyncClient, SfnAsyncClientBuilder}

object AWS {
  val awsAccountId: String = sys.env("LOGIC_CAPTURE_AWS_ACCOUNT_ID")
  val region: Region = EU_WEST_1

  def credentialsForDevAndProd(devProfile: String, prodCreds: AwsCredentialsProvider): AwsCredentialsProviderChain =
    AwsCredentialsProviderChain.of(prodCreds, ProfileCredentialsProvider.builder().profileName(devProfile).build())

  lazy val credentials: AwsCredentialsProvider =  // `aws configure --profile logic-capture-client`
    DefaultCredentialsProvider.builder().profileName("logic-capture-client").build()

  def build[T, B <: AwsClientBuilder[B, T]](builder: B, creds: AwsCredentialsProvider = credentials): T =
    builder.credentialsProvider(creds).region(region).build()

  val SFN: SfnAsyncClient = build[SfnAsyncClient, SfnAsyncClientBuilder](SfnAsyncClient.builder())
}
