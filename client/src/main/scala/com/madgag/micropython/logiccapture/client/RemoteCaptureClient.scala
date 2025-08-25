package com.madgag.micropython.logiccapture.client

import cats.*
import cats.effect.{IO, Temporal}
import cats.syntax.all.*
import com.github.tototoshi.csv.CSVReader
import com.madgag.logic.Time.Delta
import com.madgag.logic.TimeParser.DeltaParser
import com.madgag.logic.fileformat.Foo
import com.madgag.logic.fileformat.saleae.csv.SaleaeCsv
import com.madgag.logic.{ChannelMapping, ChannelSignals, Time, TimeParser}
import com.madgag.micropython.logiccapture.TimeExpectation
import com.madgag.micropython.logiccapture.aws.AWSIO
import com.madgag.micropython.logiccapture.client.RemoteCaptureClient.{Error, UnfinishedExecutionStates}
import com.madgag.micropython.logiccapture.model.{CaptureResult, JobDef, JobOutput}
import com.madgag.micropython.logiccapture.worker.aws.Fail
import retry.*
import retry.ResultHandler.retryUntilSuccessful
import retry.RetryPolicies.*
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.model.*
import software.amazon.awssdk.services.sfn.model.ExecutionStatus.{PENDING_REDRIVE, RUNNING, SUCCEEDED}
import upickle.default.*

import java.time.Duration
import scala.concurrent.duration.*
import scala.io.Source
import scala.jdk.DurationConverters.*

class RemoteCaptureClient(
  awsIo: AWSIO[SfnAsyncClient, SfnRequest],
  stateMachineArn: String
) {

  def capture[C](jobDef: JobDef, channelMapping: ChannelMapping[C]): IO[Seq[Option[ChannelSignals[Delta, C]]]] = for {
    startExecutionResponse <- startExecutionOf(jobDef)
    conclusion <- findConclusionOfExecution(startExecutionResponse.executionArn, jobDef.minimumTotalExecutionTime)
  } yield (for {
    conc <- conclusion.toOption
  } yield conc.map(_.capturedData.map(parseSaleaeCsv(channelMapping, _)))).get

  private def parseSaleaeCsv[C](channelMapping: ChannelMapping[C], capData: String) = {
    val csvDetails = SaleaeCsv.csvDetails(DeltaParser, channelMapping)
    Foo.read(csvDetails.format)(CSVReader.open(Source.fromString(capData)))
  }

  private def findConclusionOfExecution(executionArn: String, minimumExecutionTime: Duration): IO[Either[Error, JobOutput]] = 
    TimeExpectation.timeVsExpectation(minimumExecutionTime) { dur =>
    Temporal[IO].sleep(dur.toScala) >> retryingOnFailures(describeExecutionOf(executionArn))(
      limitRetriesByCumulativeDelay(30.seconds, fullJitter[IO](minimumExecutionTime.dividedBy(20).toScala)),
      retryUntilSuccessful(v => !UnfinishedExecutionStates.contains(v.status()), log = ResultHandler.noop)
    ).map(RemoteCaptureClient.Error.from)
  }

  private def startExecutionOf(jobDef: JobDef): IO[StartExecutionResponse] =
    awsIo.glurk(StartExecutionRequest.builder().stateMachineArn(stateMachineArn).input(write(jobDef)).build())(_.startExecution)

  private def describeExecutionOf(executionArn: String): IO[DescribeExecutionResponse] =
    awsIo.glurk(DescribeExecutionRequest.builder().executionArn(executionArn).build())(_.describeExecution)

}

object RemoteCaptureClient {
  val UnfinishedExecutionStates: Set[ExecutionStatus] = Set(RUNNING, PENDING_REDRIVE)

  sealed trait Error

  object Error {
    case class Failed(fail: Fail) extends Error

    case class Unfinished(lastExecutionStatus: ExecutionStatus) extends Error

    def from(resultOfAllRetries: Either[DescribeExecutionResponse, DescribeExecutionResponse]): Either[Error, JobOutput] = resultOfAllRetries.fold(
      unfinished => Left(Unfinished(unfinished.status)),
      finished => Either.cond(
        finished.status == SUCCEEDED,
        read[JobOutput](finished.output),
        Failed(Fail(finished.error, finished.cause)))
      )
  }
}
