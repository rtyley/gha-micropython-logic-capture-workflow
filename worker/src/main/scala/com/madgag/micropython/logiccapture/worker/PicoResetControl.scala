package com.madgag.micropython.logiccapture.worker

import cats.effect.{IO, Resource}
import com.pi4j.Pi4J
import com.pi4j.boardinfo.util.BoardInfoHelper
import com.pi4j.context.Context
import com.pi4j.io.gpio.digital.{DigitalOutput, DigitalOutputProvider}
import com.pi4j.plugin.gpiod.provider.gpio.digital.{GpioDDigitalInputProvider, GpioDDigitalOutput, GpioDDigitalOutputProvider}

import java.util.concurrent.CompletableFuture
import scala.jdk.FutureConverters.*

object PicoResetControl {

  val pi4JResource: Resource[IO, Context] = Resource.make {
    IO.blocking {
      val digitalInputProvider = GpioDDigitalInputProvider.newInstance()
      val digitalOutputProvider = GpioDDigitalOutputProvider.newInstance()

      val pi4j: Context = Pi4J.newContextBuilder()
        .add(digitalInputProvider, digitalOutputProvider)
        .setGpioChipName("gpiochip0").build() // blocking IO

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

  def resetPinFor(pi4j: Context): IO[DigitalOutput] =
    IO.blocking(pi4j.digitalOutput[DigitalOutputProvider]().create(21))
}
