/*
 * Copyright (C) 2016-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.internal.javadsl.broker.sqs

import com.google.inject.AbstractModule

class SqsBrokerModule extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[JavadslRegisterTopicProducers]).asEagerSingleton()
  }

}
