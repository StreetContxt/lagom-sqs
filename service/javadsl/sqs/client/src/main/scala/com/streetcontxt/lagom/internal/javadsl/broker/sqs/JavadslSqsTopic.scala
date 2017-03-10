/*
 * Copyright (C) 2017 Quantify Labs Inc. <https://github.com/StreetContxt>
 */
package com.lightbend.lagom.internal.javadsl.broker.sqs

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.lightbend.lagom.internal.broker.sqs.SqsConfig
import com.lightbend.lagom.javadsl.api.Descriptor.TopicCall
import com.lightbend.lagom.javadsl.api.{ServiceInfo, ServiceLocator}
import com.lightbend.lagom.javadsl.api.broker.Topic.TopicId
import com.lightbend.lagom.javadsl.api.broker.{Subscriber, Topic}

import scala.concurrent.ExecutionContext

/**
 * Represents an Sqs topic and allows publishing/consuming messages to/from the topic.
 */
private[lagom] class JavadslSqsTopic[Message](sqsConfig: SqsConfig, topicCall: TopicCall[Message],
                                              info: ServiceInfo, system: ActorSystem, serviceLocator: ServiceLocator)(implicit mat: Materializer, ec: ExecutionContext) extends Topic[Message] {

  override def topicId: TopicId = topicCall.topicId

  override def subscribe(): Subscriber[Message] =
    new JavadslSqsSubscriber(sqsConfig, topicCall, info, system, serviceLocator)
}
