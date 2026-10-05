package com.payflow.payment.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {
    private final KafkaAdmin kafkaAdmin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health() {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            var brokers = adminClient.describeCluster().nodes().get(5, TimeUnit.SECONDS);
            if (brokers.isEmpty()) {
                return Health.down().withDetail("message", "Kafka returned no brokers").build();
            }
            return Health.up().withDetail("brokerCount", brokers.size()).build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }
}
