package com.payflow.notificationservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the context wires without a running Kafka broker or SMTP server. Mail and
 * Kafka are both lazy at startup, so the beans should exist but no connection is made.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "jwt.secret=context-load-test-secret-that-is-long-enough-for-hs256",
        "notification.from=no-reply@payflow.dev"
})
@DisplayName("the application context wires the consumer and mail sender without a broker")
class NotificationServiceApplicationTests {

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private com.payflow.notificationservice.messaging.PaymentEventConsumer consumer;

    @Test
    @DisplayName("contextLoads")
    void contextLoads() {
        assertThat(mailSender).isNotNull();
        assertThat(kafkaTemplate).isNotNull();
        assertThat(consumer).isNotNull();
    }
}
