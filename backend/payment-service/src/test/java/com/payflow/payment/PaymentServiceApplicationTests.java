package com.payflow.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
}
