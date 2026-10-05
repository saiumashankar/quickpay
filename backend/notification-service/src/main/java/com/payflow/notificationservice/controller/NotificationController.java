package com.payflow.notificationservice.controller;

import com.payflow.notificationservice.model.PaymentEvent;
import com.payflow.notificationservice.model.PaymentEventType;
import com.payflow.notificationservice.model.PaymentStatus;
import com.payflow.notificationservice.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/notify")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Sends a single receipt by hand so the SMTP path can be exercised without
     * running a transfer. Only the sender side is populated: there is no second
     * real party behind a manual test, and inventing a recipient would send
     * somebody an imaginary receipt.
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, String>> sendTestNotification(
            @RequestParam String email,
            @RequestParam(defaultValue = "100.00") String amount,
            @RequestParam(defaultValue = "USD") String currency) {
        PaymentEvent event = new PaymentEvent(
                PaymentEventType.PAYMENT_SUCCESS.name(),
                UUID.randomUUID(),
                1L,
                UUID.randomUUID().toString(),
                email,
                "manual-tester",
                new BigDecimal(amount),
                currency,
                "manual-test-recipient",
                null,
                null,
                "manual test notification",
                null,
                PaymentStatus.SUCCESS,
                Instant.now());
        notificationService.handlePaymentEvent(event);
        return ResponseEntity.ok(Map.of("status", "processed", "recipient", email));
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, String>> sendNotification(@RequestBody PaymentEvent event) {
        notificationService.handlePaymentEvent(event);
        return ResponseEntity.ok(Map.of("status", "processed", "paymentId", String.valueOf(event.paymentId())));
    }
}
