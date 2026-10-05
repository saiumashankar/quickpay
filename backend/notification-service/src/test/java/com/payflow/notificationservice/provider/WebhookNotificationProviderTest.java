package com.payflow.notificationservice.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.notificationservice.config.NotificationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The signing half of the webhook is the part that can be wrong in a way that
 * never shows up on the sending side: we would happily POST a signature that no
 * merchant could reproduce. These tests verify it the way a merchant would, by
 * recomputing the HMAC from the shared secret, rather than by asserting on a
 * value this class also produced.
 */
@DisplayName("the webhook carries a signature a merchant can actually verify")
class WebhookNotificationProviderTest {

    private static final String SECRET = "whsec_test_secret";
    private static final String ENDPOINT = "https://merchant.example.com/payflow/events";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private WebhookNotificationProvider provider;
    private MockRestServiceServer server;

    /**
     * The last request the server saw. Captured from the responder because the
     * convenience accessor for it was removed in Spring 7, and reading the
     * request the provider actually sent is the whole point of these tests.
     */
    private ClientHttpRequest lastRequest;
    private String lastBody;

    private void setUp(String secret) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new WebhookNotificationProvider(
                new NotificationProperties("no-reply@payflow.dev", null, new NotificationProperties.Webhook(secret)),
                objectMapper, builder);
        lastRequest = null;
        lastBody = null;
    }

    /** Accepts the call and keeps a copy of what arrived. */
    private ResponseCreator capturingSuccess() {
        return request -> {
            lastRequest = request;
            // The mock request buffers the body, so reading it back is how the
            // bytes the provider actually sent are recovered.
            lastBody = ((java.io.ByteArrayOutputStream) request.getBody()).toString(StandardCharsets.UTF_8);
            return withSuccess("{}", MediaType.APPLICATION_JSON).createResponse(request);
        };
    }

    private NotificationMessage message() {
        return NotificationMessage.webhook(UUID.randomUUID(), NotificationMessage.Party.SENDER,
                new BigDecimal("500.00"), "USD", "Payment successful", "You sent money to @merchant.", ENDPOINT);
    }

    /** Recomputes the signature the way a merchant's server would. */
    private String expectedSignature(String timestamp, String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("the signature is an HMAC of the timestamp and the exact bytes sent")
    void signatureCoversTimestampAndBody() throws Exception {
        setUp(SECRET);
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(capturingSuccess());

        provider.send(message());

        String sentTimestamp = lastRequest.getHeaders().getFirst(WebhookNotificationProvider.TIMESTAMP_HEADER);
        String sentSignature = lastRequest.getHeaders().getFirst(WebhookNotificationProvider.SIGNATURE_HEADER);

        // The merchant recomputes this from the secret, the timestamp header and
        // the body it received. Anything else and no merchant could ever verify us.
        assertThat(sentSignature).isEqualTo(expectedSignature(sentTimestamp, lastBody, SECRET));
    }

    @Test
    @DisplayName("the same body at a different timestamp signs differently, so a captured call cannot be replayed")
    void timestampIsPartOfTheSignature() {
        String first = WebhookNotificationProvider.sign(SECRET, "1700000000.body");
        String later = WebhookNotificationProvider.sign(SECRET, "1700000060.body");

        assertThat(first).isNotEqualTo(later);
    }

    @Test
    @DisplayName("a body altered in transit no longer matches the signature")
    void tamperedBodyDoesNotVerify() throws Exception {
        String original = "{\"amount\":500}";
        String tampered = "{\"amount\":5000}";
        String signature = WebhookNotificationProvider.sign(SECRET, "1700000000." + original);

        assertThat(expectedSignature("1700000000", tampered, SECRET)).isNotEqualTo(signature);
    }

    @Test
    @DisplayName("a merchant using a different secret cannot forge a signature we would accept")
    void wrongSecretDoesNotVerify() throws Exception {
        String signature = WebhookNotificationProvider.sign(SECRET, "1700000000.body");

        assertThat(expectedSignature("1700000000", "body", "someone-elses-secret")).isNotEqualTo(signature);
    }

    @Test
    @DisplayName("the timestamp and event headers are sent so the merchant knows the scheme")
    void identifyingHeadersAreSent() throws Exception {
        setUp(SECRET);
        server.expect(requestTo(ENDPOINT)).andRespond(capturingSuccess());

        provider.send(message());

        assertThat(lastRequest.getHeaders().getFirst(WebhookNotificationProvider.TIMESTAMP_HEADER)).isNotBlank();
        assertThat(lastRequest.getHeaders().getFirst(WebhookNotificationProvider.EVENT_HEADER))
                .isEqualTo("payment.sender");
        assertThat(lastRequest.getHeaders().getFirst("Content-Type")).contains("application/json");
    }

    @Test
    @DisplayName("the payload names the payment and the amount")
    void payloadCarriesThePayment() throws Exception {
        setUp(SECRET);
        server.expect(requestTo(ENDPOINT)).andRespond(capturingSuccess());

        provider.send(message());

        assertThat(lastBody).contains("\"event\":\"payment.sender\"");
        assertThat(lastBody).contains("500.00");
        assertThat(lastBody).contains("USD");
    }

    @Test
    @DisplayName("a 5xx from the merchant is retried: their outage may be short")
    void serverErrorIsRetryable() {
        setUp(SECRET);
        server.expect(requestTo(ENDPOINT)).andRespond(withServerError());

        assertThatThrownBy(() -> provider.send(message()))
                .isInstanceOf(NotificationDeliveryException.class)
                .satisfies(thrown -> assertThat(((NotificationDeliveryException) thrown).isRetryable()).isTrue());
    }

    @Test
    @DisplayName("a 4xx from the merchant is permanent: retrying cannot change the answer")
    void clientErrorIsPermanent() {
        setUp(SECRET);
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> provider.send(message()))
                .isInstanceOf(NotificationDeliveryException.class)
                .satisfies(thrown -> assertThat(((NotificationDeliveryException) thrown).isRetryable()).isFalse());
    }

    @Test
    @DisplayName("with no secret configured the provider reports itself off rather than sending unsigned")
    void unconfiguredProviderIsDisabled() {
        setUp("  ");

        assertThat(provider.isConfigured()).isFalse();
        assertThatThrownBy(() -> provider.send(message()))
                .isInstanceOf(NotificationDeliveryException.class)
                .satisfies(thrown -> assertThat(((NotificationDeliveryException) thrown).isRetryable()).isFalse());
    }
}
