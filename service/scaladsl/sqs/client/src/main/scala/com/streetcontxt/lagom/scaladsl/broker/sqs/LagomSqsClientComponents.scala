/*
 * Copyright (C) 2017 Quantify Labs Inc. <https://github.com/StreetContxt>
 */
package com.lightbend.lagom.scaladsl.broker.sqs

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.lightbend.lagom.internal.scaladsl.api.broker.{ TopicFactory, TopicFactoryProvider }
import com.lightbend.lagom.internal.scaladsl.broker.sqs.SqsTopicFactory
import com.lightbend.lagom.scaladsl.api.{ ServiceInfo, ServiceLocator }

import scala.concurrent.ExecutionContext

trait LagomSqsClientComponents extends TopicFactoryProvider {
  def serviceInfo: ServiceInfo
  def actorSystem: ActorSystem
  def materializer: Materializer
  def executionContext: ExecutionContext
  def serviceLocator: ServiceLocator

  lazy val topicFactory: TopicFactory = new SqsTopicFactory(serviceInfo, actorSystem, serviceLocator)(materializer, executionContext)
  override def optionalTopicFactory: Option[TopicFactory] = Some(topicFactory)
}
