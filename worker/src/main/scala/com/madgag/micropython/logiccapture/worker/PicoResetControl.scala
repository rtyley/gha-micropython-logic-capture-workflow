package com.madgag.micropython.logiccapture.worker

import cats.effect.{IO, Resource}
import com.gu.time.duration.formatting.*
import com.madgag.micropython.logiccapture.worker.PicoResetControl.ResetTime
import com.pi4j.Pi4J
import com.pi4j.boardinfo.util.BoardInfoHelper
import com.pi4j.context.Context
import com.pi4j.io.gpio.digital.{DigitalOutput, DigitalOutputProvider}
import com.pi4j.plugin.ffm.providers.gpio.FFMDigitalOutputProviderImpl

import java.time.temporal.ChronoUnit.MILLIS
import java.time.{Duration, Instant}
import java.util.concurrent.CompletableFuture
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.DurationConverters.*
import scala.jdk.FutureConverters.*

object PicoResetControl {

  opaque type ResetTime = Instant

  object ResetTime:
    def apply(instant: Instant): ResetTime = instant

    def sleepUntilAfterReset(d: FiniteDuration)(using rt: ResetTime): IO[Unit] =
      timeSinceReset.flatMap(tsr => IO.sleep(d - tsr.toScala))

    def timeSinceReset(using rt: ResetTime): IO[Duration] =
      IO.realTimeInstant.map(Duration.between(rt, _))

    extension [T](io: IO[T])

      def logTimeSR(desc: String)(using ResetTime): IO[T] = IO.println(s"$desc...") >> (for {
        dv <- io.timed
        d = dv._1
        tsr <- timeSinceReset
        _ <- IO.println(s"$desc ...finished in ${d.toJava.truncatedTo(MILLIS).format()} (${tsr.truncatedTo(MILLIS).format()} after reset)")
      } yield dv._2)


  val pi4JResource: Resource[IO, Context] = Resource.make {
    IO.blocking {
      val pi4j = Pi4J.newContextBuilder()
        .noAutoDetect()
        .add(new FFMDigitalOutputProviderImpl())
        .setGpioChipName("gpiochip0").build() // blocking IO

      pi4j.providers().describe().print(System.out)
      println("Board model: " + pi4j.boardInfo.getBoardModel.getLabel)
      println("Operating system: " + pi4j.boardInfo.getOperatingSystem)
      println("Java versions: " + pi4j.boardInfo.getJavaInfo)
      // This info is also available directly from the BoardInfoHelper,
      // and with some additional realtime data.
      println("Board model: " + BoardInfoHelper.current.getBoardModel.getLabel)
      println("Raspberry Pi model with RP1 chip (Raspberry Pi 5): " + BoardInfoHelper.usesRP1)
      println("OS is 64-bit: " + BoardInfoHelper.is64bit)
      println("Board temperature (°C): " + BoardInfoHelper.getBoardReading.getTemperatureInCelsius)
      pi4j
    }
  }(pi4j => IO.fromFuture(IO(pi4j.asyncShutdown().asInstanceOf[CompletableFuture[Unit]].asScala)))

  def resetPinFor(pi4j: Context): IO[DigitalOutput] = IO.blocking {
    println("GONNA GET A RESET PIN I TELLS YA")
    val digitalOutputProvider: DigitalOutputProvider = pi4j.digitalOutput[DigitalOutputProvider]()
    println(digitalOutputProvider)
    digitalOutputProvider.create[DigitalOutput](21)
  }

  val resource: Resource[IO, PicoResetControl] =
    pi4JResource.flatMap(pi4J => Resource.eval(resetPinFor(pi4J).map(PicoResetControl(_))))
}

class PicoResetControl(pin: DigitalOutput) {
  val reset: IO[ResetTime] =
    IO.blocking(pin.low()) >> IO.sleep(200.millis) >> IO.blocking(pin.high()) >> IO.realTimeInstant.map(ResetTime(_)).flatTap {
      instant => IO.println(s"...Pico reset at $instant")
    }
}