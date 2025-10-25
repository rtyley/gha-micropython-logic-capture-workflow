package com.madgag.micropython.logiccapture

import cats.effect.IO

import java.time.Duration
import java.time.temporal.ChronoUnit.MILLIS
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*
import com.gu.time.duration.formatting.*

object TimeExpectation {
  def timeVsExpectation[A](minimumExpectation: Duration)(task: Duration => IO[A]): IO[A] = {
    task(minimumExpectation).timed.map {
      case (timedDuration, result) =>
        println(f"minimumExpectation=$minimumExpectation actual=${timedDuration.toJava} ${timedDuration.div(minimumExpectation.toScala)}%2.2f")
        result
    }
  }
}

extension [T] (io: IO[T])
  def logTime(desc: String): IO[T] = IO.println(s"$desc...") >> io.timed.flatMap {
    case (d, v) => IO.println(s"$desc ...finished in ${d.toJava.truncatedTo(MILLIS).format()}") >> IO.pure(v)
  }

  def logSlow(desc: String, threshold: scala.concurrent.duration.FiniteDuration): IO[T] = io.timed.flatMap {
    case (d, v) => IO.whenA(d > threshold)(IO.println(s"$desc ...finished in ${d.toJava.truncatedTo(MILLIS).format()}")) >> IO.pure(v)
  }