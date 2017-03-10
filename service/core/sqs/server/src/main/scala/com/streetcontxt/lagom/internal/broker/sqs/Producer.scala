/*
 * Copyright (C) 2017 Quantify Labs Inc. <https://github.com/StreetContxt>
 */
package com.lightbend.lagom.internal.broker.sqs

import java.util.concurrent.{ExecutorService, Executors}

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, SupervisorStrategy}
import akka.cluster.sharding.ClusterShardingSettings
import akka.pattern.{BackoffSupervisor, pipe}
import akka.persistence.query.Offset
import akka.stream.scaladsl._
import akka.stream.{FlowShape, KillSwitch, KillSwitches, Materializer}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.client.builder.ExecutorFactory
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder
import com.amazonaws.services.sqs.model.SendMessageRequest
import com.amazonaws.{ClientConfiguration, Protocol}
import com.lightbend.lagom.internal.persistence.cluster.ClusterDistribution.EnsureActive
import com.lightbend.lagom.internal.persistence.cluster.{ClusterDistribution, ClusterDistributionSettings}
import com.lightbend.lagom.spi.persistence.{OffsetDao, OffsetStore}
import me.snov.akka.sqs.client.SqsSettings
import me.snov.akka.sqs.shape.SqsPublishSinkShape


import scala.collection.immutable
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.util.Failure

/**
  * A Producer for publishing messages in Sqs using the me.snov.akka.sqs API.
  */
private[lagom] object Producer {

  def startTaggedOffsetProducer[Message](
                                          system: ActorSystem,
                                          tags: immutable.Seq[String],
                                          sqsConfig: SqsConfig,
                                          topicId: String,
                                          eventStreamFactory: (String, Offset) => Source[(Message, Offset), _],
                                          serializer: Message => ByteString,
                                          offsetStore: OffsetStore
                                        )(implicit mat: Materializer, ec: ExecutionContext): Unit = {

    val producerConfig = ProducerConfig(system.settings.config)
    val publisherProps =
      TaggedOffsetProducerActor.props(sqsConfig, topicId, eventStreamFactory, serializer, offsetStore)

    val backoffPublisherProps = BackoffSupervisor.propsWithSupervisorStrategy(
      publisherProps, s"producer", producerConfig.minBackoff, producerConfig.maxBackoff,
      producerConfig.randomBackoffFactor, SupervisorStrategy.stoppingStrategy
    )
    val clusterShardingSettings = ClusterShardingSettings(system).withRole(producerConfig.role)

    ClusterDistribution(system).start(
      s"sqsProducer-$topicId",
      backoffPublisherProps,
      tags.toSet,
      ClusterDistributionSettings(system).copy(clusterShardingSettings = clusterShardingSettings)
    )
  }

  private class TaggedOffsetProducerActor[Message](
                                                    sqsConfig: SqsConfig,
                                                    topicId: String,
                                                    eventStreamFactory: (String, Offset) => Source[(Message, Offset), _],
                                                    serializer: Message => ByteString,
                                                    offsetStore: OffsetStore
                                                  )(implicit mat: Materializer, ec: ExecutionContext) extends Actor with ActorLogging {

    /** Switch used to terminate the on-going Sqs publishing stream when this actor fails. */
    private var shutdown: Option[KillSwitch] = None

    override def postStop(): Unit = {
      shutdown.foreach(_.shutdown())
    }

    override def receive: Receive = {
      case EnsureActive(tag) =>
        val daoFuture = offsetStore.prepare(s"topicProducer-$topicId", tag)
        daoFuture pipeTo self
        context.become(initializing(tag))
    }

    def generalHandler: Receive = {
      case Failure(e) =>
        throw e

      case EnsureActive(tagName) =>
    }

    private def initializing(tag: String): Receive = generalHandler.orElse {
      case (dao: OffsetDao) =>
        run(tag, dao)
    }

    private def active: Receive = generalHandler.orElse {
      case Done =>
        log.info("Sqs producer stream for topic {} was completed.", topicId)
        context.stop(self)
    }

    private def run(tag: String, dao: OffsetDao) = {
      val readSideSource = eventStreamFactory(tag, dao.loadedOffset)

      val (killSwitch, streamDone) = readSideSource
        .viaMat(KillSwitches.single)(Keep.right)
        .via(eventsPublisherFlow(dao))
        .toMat(Sink.ignore)(Keep.both)
        .run()

      shutdown = Some(killSwitch)
      streamDone pipeTo self
      context.become(active)
    }

    private def eventsPublisherFlow(offsetDao: OffsetDao) =
      Flow.fromGraph(GraphDSL.create(sqsFlowPublisher) { implicit builder =>
        publishFlow =>
          import GraphDSL.Implicits._
          val unzip = builder.add(Unzip[Message, Offset])
          val zip = builder.add(Zip[Any, Offset])
          val offsetCommitter = builder.add(Flow.fromFunction { e: (Any, Offset) =>
            offsetDao.saveOffset(e._2)
          })

          unzip.out0 ~> publishFlow ~> zip.in0
          unzip.out1 ~> zip.in1
          zip.out ~> offsetCommitter.in
          FlowShape(unzip.in, offsetCommitter.out)
      })

    private def sqsFlowPublisher: Flow[Message, _, _] = {
      val awsCredentials: AWSCredentials = new BasicAWSCredentials(sqsConfig.awsAccessKey, sqsConfig.awsSecretKey)
      val clientConfiguration: ClientConfiguration = new ClientConfiguration()
      clientConfiguration.setProtocol(if (sqsConfig.isSecure) Protocol.HTTPS else Protocol.HTTP)

      val credentialsProvider = new AWSCredentialsProvider {
        override def refresh(): Unit = ()

        override def getCredentials: AWSCredentials = awsCredentials
      }

      val client = AmazonSQSAsyncClientBuilder.standard()
        .withCredentials(credentialsProvider)
        .withClientConfiguration(clientConfiguration)
        .withExecutorFactory(new ExecutorFactory {
          override def newExecutor(): ExecutorService = Executors.newSingleThreadExecutor()
        })
        .withEndpointConfiguration(new EndpointConfiguration(sqsConfig.sqsEndPoint, sqsConfig.sqsSigningRegion))
        .build()

      val queueUrl = client.listQueues(topicId).getQueueUrls.asScala.find(_.endsWith(s"/$topicId")).get

      val sqsSettings: SqsSettings = SqsSettings(
        queueUrl = queueUrl,
        waitTimeSeconds = -1,
        maxNumberOfMessages = -1,
        awsCredentialsProvider = Some(credentialsProvider),
        awsClient = Some(client),
        endpoint = None,
        awsClientConfiguration = Some(clientConfiguration),
        visibilityTimeout = None)

      val flow: Flow[Message, SendMessageRequest, NotUsed] =
        Flow[Message].map { message => new SendMessageRequest().withMessageBody(serializer(message).utf8String) }

      Flow.fromGraph(
        GraphDSL.create(flow) { implicit builder =>
          flow =>
            import GraphDSL.Implicits._

            val flowFanout = builder.add(Broadcast[SendMessageRequest](2))

            val sinkShape = builder.add(SqsPublishSinkShape(sqsSettings))
            flow.out ~> flowFanout
            flowFanout ~> sinkShape.in
            FlowShape(flow.in, flowFanout.out(1))
        })
    }

  }

  private object TaggedOffsetProducerActor {
    def props[Message](
                        sqsConfig: SqsConfig,
                        topicId: String,
                        eventStreamFactory: (String, Offset) => Source[(Message, Offset), _],
                        serializer: Message => ByteString,
                        offsetStore: OffsetStore
                      )(implicit mat: Materializer, ec: ExecutionContext) =
      Props(new TaggedOffsetProducerActor[Message](sqsConfig, topicId, eventStreamFactory,
        serializer, offsetStore))
  }

}
