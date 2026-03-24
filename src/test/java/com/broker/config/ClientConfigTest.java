package com.broker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ClientConfigTest {

    @Test
    void shouldExposeRestClientBuilderBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(ClientConfig.class)
                .run(context -> assertInstanceOf(RestClient.Builder.class, context.getBean(RestClient.Builder.class)));
    }
}
