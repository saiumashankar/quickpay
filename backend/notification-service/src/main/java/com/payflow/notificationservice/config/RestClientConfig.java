package com.payflow.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the outbound providers.
 *
 * The timeouts are short and deliberate. These calls are made from inside a
 * Kafka consumer, so a provider that hangs holds the partition and delays every
 * later payment event behind it. Failing fast lets the error handler retry with
 * backoff, which is what actually recovers from a slow endpoint; blocking for a
 * minute per attempt just moves the backlog.
 *
 * Declared explicitly rather than relying on an auto-configured builder: a
 * builder with no timeout set has no timeout at all, and that default is only
 * discovered by watching a consumer stall in production.
 */
@Configuration
public class RestClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    @Bean
    RestClient.Builder notificationRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return RestClient.builder().requestFactory(factory);
    }
}
