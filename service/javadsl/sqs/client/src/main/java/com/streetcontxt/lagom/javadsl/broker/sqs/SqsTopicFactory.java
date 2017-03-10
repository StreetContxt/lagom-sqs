/*
 * Copyright (C) 2017 Quantify Labs Inc. <https://github.com/StreetContxt>
 */
package com.lightbend.lagom.javadsl.broker.sqs;

import akka.actor.ActorSystem;
import akka.stream.Materializer;

import com.lightbend.lagom.internal.broker.sqs.SqsConfig;
import com.lightbend.lagom.internal.broker.sqs.SqsConfig$;
import com.lightbend.lagom.internal.javadsl.api.broker.TopicFactory;
import com.lightbend.lagom.internal.javadsl.broker.sqs.JavadslSqsTopic;
import com.lightbend.lagom.javadsl.api.Descriptor;
import com.lightbend.lagom.javadsl.api.ServiceInfo;
import com.lightbend.lagom.javadsl.api.ServiceLocator;
import com.lightbend.lagom.javadsl.api.broker.Topic;
import scala.concurrent.ExecutionContext;

import javax.inject.Inject;

/**
 * Factory for creating topics instances.
 */
public class SqsTopicFactory implements TopicFactory {
    private final ServiceInfo serviceInfo;
    private final ActorSystem system;
    private final Materializer materializer;
    private final ExecutionContext executionContext;
    private final SqsConfig config;
    private final ServiceLocator serviceLocator;

    @Inject
    public SqsTopicFactory(ServiceInfo serviceInfo, ActorSystem system, Materializer materializer,
            ExecutionContext executionContext, ServiceLocator serviceLocator) {
        this.serviceInfo = serviceInfo;
        this.system = system;
        this.materializer = materializer;
        this.executionContext = executionContext;
        this.config = SqsConfig$.MODULE$.apply(system.settings().config());
        this.serviceLocator = serviceLocator;
    }

    @Override
    public <Message> Topic<Message> create(Descriptor.TopicCall<Message> topicCall) {
        return new JavadslSqsTopic<>(config, topicCall, serviceInfo, system, serviceLocator, materializer, executionContext);
    }
}
