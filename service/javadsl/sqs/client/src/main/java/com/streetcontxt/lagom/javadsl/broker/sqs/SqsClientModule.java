/*
 * Copyright (C) 2017 Quantify Labs Inc. <https://github.com/StreetContxt>
 */
package com.lightbend.lagom.javadsl.broker.sqs;

import com.google.inject.AbstractModule;
import com.lightbend.lagom.internal.javadsl.api.broker.TopicFactory;

public class SqsClientModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(TopicFactory.class).to(SqsTopicFactory.class);
    }
}
