/*
 * Copyright (C) 2017 Quantify Labs Inc. <https://github.com/StreetContxt>
 */
package com.lightbend.lagom.internal.broker.sqs

import java.util.concurrent.TimeUnit

import com.typesafe.config.Config

import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.control.NoStackTrace

sealed trait SqsConfig {
  def sqsEndPoint: String

  def sqsSigningRegion: String

  def awsAccessKey: String

  def awsSecretKey: String

  def proxyHost: Option[String]

  def proxyPort: Option[Integer]

  def isSecure: Boolean
}

object SqsConfig {
  def apply(conf: Config): SqsConfig =
    new SqsConfigImpl(conf.getConfig("lagom.broker.sqs"))

  private final class SqsConfigImpl(conf: Config) extends SqsConfig {
    override val sqsEndPoint: String = conf.getString("sqs-end-point")
    override val sqsSigningRegion: String = conf.getString("sqs-signing-region")
    override val awsAccessKey: String = conf.getString("aws-access-key")
    override val awsSecretKey: String = conf.getString("aws-secret-key")
    override val proxyHost: Option[String] = Some(conf.getString("proxy-host")).filter(_.nonEmpty)
    override val proxyPort: Option[Integer] = Some(conf.getString("proxy-port"))
      .filter(_.nonEmpty)
      .map(_.toInt)
    override val isSecure: Boolean = conf.getBoolean("is-secure")
  }

}

sealed trait ClientConfig {
  def minBackoff: FiniteDuration

  def maxBackoff: FiniteDuration

  def randomBackoffFactor: Double
}

object ClientConfig {

  private[sqs] class ClientConfigImpl(conf: Config) extends ClientConfig {
    val minBackoff = conf.getDuration("failure-exponential-backoff.min", TimeUnit.MILLISECONDS).millis
    val maxBackoff = conf.getDuration("failure-exponential-backoff.max", TimeUnit.MILLISECONDS).millis
    val randomBackoffFactor = conf.getDouble("failure-exponential-backoff.random-factor")
  }

}

sealed trait ProducerConfig extends ClientConfig {
  def role: Option[String]
}

object ProducerConfig {
  def apply(conf: Config): ProducerConfig =
    new ProducerConfigImpl(conf.getConfig("lagom.broker.sqs.client.producer"))

  private class ProducerConfigImpl(conf: Config)
    extends ClientConfig.ClientConfigImpl(conf) with ProducerConfig {
    val role = conf.getString("role") match {
      case "" => None
      case other => Some(other)
    }
  }

}

sealed trait ConsumerConfig extends ClientConfig {
  def batchingSize: Int

  def maxBatchSize: Int

  def longPollingDuration: FiniteDuration
  def batchingInterval: FiniteDuration

}

object ConsumerConfig {
  def apply(conf: Config): ConsumerConfig =
    new ConsumerConfigImpl(conf.getConfig("lagom.broker.sqs.client.consumer"))

  private final class ConsumerConfigImpl(conf: Config)
    extends ClientConfig.ClientConfigImpl(conf)
      with ConsumerConfig {
    override val batchingSize: Int = conf.getInt("batching-size")
    override val maxBatchSize: Int = conf.getInt("max-batch-size")
    override val longPollingDuration: FiniteDuration = {
      val interval = conf.getDuration("long-polling-duration")
      FiniteDuration(interval.toMillis, TimeUnit.MILLISECONDS)
    }
    override val batchingInterval: FiniteDuration = {
      val interval = conf.getDuration("batching-interval")
      FiniteDuration(interval.toMillis, TimeUnit.MILLISECONDS)
    }
  }

}
