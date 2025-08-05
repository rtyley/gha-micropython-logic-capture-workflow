package com.madgag.micropython.logiccapture

import cats.effect.IO

import java.time.Duration
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

object TimeExpectation {
  def timeVsExpectation[A](minimumExpectation: Duration)(task: Duration => IO[A]): IO[A] = {
    task(minimumExpectation).timed.map {
      case (timedDuration, result) =>
        println(f"minimumExpectation=$minimumExpectation actual=${timedDuration.toJava} ${timedDuration.div(minimumExpectation.toScala)}%2.2f")
        result
    }
  }
}
