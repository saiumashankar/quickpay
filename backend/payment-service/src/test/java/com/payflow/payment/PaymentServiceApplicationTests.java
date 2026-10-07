package com.payflow.payment;

import com.payflow.payment.dto.CreatePaymentRequest;
import com.payflow.payment.dto.GatewayUserContext;
import com.payflow.payment.dto.PaymentDirection;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.entity.Payment;
import com.payflow.payment.entity.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:context;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "eureka.client.enabled=false",
        "feign.client.enabled=false",
        "payment.outbox.enabled=false",
        "jwt.secret=context-load-test-secret-that-is-long-enough-for-hs256"
})
@DisplayName("the application context wires JPA, Redis, Kafka and the handle lookup without a running broker")
class PaymentServiceApplicationTests {

    @Autowired
    private com.payflow.payment.repository.PaymentRepository paymentRepository;

    @Autowired
    private com.payflow.payment.repository.WalletRepository walletRepository;

    @Test
    @DisplayName("contextLoads")
    void contextLoads() {
        org.assertj.core.api.Assertions.assertThat(paymentRepository).isNotNull();
        org.assertj.core.api.Assertions.assertThat(walletRepository).isNotNull();
    }

    @Test
    @DisplayName("a handle is canonicalised the same way whatever the caller typed")
    void handleCanonicalisationIsAvailable() {
        org.assertj.core.api.Assertions
                .assertThat(com.payflow.payment.service.Handle.canonicalize("@Sai123")).contains("sai123");
        org.assertj.core.api.Assertions
                .assertThat(com.payflow.payment.service.Handle.canonicalize("  MERCHANT ")).contains("merchant");
        org.assertj.core.api.Assertions
                .assertThat(com.payflow.payment.service.Handle.canonicalize("!!")).isEmpty();
    }

    @Test
    @DisplayName("recipient activity includes completed incoming transfers but not failed transfers")
    void recipientActivityIncludesOnlySuccessfulIncomingPayments() {
        paymentRepository.deleteAll();
        String senderOwnerId = UUID.randomUUID().toString();
        String recipientOwnerId = UUID.randomUUID().toString();
        GatewayUserContext sender = new GatewayUserContext(
                1L, senderOwnerId, "USER", "sender@example.test", "sender");
        CreatePaymentRequest request = new CreatePaymentRequest(
                new BigDecimal("10.00"), "USD", "recipient", "Test transfer");
        Payment successful = new Payment(sender, request, "recipient", recipientOwnerId,
                "recipient@example.test", PaymentStatus.SUCCESS);
        Payment failed = new Payment(sender, request, "recipient", recipientOwnerId,
                "recipient@example.test", PaymentStatus.FAILED);
        paymentRepository.saveAllAndFlush(List.of(successful, failed));

        List<PaymentResponse> activity = paymentRepository.findActivityByOwnerId(recipientOwnerId).stream()
                .map(payment -> PaymentResponse.from(payment, recipientOwnerId))
                .toList();

        assertThat(activity).hasSize(1);
        assertThat(activity.get(0).direction()).isEqualTo(PaymentDirection.RECEIVED);
        assertThat(activity.get(0).senderHandle()).isEqualTo("sender");
    }
}
