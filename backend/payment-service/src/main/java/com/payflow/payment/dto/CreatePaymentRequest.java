package com.payflow.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A request to send money to somebody else's wallet.
 *
 * The recipient is a handle, not an account number, an email or a phone number.
 * That is the whole user facing idea: the person types who they want to pay and
 * nothing about how the money is actually routed. The handle is resolved to a
 * wallet by HandleResolver before any balance is touched, so a typo produces a
 * validation error rather than a lost transfer.
 */
public record CreatePaymentRequest(
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 31) String recipientHandle,
        @Size(max = 500) String description
) {
}
