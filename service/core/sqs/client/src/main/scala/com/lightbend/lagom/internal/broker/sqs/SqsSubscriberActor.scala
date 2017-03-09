/*
 * Copyright (C) 2016-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.internal.broker.sqs

import java.util.concurrent.{ExecutorService, Executors}

import akka.Done
import akka.actor.{Actor, ActorLogging, Props, Status}
import akka.pattern.pipe
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, Sink, Source, Unzip, Zip, Broadcast}
import akka.stream._
import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.client.builder.ExecutorFactory
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder
import com.amazonaws.services.sqs.model.{Message => SqsMessage}
import com.amazonaws.{ClientConfiguration, Protocol}
import me.snov.akka.sqs.Ack
import me.snov.akka.sqs.client.SqsSettings
import me.snov.akka.sqs.shape.{SqsAckSinkShape, SqsSourceShape}

import scala.concurrent.{ExecutionContext, Promise}
import scala.collection.JavaConverters._

private[lagom] class SqsSubscriberActor[Message](sqsConfig: SqsConfig,
                                                 consumerConfig: ConsumerConfig,
                                                 deserializer: String => Message,
                                                 flow: Flow[Message, Done, _],
                                                 topicName: String,
                                                 streamCompleted: Promise[Done])
                                                (implicit mat: Materializer, ec: ExecutionContext
                                                ) extends Actor with ActorLogging {

  /** Switch used to terminate the on-going SQS publishing stream when this actor fails. */
  private var shutdown: Option[KillSwitch] = None

  override def preStart(): Unit = {
    run()
  }

  override def postStop(): Unit = {
    shutdown.foreach(_.shutdown())
  }

  private def running: Receive = {
    case Status.Failure(e) =>
      log.error("Topic subscription interrupted due to failure", e)
      throw e

    case Done =>
      log.info("Sqs subscriber stream for queue {} at endpoint {} was completed.", topicName, sqsConfig.sqsEndPoint)
      streamCompleted.success(Done)
      context.stop(self)
  }

  override def receive: PartialFunction[Any, Nothing] = PartialFunction.empty

  private def run() = {
    val (killSwitch, streamDone) =
      atLeastOnce(flow)
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.ignore)(Keep.both)
        .run()

    shutdown = Some(killSwitch)
    streamDone pipeTo self
    context.become(running)
  }

  private def atLeastOnce(flow: Flow[Message, Done, _]): Source[Done, _] = {

    val settings: SqsSettings = SqsSubscriberActor.buildSqsSettings(sqsConfig, consumerConfig, topicName)

    val committOffsetFlow: Flow[(SqsMessage, Message), Done, Any] =
      Flow.fromGraph(GraphDSL.create(flow) { implicit builder =>
        flow =>
          import GraphDSL.Implicits._
          val unzip = builder.add(Unzip[SqsMessage, Message])
          val zip = builder.add(Zip[SqsMessage, Done])
          val committerFanout = builder.add(Broadcast[(SqsMessage, Ack)](2))
          val committer = {
            val commitFlow = Flow[(SqsMessage, Done)]
              .map { case (msg, Done) => (msg, Ack()) }
            builder.add(commitFlow)
          }
          val outputter = {
            val outputFlow = Flow[(SqsMessage, Ack)]
              .map { _ => Done.getInstance() }
            builder.add(outputFlow)
          }

          val ackSink = SqsAckSinkShape(settings)

          unzip.out0 ~> zip.in0
          unzip.out1 ~> flow ~> zip.in1
          zip.out ~> committer.in
          committer.out ~> committerFanout.in
          committerFanout ~> ackSink
          committerFanout ~> outputter.in

          FlowShape(unzip.in, outputter.out)
      })

    Source.fromGraph(SqsSourceShape(settings))
      .map(sm => (sm, deserializer(sm.getBody)))
      .via(committOffsetFlow)
  }
}

object SqsSubscriberActor {
  def buildSqsSettings(sqsConfig: SqsConfig, consumerConfig: ConsumerConfig, topicName: String) = {
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

    val queueUrl = client.listQueues(topicName).getQueueUrls.asScala.find(_.endsWith(s"/$topicName")).get

    SqsSettings(
      queueUrl = queueUrl,
      waitTimeSeconds = consumerConfig.longPollingDuration.toSeconds.toInt,
      maxNumberOfMessages = consumerConfig.maxBatchSize,
      awsCredentialsProvider = Some(credentialsProvider),
      awsClient = Some(client),
      endpoint = None,
      awsClientConfiguration = Some(clientConfiguration),
      visibilityTimeout = None)
  }


  def props[Message](sqsConfig: SqsConfig,
                     consumerConfig: ConsumerConfig,
                     mapper: String => Message,
                     flow: Flow[Message, Done, _],
                     topicName: String,
                     streamCompleted: Promise[Done]
                    )(implicit mat: Materializer, ec: ExecutionContext) =
    Props(new SqsSubscriberActor[Message](sqsConfig, consumerConfig, mapper, flow, topicName, streamCompleted))
}
