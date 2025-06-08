package com.madgag.micropython.logiccapture.worker

import com.madgag.micropython.logiccapture.worker.YamlConfigFile.Error.{InvalidYaml, MissingFile}
import com.madgag.micropython.logiccapture.worker.aws.Fail
import org.virtuslab.yaml.*
import os.{Path, SubPath}

object YamlConfigFile {
  sealed trait Error {
    def causeDescription: String
    def asFail: Fail = Fail(this.getClass.getSimpleName, causeDescription)
  }
  object Error {
    case class MissingFile(subPath: SubPath) extends Error {
      override def causeDescription = s"Yaml Config file does not exist: $subPath"
    }

    case class InvalidYaml(yamlError: YamlError) extends Error {
      override def causeDescription: String = yamlError.msg
    }
  }
  
  def read[T : YamlCodec](workRoot: Path, configFileSubPath: SubPath): Either[YamlConfigFile.Error, T] = {
    val configFile = workRoot / configFileSubPath
    if (!os.exists(configFile) || !os.isFile(configFile)) Left(MissingFile(configFileSubPath)) else {
      val captureConfig: Either[YamlError, T] = os.read(configFile).as[T]
      println(captureConfig)
      captureConfig.left.map(InvalidYaml(_))
    }
  }
}