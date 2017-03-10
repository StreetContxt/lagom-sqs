/*
 * Copyright (C) 2017 Quantify Labs Inc. <https://github.com/StreetContxt>
 */
package com.lightbend.lagom.scaladsl.broker.sqs

import com.lightbend.lagom.internal.scaladsl.broker.sqs.ScaladslRegisterTopicProducers
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.server.LagomServer
import com.lightbend.lagom.spi.persistence.OffsetStore

/**
 * Components for including Sqs into a Lagom application.
 *
 * Extending this trait will automatically start all topic producers.
 */
trait LagomSqsComponents extends LagomSqsClientComponents {
  def lagomServer: LagomServer
  def offsetStore: OffsetStore
  def serviceLocator: ServiceLocator

  override def topicPublisherName: Option[String] = super.topicPublisherName match {
    case Some(other) =>
      sys.error(s"Cannot provide the sqs topic factory " +
        s"as the default topic publisher since a default topic publisher " +
        s"has already been mixed into this cake: $other")
    case None => Some("sqs")
  }

  // Eagerly start topic producers
  new ScaladslRegisterTopicProducers(lagomServer, topicFactory, serviceInfo, actorSystem,
    offsetStore, serviceLocator)(executionContext, materializer)
}
