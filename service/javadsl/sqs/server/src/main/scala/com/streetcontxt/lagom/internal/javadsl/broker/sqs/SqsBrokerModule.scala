/*
 * Copyright (C) 2017 Quantify Labs Inc. <https://github.com/StreetContxt>
 */
package com.lightbend.lagom.internal.javadsl.broker.sqs

import com.google.inject.AbstractModule

class SqsBrokerModule extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[JavadslRegisterTopicProducers]).asEagerSingleton()
  }

}
