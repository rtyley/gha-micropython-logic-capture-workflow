package com.madgag.micropython.logiccapture.client

import cats.*
import cats.data.*
import cats.effect.IO
import com.github.tototoshi.csv.CSVReader
import com.gu.time.duration.formatting.*
import com.madgag.logic.fileformat.Foo
import com.madgag.logic.fileformat.saleae.csv.SaleaeCsv
import com.madgag.logic.time.Time.Delta
import com.madgag.logic.time.TimeParser.DeltaParser
import com.madgag.logic.{ChannelMapping, ChannelSignals}
import com.madgag.micropython.logiccapture.TimeExpectation
import com.madgag.micropython.logiccapture.aws.{AWSIO, Fail}
import com.madgag.micropython.logiccapture.client.RemoteCaptureClient.{Error, UnfinishedExecutionStates}
import com.madgag.micropython.logiccapture.model.{CaptureResult, JobDef, JobOutput}
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

  def capture[C](jobDef: JobDef, channelMapping: ChannelMapping[C]): EitherT[IO,Error,Seq[Option[ChannelSignals[Delta, C]]]] = for {
    startExecutionResponse <- EitherT.right(startExecutionOf(jobDef))
    conclusion <- findConclusionOfExecution(startExecutionResponse.executionArn, jobDef.minimumTotalExecutionTime)
  } yield conclusion.map(_.capturedData.map(sc => parseSaleaeCsv(channelMapping, sc.uncompressed)))

  private def parseSaleaeCsv[C](channelMapping: ChannelMapping[C], capData: String) = {
    val csvDetails = SaleaeCsv.csvDetails(DeltaParser, channelMapping)
    Foo.read(csvDetails.format)(CSVReader.open(Source.fromString(capData)))
  }


  val retryIfNotConcluded: ValueHandler[IO, DescribeExecutionResponse] = 
    retryUntilSuccessful(v => !UnfinishedExecutionStates.contains(v.status()), log = ResultHandler.noop)
  
  private def findConclusionOfExecution(executionArn: String, minimumExecutionTime: Duration): EitherT[IO, Error, JobOutput] = {
    // Perhaps ideally, we would call https://docs.aws.amazon.com/step-functions/latest/apireference/API_GetExecutionHistory.html#API_GetExecutionHistory_ResponseSyntax
    // and find out when the activity started. Then, we would check a couple of times to ensure that the activity
    // has not rejected our input, and then wait for the expected execution time before polling again for results.
    def glorp(rp: RetryPolicy[IO, DescribeExecutionResponse]): EitherT[IO, Error, JobOutput] = 
      EitherT(retryingOnFailures(describeExecutionOf(executionArn))(rp, retryIfNotConcluded).map(RemoteCaptureClient.Error.from))
    println(s"minimumExecutionTime: ${minimumExecutionTime.format()}")
    EitherT(TimeExpectation.timeVsExpectation(minimumExecutionTime) { dur =>
      glorp(limitRetriesByCumulativeDelay(dur.toScala, fullJitter[IO](1.second))).recoverWith {
          case _: Error.Unfinished =>
            println(s"Now into 'it should be done' time")
            glorp(limitRetriesByCumulativeDelay(30.seconds, fullJitter[IO](dur.dividedBy(20).toScala)))
        }.value
      }
    )
  }

  private def startExecutionOf(jobDef: JobDef): IO[StartExecutionResponse] =
    awsIo.glurk(StartExecutionRequest.builder().stateMachineArn(stateMachineArn).input(write(jobDef)).build())(_.startExecution)


  /**
   * Currently the micropython-logic-capture project is designed to have a single trusted user. Partly this is because
   * we allow arbitrary python code to be executed on the Pico, and arbitrary code can
   *
   * Unfortunately, calls like these are a cross-user security risk for us. The call allows (in fact, defaults to) returning
   * the execution data passed to the step function, and there is nothing to prevent access to other users executions
   * (other than the obscurity of the executionArn). A possible workaround would be to asymmetrically encrypt all user
   * data - ideally both input and output - though that makes
   */
  private def describeExecutionOf(executionArn: String): IO[DescribeExecutionResponse] =
    awsIo.glurk(DescribeExecutionRequest.builder().executionArn(executionArn).build())(_.describeExecution)

  private def getExecutionHistory(executionArn: String): IO[GetExecutionHistoryResponse] =
    awsIo.glurk(GetExecutionHistoryRequest.builder().executionArn(executionArn).build())(_.getExecutionHistory)
}

object RemoteCaptureClient {
  val UnfinishedExecutionStates: Set[ExecutionStatus] = Set(RUNNING, PENDING_REDRIVE)

  sealed trait Error

  object Error {
    case class Failed(fail: Fail) extends Error

    case class Unfinished(lastExecutionStatus: ExecutionStatus) extends Error

    def from(resultOfAllRetries: Either[DescribeExecutionResponse, DescribeExecutionResponse]): Either[Error, JobOutput] =
      resultOfAllRetries.fold(
        unfinished => Left(Unfinished(unfinished.status)),
        finished =>
          Either.cond(
          finished.status == SUCCEEDED,
          read[JobOutput](finished.output),
          Failed(Fail(finished.error, finished.cause)))
      )
  }
}
