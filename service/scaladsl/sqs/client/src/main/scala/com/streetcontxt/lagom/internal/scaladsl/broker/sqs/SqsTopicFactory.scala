/*
 * Copyright (C) 2017 Quantify Labs Inc. <https://github.com/StreetContxt>
 */
package com.lightbend.lagom.internal.scaladsl.broker.sqs

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.lightbend.lagom.internal.broker.sqs.SqsConfig
import com.lightbend.lagom.internal.scaladsl.api.broker.TopicFactory
import com.lightbend.lagom.scaladsl.api.Descriptor.TopicCall
import com.lightbend.lagom.scaladsl.api.{ServiceInfo, ServiceLocator}
import com.lightbend.lagom.scaladsl.api.broker.Topic

import scala.concurrent.ExecutionContext

/**
 * Factory for creating topics instances.
 */
private[lagom] class SqsTopicFactory(serviceInfo: ServiceInfo, system: ActorSystem, serviceLocator: ServiceLocator)(implicit materializer: Materializer, executionContext: ExecutionContext) extends TopicFactory {

  private val config = SqsConfig(system.settings.config)

  def create[Message](topicCall: TopicCall[Message]): Topic[Message] = {
    new ScaladslSqsTopic(config, topicCall, serviceInfo, system, serviceLocator)
  }
}
