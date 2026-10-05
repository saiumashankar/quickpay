package com.payflow.notificationservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

/**
 * Calls auth-service by its registered name, never by address. With
 * spring-cloud-loadbalancer on the classpath the name is resolved through
 * Eureka, so two auth-service instances would be load balanced and adding a
 * third would need no configuration change here.
 *
 * There is no user token to forward. The consumer acts on behalf of nobody:
 * a token on the Kafka topic would be readable by every consumer group and
 * would be a bearer credential for the whole system, so the shared internal
 * token is used instead.
 */
@FeignClient(name = "auth-service")
public interface AuthServiceClient {

    @GetMapping("/api/internal/users/{ownerId}/notification-preferences")
    @JsonIgnoreProperties(ignoreUnknown = true)
    NotificationPreferences getNotificationPreferences(
            @PathVariable("ownerId") UUID ownerId,
            @RequestHeader("X-Internal-Token") String internalToken);
}
