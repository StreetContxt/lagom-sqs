/*
 * Copyright (C) 2017 Quantify Labs Inc. <https://github.com/StreetContxt>
 */
package com.lightbend.lagom.internal.javadsl.broker.sqs

import java.net.URI

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import org.slf4j.LoggerFactory
import com.lightbend.lagom.javadsl.api.{ServiceInfo, ServiceLocator}
import akka.stream.Materializer
import javax.inject.Inject

import akka.actor.ActorSystem
import akka.persistence.query.Offset
import akka.stream.scaladsl.Source
import com.lightbend.lagom.internal.broker.TaggedOffsetTopicProducer
import com.lightbend.lagom.internal.broker.sqs.{Producer, SqsConfig}
import com.lightbend.lagom.internal.javadsl.api.MethodTopicHolder
import com.lightbend.lagom.internal.javadsl.api.broker.TopicFactory
import com.lightbend.lagom.internal.javadsl.persistence.OffsetAdapter
import com.lightbend.lagom.internal.javadsl.server.ResolvedServices
import com.lightbend.lagom.javadsl.api.Descriptor.TopicCall
import com.lightbend.lagom.spi.persistence.OffsetStore

import scala.collection.immutable
import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._

class JavadslRegisterTopicProducers @Inject() (resolvedServices: ResolvedServices,
                                               topicFactory: TopicFactory,
                                               info: ServiceInfo,
                                               actorSystem: ActorSystem,
                                               offsetStore: OffsetStore,
                                               serviceLocator: ServiceLocator)
                                              (implicit ec: ExecutionContext, mat: Materializer) {

  private val log = LoggerFactory.getLogger(classOf[JavadslRegisterTopicProducers])
  private val sqsConfig = SqsConfig(actorSystem.settings.config)

  // Goes through the services' descriptors and publishes the streams registered in
  // each of the service's topic method implementation.
  for {
    service <- resolvedServices.services
    tc <- service.descriptor.topicCalls().asScala
    topicCall = tc.asInstanceOf[TopicCall[AnyRef]]
  } {
    topicCall.topicHolder match {
      case holder: MethodTopicHolder =>
        val topicProducer = holder.create(service.service)
        val topicId = topicCall.topicId

        topicFactory.create(topicCall) match {
          case topicImpl: JavadslSqsTopic[AnyRef] =>

            topicProducer match {
              case tagged: TaggedOffsetTopicProducer[AnyRef, _] =>

                val tags = tagged.tags.asScala.to[immutable.Seq]

                val eventStreamFactory: (String, Offset) => Source[(AnyRef, Offset), _] = { (tag, offset) =>
                  tags.find(_.tag == tag) match {
                    case Some(aggregateTag) => tagged.readSideStream(
                      aggregateTag,
                      OffsetAdapter.offsetToDslOffset(offset)
                    ).asScala.map { pair =>
                        pair.first -> OffsetAdapter.dslOffsetToOffset(pair.second)
                      }
                    case None => throw new RuntimeException("Unknown tag: " + tag)
                  }
                }
                val serializer = topicCall.messageSerializer().serializerForRequest()
                Producer.startTaggedOffsetProducer(
                  actorSystem,
                  tags.map(_.tag),
                  sqsConfig,
                  topicId.value(),
                  eventStreamFactory,
                  serializer.serialize,
                  offsetStore)
              case other => log.warn {
                s"Unknown topic producer ${other.getClass.getName}. " +
                  s"This will likely result in no events published to topic ${topicId.value} by service ${info.serviceName}."
              }
            }

          case otherTopicImpl => log.warn {
            s"Expected Topic type ${classOf[JavadslSqsTopic[_]].getName}, but found incompatible type ${otherTopicImpl
              .getClass.getName}." +
              s"This will likely result in no events published to topic ${topicId.value} by service ${info.serviceName}."
          }
        }

      case other =>
        log.error {
          s"Cannot plug publisher source for topic ${topicCall.topicId}. " +
            s"Reason was that it was expected a topicHolder of type ${classOf[MethodTopicHolder]}, " +
            s"but ${other.getClass} was found instead."
        }
    }
  }

  private def locateService(name: String): Future[Option[URI]] =
    serviceLocator.locate(name).toScala.map(_.asScala)

}
