package com.payflow.notificationservice.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.notificationservice.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls a merchant's own HTTPS endpoint when money moves, with the payload
 * signed so they can tell the call really came from Payflow.
 *
 * The signature covers the timestamp as well as the body. Signing only the body
 * would let somebody who intercepted one call replay it forever, because the
 * bytes would still verify; including a timestamp means a captured call stops
 * being acceptable after the window a merchant is told to accept.
 *
 * The merchant computes the same HMAC over "timestamp.body" with the shared
 * secret and compares it in constant time. The algorithm and version are sent as
 * headers rather than being implied, so a secret can be rotated later without
 * the merchant having to guess which scheme a given call used.
 *
 * This is an outbound call to a third party from inside the Kafka consumer. It
 * is therefore deliberately given a short timeout: a merchant who is slow must
 * not hold up every other merchant's notifications behind them on the same
 * partition.
 */
@Component
public class WebhookNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationProvider.class);

    static final String SIGNATURE_HEADER = "X-Payflow-Signature";
    static final String TIMESTAMP_HEADER = "X-Payflow-Timestamp";
    static final String EVENT_HEADER = "X-Payflow-Event";

    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public WebhookNotificationProvider(NotificationProperties properties, ObjectMapper objectMapper,
                                       RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public NotificationMessage.Kind kind() {
        return NotificationMessage.Kind.WEBHOOK;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public boolean isConfigured() {
        NotificationProperties.Webhook webhook = properties.webhook();
        return webhook != null
                && webhook.secret() != null && !webhook.secret().isBlank();
    }

    @Override
    public void send(NotificationMessage message) throws NotificationDeliveryException {
        if (!isConfigured()) {
            throw NotificationDeliveryException.permanent("Webhook signing secret is not configured");
        }

        String body = toJson(message);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = sign(properties.webhook().secret(), timestamp + "." + body);

        try {
            restClient.post()
                    .uri(message.destination())
                    .header(SIGNATURE_HEADER, signature)
                    .header(TIMESTAMP_HEADER, timestamp)
                    .header(EVENT_HEADER, "payment." + message.party().name().toLowerCase())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpServerErrorException exception) {
            // 5xx is the merchant's problem to fix and may be fixed shortly, so
            // it is worth another attempt.
            log.warn("Webhook {} returned {} for payment {}",
                    message.destination(), exception.getStatusCode(), message.paymentId());
            throw NotificationDeliveryException.retryable(
                    "Webhook " + message.destination() + " returned " + exception.getStatusCode(), exception);
        } catch (RestClientResponseException exception) {
            // 4xx will be answered the same way every time. Retrying it would
            // hold the partition for the whole backoff window and delay every
            // later event behind it, to arrive at the same rejection.
            log.warn("Webhook {} rejected the call with {} for payment {}",
                    message.destination(), exception.getStatusCode(), message.paymentId());
            throw NotificationDeliveryException.permanent(
                    "Webhook " + message.destination() + " rejected the call with "
                            + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            log.warn("Webhook {} could not be reached for payment {}: {}",
                    message.destination(), message.paymentId(), exception.getMessage());
            throw NotificationDeliveryException.retryable(
                    "Could not reach webhook " + message.destination(), exception);
        }
    }

    /**
     * HMAC-SHA256 over "timestamp.body", hex encoded.
     *
     * Exposed for the tests that assert a merchant verifying our signature
     * would accept it, which is the part of this that is easy to get subtly
     * wrong and impossible to notice from the sending side.
     */
    static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Could not sign webhook payload", exception);
        }
    }

    private String toJson(NotificationMessage message) throws NotificationDeliveryException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "payment." + message.party().name().toLowerCase());
        payload.put("paymentId", message.paymentId());
        payload.put("party", message.party().name());
        payload.put("amount", message.amount());
        payload.put("currency", message.currency());
        payload.put("subject", message.subject());
        payload.put("body", message.body());
        payload.put("occurredAt", Instant.now().toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            // Serialising a map of primitives cannot fail in practice. Treated as
            // permanent because retrying would produce the same failure.
            throw NotificationDeliveryException.permanent("Could not serialise webhook payload", exception);
        }
    }
}
