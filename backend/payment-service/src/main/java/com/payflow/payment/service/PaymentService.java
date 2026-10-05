package com.payflow.payment.service;

import com.payflow.payment.client.HandleOwner;
import com.payflow.payment.dto.CreatePaymentRequest;
import com.payflow.payment.dto.GatewayUserContext;
import com.payflow.payment.dto.PaymentEvent;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.entity.OutboxEvent;
import com.payflow.payment.entity.Payment;
import com.payflow.payment.entity.PaymentEventType;
import com.payflow.payment.entity.PaymentStatus;
import com.payflow.payment.entity.Wallet;
import com.payflow.payment.exception.PaymentBadRequestException;
import com.payflow.payment.exception.PaymentConflictException;
import com.payflow.payment.exception.PaymentForbiddenException;
import com.payflow.payment.exception.PaymentNotFoundException;
import com.payflow.payment.repository.OutboxRepository;
import com.payflow.payment.repository.PaymentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moves money from one user's wallet to another's.
 *
 * The transfer is one database transaction. The debit, the credit, the payment
 * record and the two outbox events either all happen or none of them do, which
 * is what stops the classic half transfer where the payer is charged and the
 * payee is never credited. There is no distributed transaction here precisely
 * because both balances live in this one database; the event is published after
 * the commit rather than before it.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String PROCESSING = "PROCESSING";
    private static final String IDEMPOTENCY_SEPARATOR = ":";
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then redis.call('del', KEYS[1]); return 1 else return 0 end",
            Long.class);
    /**
     * Promotes the reservation to a completed record, but only if this request still
     * owns it: another request that took the key over after this one rolled back must
     * not have its record overwritten.
     *
     * Two things about this script are load bearing. Lua requires a return to be the
     * last statement in a block, so the write is a statement of its own and the
     * function returns an explicit 1 rather than returning the write's reply. And it
     * has to return a number: {@code psetex} replies with a status string, which
     * cannot be deserialised into the Long this script is declared to produce, and the
     * mismatch would abort the call after the write had already landed.
     */
    private static final DefaultRedisScript<Long> COMPLETE_IDEMPOTENCY = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('psetex', KEYS[1], ARGV[3], ARGV[2]); return 1 "
                    + "else return 0 end",
            Long.class);

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration idempotencyTtl;
    private final HandleResolver handleResolver;
    private final WalletService walletService;

    public PaymentService(PaymentRepository paymentRepository, OutboxRepository outboxRepository,
                         StringRedisTemplate redis,
                         ObjectMapper objectMapper,
                         HandleResolver handleResolver,
                         WalletService walletService,
                         @Value("${payment.idempotency.ttl:24h}") Duration idempotencyTtl) {
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.handleResolver = handleResolver;
        this.walletService = walletService;
        this.idempotencyTtl = idempotencyTtl;
    }

    @Transactional
    public PaymentResponse initiate(GatewayUserContext principal, String idempotencyKey,
                                    CreatePaymentRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new PaymentBadRequestException("Idempotency-Key must contain 1 to 128 characters");
        }
        validatePrincipal(principal);
        if (principal.handle() == null || principal.handle().isBlank()) {
            // A transfer has to be recorded with the handle it was made from, so
            // a token issued before that claim existed cannot send money. Saying
            // so beats a null column, and it is fixed by signing in again.
            throw new PaymentForbiddenException("Your session does not carry a handle, please sign in again");
        }

        String canonicalHandle = Handle.canonicalize(request.recipientHandle())
                .orElseThrow(() -> new PaymentBadRequestException(
                        "Handle must be 3 to 30 characters, starting with a letter or digit, and contain only letters, digits, dot or underscore"));

        String fingerprint = fingerprint(request.amount(), request.currency(), canonicalHandle, request.description());
        String idemRedisKey = "payment:idempotency:" + principal.ownerId() + ":" + idempotencyKey;
        String existingId = redis.opsForValue().get(idemRedisKey);
        if (existingId != null) {
            if (PROCESSING.equals(existingId)) {
                throw new PaymentConflictException("An identical transfer is still processing");
            }
            String[] cached = existingId.split(IDEMPOTENCY_SEPARATOR, 2);
            if (cached.length != 2 || !fingerprint.equals(cached[0])) {
                throw new PaymentConflictException("Idempotency key was already used for a different request");
            }
            // The retry replays the stored result instead of moving money again.
            // This is the guarantee that a double tap, a network retry or a
            // client that did not see the response cannot pay twice.
            return paymentRepository.findByIdAndOwnerId(UUID.fromString(cached[1]), principal.ownerId())
                    .map(PaymentResponse::from)
                    .orElseThrow(() -> new PaymentConflictException("Idempotency record has no payment"));
        }

        // Resolve the handle before reserving the idempotency key. Resolution is
        // a network call that can legitimately fail and be retried, and holding
        // a reservation across it would turn a dependency blip into a 409 that
        // the client cannot get past for two minutes.
        HandleOwner recipientOwner = handleResolver.resolve(canonicalHandle);

        if (recipientOwner.ownerId().toString().equals(principal.ownerId())) {
            throw new PaymentBadRequestException("You cannot send money to your own handle");
        }
        if (recipientOwner.handle() != null && recipientOwner.handle().equals(principal.handle())) {
            throw new PaymentBadRequestException("You cannot send money to your own handle");
        }

        String reservation = UUID.randomUUID().toString();
        Boolean reserved = redis.opsForValue().setIfAbsent(idemRedisKey, reservation, Duration.ofMinutes(2));
        if (!Boolean.TRUE.equals(reserved)) {
            throw new PaymentConflictException("An identical transfer is already processing");
        }

        UUID[] paymentIdHolder = new UUID[1];
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    if (status == STATUS_COMMITTED) {
                        redis.execute(COMPLETE_IDEMPOTENCY, List.of(idemRedisKey), reservation,
                                fingerprint + IDEMPOTENCY_SEPARATOR + paymentIdHolder[0],
                                Long.toString(idempotencyTtl.toMillis()));
                    } else {
                        redis.execute(RELEASE_IF_OWNER, List.of(idemRedisKey), reservation);
                    }
                } catch (RuntimeException exception) {
                    log.error("Failed to finalize payment Redis coordination for owner {}", principal.ownerId(), exception);
                }
            }
        });

        // Both wallets exist before any balance is inspected, so the rows that
        // are locked below are the rows that are read.
        walletService.getOrCreate(principal.ownerId(), principal.handle(), request.currency());
        walletService.getOrCreate(recipientOwner.ownerId().toString(), canonicalHandle, request.currency());

        Map<String, Wallet> locked = walletService.lockBoth(principal.ownerId(), recipientOwner.ownerId().toString());
        Wallet senderWallet = locked.get(principal.ownerId());
        Wallet recipientWallet = locked.get(recipientOwner.ownerId().toString());

        requireCurrency(senderWallet, request.currency());
        requireCurrency(recipientWallet, request.currency());

        Payment payment = paymentRepository.save(new Payment(principal, request, canonicalHandle,
                recipientOwner.ownerId().toString(), recipientOwner.email(), PaymentStatus.PENDING));
        paymentIdHolder[0] = payment.getId();
        outboxRepository.save(new OutboxEvent(payment, PaymentEventType.PAYMENT_INITIATED,
                serialize(PaymentEvent.from(payment, PaymentEventType.PAYMENT_INITIATED, PaymentStatus.PENDING))));

        boolean insufficientFunds = !senderWallet.hasAtLeast(request.amount());
        if (insufficientFunds) {
            // Recorded as a real payment that failed, not as an exception. The
            // payer asked for a transfer and the answer was no, and they are
            // owed a notification saying so. Throwing instead would roll the
            // transaction back and there would be nothing for notification-service
            // to react to, so the user would simply see nothing happen.
            payment.setStatus(PaymentStatus.FAILED);
            outboxRepository.save(new OutboxEvent(payment, PaymentEventType.PAYMENT_FAILED,
                    serialize(PaymentEvent.from(payment, PaymentEventType.PAYMENT_FAILED, PaymentStatus.FAILED))));
            log.info("Transfer {} to @{} declined for owner {}: balance {} is short of {}",
                    payment.getId(), canonicalHandle, principal.ownerId(), senderWallet.getBalance(), request.amount());
            return PaymentResponse.from(payment);
        }

        // Debit and credit are two writes to two rows inside one transaction,
        // so the sum of all balances cannot change even if the service dies here.
        senderWallet.debit(request.amount());
        recipientWallet.credit(request.amount());

        payment.setStatus(PaymentStatus.SUCCESS);
        outboxRepository.save(new OutboxEvent(payment, PaymentEventType.PAYMENT_SUCCESS,
                serialize(PaymentEvent.from(payment, PaymentEventType.PAYMENT_SUCCESS, PaymentStatus.SUCCESS))));

        log.info("Transferred {} {} from @{} to @{} as payment {}",
                request.amount(), request.currency(), principal.handle(), canonicalHandle, payment.getId());
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID id, GatewayUserContext principal) {
        Payment payment = paymentRepository.findByIdAndOwnerId(id, principal.ownerId())
                .orElseThrow(PaymentNotFoundException::new);
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getUserPayments(Long userId, GatewayUserContext principal) {
        if (!"ADMIN".equals(principal.role()) && !principal.userId().equals(userId)) {
            throw new PaymentForbiddenException("Cannot view another user's payments");
        }
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    private void requireCurrency(Wallet wallet, String currency) {
        if (wallet.getCurrency() != null && !wallet.getCurrency().equalsIgnoreCase(currency)) {
            throw new PaymentBadRequestException(
                    wallet.getHandle() + "'s wallet holds " + wallet.getCurrency()
                            + " and cannot receive " + currency + ". No conversion is performed.");
        }
    }

    private String serialize(PaymentEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize payment event", exception);
        }
    }

    private UUID validatePrincipal(GatewayUserContext principal) {
        if (principal == null || principal.userId() == null || principal.userId() < 1
                || principal.ownerId() == null
                || principal.email() == null || principal.email().isBlank()
                || !List.of("USER", "ADMIN").contains(principal.role())) {
            throw new PaymentForbiddenException("Authenticated user context is invalid");
        }
        try {
            return UUID.fromString(principal.ownerId());
        } catch (IllegalArgumentException exception) {
            throw new PaymentForbiddenException("Authenticated owner ID is invalid");
        }
    }

    /**
     * Identifies "the same transfer" for the idempotency check.
     *
     * The canonical handle is used rather than the raw input so that "@Sai123"
     * and "sai123" are recognised as the same request and replay rather than
     * being rejected as a conflicting reuse of the key. The amount is stripped
     * of trailing zeros so 500 and 500.00 also match.
     */
    private String fingerprint(java.math.BigDecimal amount, String currency, String recipientHandle,
                               String description) {
        String data = amount.stripTrailingZeros().toPlainString() + "\n"
                + currency + "\n" + recipientHandle + "\n"
                + (description == null ? "" : description);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
