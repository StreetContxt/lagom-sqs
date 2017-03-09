/*
 * Copyright (C) 2016-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.internal.javadsl.broker.sqs

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletionStage

import akka.Done
import akka.actor.{ActorSystem, SupervisorStrategy}
import akka.japi.function.{Function => AkkaFunction}
import akka.pattern.BackoffSupervisor
import akka.stream.Materializer
import akka.stream.javadsl.{Flow, Source}
import akka.util.ByteString
import com.lightbend.lagom.internal.broker.sqs._
import com.lightbend.lagom.javadsl.api.Descriptor.TopicCall
import com.lightbend.lagom.javadsl.api.broker.Subscriber
import com.lightbend.lagom.javadsl.api.deser.MessageSerializer.NegotiatedDeserializer
import com.lightbend.lagom.javadsl.api.{ServiceInfo, ServiceLocator}
import me.snov.akka.sqs.shape.SqsSourceShape
import com.amazonaws.services.sqs.model.{Message => SqsMessage}

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Promise}

/**
  * A Consumer for consuming messages from Sqs using the akka.stream.alpakka.sqs API.
  */
private[lagom] class JavadslSqsSubscriber[Message](sqsConfig: SqsConfig, topicCall: TopicCall[Message],
                                                   info: ServiceInfo, system: ActorSystem,
                                                   serviceLocator: ServiceLocator)(implicit mat: Materializer, ec: ExecutionContext) extends Subscriber[Message] {
  import JavadslSqsSubscriber._

  private lazy val consumerId = SqsClientIdSequenceNumber.getAndIncrement

  private def consumerConfig = ConsumerConfig(system.settings.config)

  @throws(classOf[IllegalArgumentException])
  override def withGroupId(groupId: String): Subscriber[Message] = {
    new JavadslSqsSubscriber(sqsConfig, topicCall, info, system, serviceLocator)
    ???
  }

  private val deserializer: String => Message = {
    val messageSerializer = topicCall.messageSerializer
    val protocol = messageSerializer.serializerForRequest.protocol
    val negotiatedDeserializer: NegotiatedDeserializer[Message, ByteString] =
      messageSerializer.deserializer(protocol)

    { (s: String) => negotiatedDeserializer.deserialize(ByteString(s)) }
  }

  override def atMostOnceSource: Source[Message, _] = {
    val settings = SqsSubscriberActor.buildSqsSettings(sqsConfig, consumerConfig, topicCall.topicId.value)
    Source.fromGraph(SqsSourceShape(settings))
      .map(new AkkaFunction[SqsMessage, Message] {
        override def apply(a: SqsMessage): Message = deserializer(a.getBody)
      })
    ???
  }

  override def atLeastOnce(flow: Flow[Message, Done, _]): CompletionStage[Done] = {
    val streamCompleted = Promise[Done]
    val consumerProps = SqsSubscriberActor.props(
      sqsConfig,
      consumerConfig,
      deserializer,
      flow.asScala,
      topicCall.topicId.value,
      streamCompleted)

    val backoffConsumerProps = BackoffSupervisor.propsWithSupervisorStrategy(
      consumerProps, s"SqsConsumerActor$consumerId-${topicCall.topicId().value}", consumerConfig.minBackoff,
      consumerConfig.maxBackoff, consumerConfig.randomBackoffFactor, SupervisorStrategy.stoppingStrategy
    )

    system.actorOf(backoffConsumerProps, s"SqsBackoffConsumer$consumerId-${topicCall.topicId().value}")

    streamCompleted.future.toJava
  }
}

private[lagom] object JavadslSqsSubscriber {
  private val SqsClientIdSequenceNumber = new AtomicInteger(1)
}
