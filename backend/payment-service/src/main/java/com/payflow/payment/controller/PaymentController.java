package com.payflow.payment.controller;

import com.payflow.payment.dto.CreatePaymentRequest;
import com.payflow.payment.dto.GatewayUserContext;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.security.AuthenticatedUserProvider;
import com.payflow.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final AuthenticatedUserProvider authenticatedUser;

    public PaymentController(PaymentService paymentService, AuthenticatedUserProvider authenticatedUser) {
        this.paymentService = paymentService;
        this.authenticatedUser = authenticatedUser;
    }

    /**
     * Sends money to another user's wallet by handle.
     *
     * Idempotency-Key is required. It is what makes a retry safe: the same key
     * with the same body replays the original result without moving money a
     * second time, and the same key with a different body is rejected rather
     * than silently charged.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse initiate(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody CreatePaymentRequest request) {
        return paymentService.initiate(authenticatedUser.currentUser(), idempotencyKey, request);
    }

    @GetMapping("/{id}")
    public PaymentResponse getPayment(@PathVariable UUID id) {
        return paymentService.getPayment(id, authenticatedUser.currentUser());
    }

    /**
     * The caller's own transfer history, newest first.
     *
     * Named /me rather than left as the bare collection so that the path says
     * whose history it is. The user id is never taken from the query: it comes
     * from the verified token, so there is no way to ask for somebody else's.
     */
    @GetMapping("/me")
    public List<PaymentResponse> getMyPayments() {
        GatewayUserContext principal = authenticatedUser.currentUser();
        return paymentService.getUserPayments(principal.userId(), principal);
    }

    /**
     * Kept as an alias for the collection path. @GetMapping("/me") and
     * @GetMapping would otherwise be two names for the same thing.
     */
    @GetMapping
    public List<PaymentResponse> getMyPaymentsLegacy() {
        return getMyPayments();
    }
}
