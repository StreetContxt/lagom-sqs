/*
 * Copyright (C) 2016-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.internal.scaladsl.broker.sqs

import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.actor.{ActorSystem, SupervisorStrategy}
import akka.pattern.BackoffSupervisor
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import com.lightbend.lagom.internal.broker.sqs.{ConsumerConfig, SqsConfig, SqsSubscriberActor}
import com.lightbend.lagom.scaladsl.api.Descriptor.TopicCall
import com.lightbend.lagom.scaladsl.api.broker.Subscriber
import com.lightbend.lagom.scaladsl.api.deser.MessageSerializer.NegotiatedDeserializer
import com.lightbend.lagom.scaladsl.api.{ServiceInfo, ServiceLocator}
import me.snov.akka.sqs.shape.SqsSourceShape

import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * A Consumer for consuming messages from Sqs using the akka.stream.alpakka.sqs API.
  */
private[lagom] class ScaladslSqsSubscriber[Message](sqsConfig: SqsConfig,
                                                    topicCall: TopicCall[Message],
                                                    info: ServiceInfo,
                                                    system: ActorSystem,
                                                    serviceLocator: ServiceLocator)
                                                   (implicit mat: Materializer, ec: ExecutionContext
                                                   ) extends Subscriber[Message] {
  import ScaladslSqsSubscriber._

  private lazy val consumerId = SqsClientIdSequenceNumber.getAndIncrement

  private def consumerConfig = ConsumerConfig(system.settings.config)

  override def withGroupId(groupId: String): Subscriber[Message] = {
    new ScaladslSqsSubscriber(sqsConfig, topicCall, info, system, serviceLocator)
    ???
  }

  private val deserializer: (String) => Message = {
    val messageSerializer = topicCall.messageSerializer
    val protocol = messageSerializer.serializerForRequest.protocol
    val negotiatedDeserializer: NegotiatedDeserializer[Message, ByteString] =
      messageSerializer.deserializer(protocol)

    { (s: String) => negotiatedDeserializer.deserialize(ByteString(s)) }
  }

  override def atMostOnceSource: Source[Message, _] = {
    val settings = SqsSubscriberActor.buildSqsSettings(sqsConfig, consumerConfig, topicCall.topicId.name)
    Source.fromGraph(SqsSourceShape(settings)).map(sm => deserializer(sm.getBody))
    ???
  }

  override def atLeastOnce(flow: Flow[Message, Done, _]): Future[Done] = {
    val streamCompleted = Promise[Done]
    val consumerProps = SqsSubscriberActor.props(
      sqsConfig,
      consumerConfig,
      deserializer,
      flow,
      topicCall.topicId.name,
      streamCompleted)

    val backoffConsumerProps = BackoffSupervisor.propsWithSupervisorStrategy(
      consumerProps, s"SqsConsumerActor$consumerId-${topicCall.topicId.name}", consumerConfig.minBackoff,
      consumerConfig.maxBackoff, consumerConfig.randomBackoffFactor, SupervisorStrategy.stoppingStrategy
    )

    system.actorOf(backoffConsumerProps, s"SqsBackoffConsumer$consumerId-${topicCall.topicId.name}")

    streamCompleted.future
  }

}

private[lagom] object ScaladslSqsSubscriber {
  private val SqsClientIdSequenceNumber = new AtomicInteger(1)
}
