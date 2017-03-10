/*
 * Copyright (C) 2017 Quantify Labs Inc. <https://github.com/StreetContxt>
 */
package com.lightbend.lagom.internal.scaladsl.broker.sqs

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.lightbend.lagom.internal.broker.sqs.SqsConfig
import com.lightbend.lagom.scaladsl.api.Descriptor.TopicCall
import com.lightbend.lagom.scaladsl.api.{ServiceInfo, ServiceLocator}
import com.lightbend.lagom.scaladsl.api.broker.{Subscriber, Topic}
import com.lightbend.lagom.scaladsl.api.broker.Topic.TopicId

import scala.concurrent.ExecutionContext

private[lagom] class ScaladslSqsTopic[Message](sqsConfig: SqsConfig, topicCall: TopicCall[Message],
                                               info: ServiceInfo, system: ActorSystem, serviceLocator: ServiceLocator)(implicit mat: Materializer, ec: ExecutionContext) extends Topic[Message] {

  override def topicId: TopicId = topicCall.topicId

  override def subscribe: Subscriber[Message] =
    new ScaladslSqsSubscriber(sqsConfig, topicCall, info, system, serviceLocator)
}
