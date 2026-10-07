package com.payflow.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.client.HandleOwner;
import com.payflow.payment.dto.CreatePaymentRequest;
import com.payflow.payment.dto.GatewayUserContext;
import com.payflow.payment.dto.PaymentDirection;
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
import com.payflow.payment.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the transfer path where correctness actually matters: a client that
 * retries after a lost response must never be charged twice, and the sender's
 * balance must never be reduced without the recipient's being increased.
 *
 * PaymentService coordinates Redis, PostgreSQL and the wallet rows, and
 * registers a TransactionSynchronization to finalise the idempotency record once
 * the database outcome is known. Outside a real transaction that callback is
 * never invoked, so these tests activate the synchronisation manager explicitly
 * and then run the registered callbacks themselves. That is what makes the
 * commit and rollback branches assertable.
 *
 * The wallets are real objects rather than mocks, so the arithmetic on the
 * balance is genuinely exercised. Mocking Wallet would have made it possible to
 * assert "debit was called" without ever checking that the money arrived.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceIdempotencyTest {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final String OWNER_ID = "6f1c2b4e-7a8d-4c3f-9b5a-1d2e3f4a5b6c";
    private static final String RECIPIENT_OWNER_ID = "1b0f6a5e-1f0a-4f0e-9b3a-2c3d4e5f6a7b";

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private WalletRepository walletRepository;
    @Mock
    private HandleResolver handleResolver;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private PaymentService paymentService;

    private Wallet senderWallet;
    private Wallet recipientWallet;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOperations);
        lenient().when(handleResolver.resolve(anyString())).thenReturn(
                new HandleOwner(99L, UUID.fromString(RECIPIENT_OWNER_ID), "merchant", "merchant@payflow.dev"));

        senderWallet = new Wallet(OWNER_ID, "asha", new BigDecimal("1000.00"), "USD");
        recipientWallet = new Wallet(RECIPIENT_OWNER_ID, "merchant", new BigDecimal("250.00"), "USD");
        lenient().when(walletRepository.findByOwnerId(anyString())).thenReturn(Optional.empty());
        lenient().when(walletRepository.saveAndFlush(any(Wallet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(walletRepository.findAllByOwnerIdInForUpdate(any()))
                .thenReturn(List.of(senderWallet, recipientWallet));

        // findAndRegisterModules picks up JavaTimeModule, which PaymentEvent needs for
        // its Instant occurredAt. The running application gets the same auto-configured mapper.
        paymentService = new PaymentService(paymentRepository, outboxRepository, redis,
                new ObjectMapper().findAndRegisterModules(),
                handleResolver, walletService(walletRepository),
                IDEMPOTENCY_TTL);
    }

    private WalletService walletService(WalletRepository repository) {
        return new WalletService(repository);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private GatewayUserContext principal() {
        return new GatewayUserContext(42L, OWNER_ID, "USER", "asha@payflow.dev", "asha");
    }

    private CreatePaymentRequest request() {
        return new CreatePaymentRequest(new BigDecimal("500.00"), "USD", "@Merchant", "Lunch");
    }

    /**
     * Stands in for the JPA save that would otherwise hand back the entity.
     * Lenient because a request that is rejected before the insert, such as a
     * currency mismatch, never reaches it.
     */
    private void stubPaymentSave() {
        lenient().when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubFirstTimeRequest() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        stubPaymentSave();
    }

    /**
     * Runs the call with transaction synchronisation active, then reports the
     * registered callbacks so a test can replay the commit or rollback branch.
     */
    private PaymentResponse initiateWithinTransaction(String key, CreatePaymentRequest request) {
        TransactionSynchronizationManager.initSynchronization();
        return paymentService.initiate(principal(), key, request);
    }

    private void completeTransaction(int status) {
        List<TransactionSynchronization> registered =
                TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        registered.forEach(synchronization -> synchronization.afterCompletion(status));
    }

    @Test
    @DisplayName("a first request writes the payment and both outbox events")
    void firstRequestCreatesPaymentAndTwoEvents() {
        stubFirstTimeRequest();

        PaymentResponse response = initiateWithinTransaction("key-1", request());
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(2)).save(events.capture());
        assertThat(events.getAllValues()).extracting(OutboxEvent::getEventType)
                .containsExactly(PaymentEventType.PAYMENT_INITIATED, PaymentEventType.PAYMENT_SUCCESS);
        assertThat(events.getAllValues()).allSatisfy(event -> {
            assertThat(event.getPayload()).contains("\"eventType\"");
            assertThat(event.getPayload()).contains("asha@payflow.dev");
            assertThat(event.getPublishedAt()).isNull();
        });
    }

    @Test
    @DisplayName("a successful transfer debits the sender and credits the recipient by the same amount")
    void transferMovesMoneyBetweenWallets() {
        stubFirstTimeRequest();

        initiateWithinTransaction("key-1", request());
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(senderWallet.getBalance()).isEqualByComparingTo("500.00");
        assertThat(recipientWallet.getBalance()).isEqualByComparingTo("750.00");
    }

    @Test
    @DisplayName("the total across both wallets is unchanged by a transfer")
    void transferConservesMoney() {
        stubFirstTimeRequest();

        initiateWithinTransaction("key-1", request());
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(senderWallet.getBalance().add(recipientWallet.getBalance()))
                .isEqualByComparingTo("1250.00");
    }

    @Test
    @DisplayName("a sender who cannot cover the amount yields FAILED and no money moves")
    void insufficientFundsProducesFailedEvent() {
        senderWallet.debit(new BigDecimal("900.00"));
        stubFirstTimeRequest();

        PaymentResponse response = initiateWithinTransaction("key-short",
                new CreatePaymentRequest(new BigDecimal("500.00"), "USD", "merchant", null));
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(senderWallet.getBalance()).isEqualByComparingTo("100.00");
        assertThat(recipientWallet.getBalance()).isEqualByComparingTo("250.00");
        ArgumentCaptor<OutboxEvent> events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(2)).save(events.capture());
        assertThat(events.getAllValues()).extracting(OutboxEvent::getEventType)
                .containsExactly(PaymentEventType.PAYMENT_INITIATED, PaymentEventType.PAYMENT_FAILED);
    }

    @Test
    @DisplayName("a transfer of exactly the whole balance is allowed")
    void spendingTheExactBalanceIsAllowed() {
        senderWallet.debit(new BigDecimal("500.00"));
        stubFirstTimeRequest();

        PaymentResponse response = initiateWithinTransaction("key-exact", request());
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(senderWallet.getBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("one unit more than the balance is refused")
    void spendingOneUnitOverIsRefused() {
        senderWallet.debit(new BigDecimal("500.00"));
        stubFirstTimeRequest();

        PaymentResponse response = initiateWithinTransaction("key-over",
                new CreatePaymentRequest(new BigDecimal("500.01"), "USD", "merchant", null));
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(senderWallet.getBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("the recipient is resolved to the wallet that is credited, not to the handle as typed")
    void recipientHandleIsResolvedToAnOwner() {
        stubFirstTimeRequest();

        PaymentResponse response = initiateWithinTransaction("key-1", request());
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verify(handleResolver).resolve("merchant");
        assertThat(response.recipientHandle()).isEqualTo("merchant");
        assertThat(response.senderHandle()).isEqualTo("asha");
    }

    @Test
    @DisplayName("both wallets are locked before a balance is read, so concurrent transfers cannot overspend")
    void bothWalletsAreLockedForTheTransfer() {
        stubFirstTimeRequest();

        initiateWithinTransaction("key-1", request());
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verify(walletRepository).findAllByOwnerIdInForUpdate(any());
    }

    @Test
    @DisplayName("sending to your own handle is refused before anything is written")
    void selfTransferIsRejected() {
        when(handleResolver.resolve(anyString()))
                .thenReturn(new HandleOwner(42L, UUID.fromString(OWNER_ID), "asha", "asha@payflow.dev"));

        assertThatThrownBy(() -> paymentService.initiate(principal(), "key-self", request()))
                .isInstanceOf(PaymentBadRequestException.class)
                .hasMessageContaining("own handle");
        verify(paymentRepository, never()).save(any());
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("a transfer into a different currency is refused rather than converted")
    void currencyMismatchIsRejected() {
        recipientWallet = new Wallet(RECIPIENT_OWNER_ID, "merchant", new BigDecimal("250.00"), "EUR");
        when(walletRepository.findAllByOwnerIdInForUpdate(any()))
                .thenReturn(List.of(senderWallet, recipientWallet));
        stubFirstTimeRequest();

        assertThatThrownBy(() -> initiateWithinTransaction("key-fx", request()))
                .isInstanceOf(PaymentBadRequestException.class)
                .hasMessageContaining("No conversion");
        assertThat(senderWallet.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(recipientWallet.getBalance()).isEqualByComparingTo("250.00");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a sender wallet with a different currency is rejected without moving money")
    void senderCurrencyMismatchIsRejected() {
        senderWallet = new Wallet(OWNER_ID, "asha", new BigDecimal("1000.00"), "EUR");
        when(walletRepository.findAllByOwnerIdInForUpdate(any()))
                .thenReturn(List.of(senderWallet, recipientWallet));
        stubFirstTimeRequest();

        assertThatThrownBy(() -> initiateWithinTransaction("key-fx", request()))
                .isInstanceOf(PaymentBadRequestException.class)
                .hasMessageContaining("cannot receive USD");
        assertThat(senderWallet.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(recipientWallet.getBalance()).isEqualByComparingTo("250.00");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a handle that cannot be a handle is rejected without a lookup")
    void malformedHandleIsRejected() {
        assertThatThrownBy(() -> paymentService.initiate(principal(), "key-1",
                new CreatePaymentRequest(new BigDecimal("10.00"), "USD", "!!", null)))
                .isInstanceOf(PaymentBadRequestException.class);
        verify(handleResolver, never()).resolve(anyString());
    }

    @Test
    @DisplayName("a committed transaction finalises the Redis record so retries replay")
    void commitFinalisesIdempotencyRecord() {
        stubFirstTimeRequest();

        PaymentResponse response = initiateWithinTransaction("key-1", request());
        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        ArgumentCaptor<org.springframework.data.redis.core.script.RedisScript<Long>> script =
                ArgumentCaptor.forClass(org.springframework.data.redis.core.script.RedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);

        verify(redis, times(1)).execute(script.capture(), keys.capture(), args.capture(), args.capture(), args.capture());

        // The finalise script rewrites the value in place, and the replacement value is
        // "fingerprint:paymentId" so a later retry can replay this exact payment.
        assertThat(script.getValue().getScriptAsString()).contains("psetex");
        assertThat(args.getAllValues().get(1)).endsWith(":" + response.id());
        assertThat(args.getAllValues().get(2)).isEqualTo(Long.toString(IDEMPOTENCY_TTL.toMillis()));
    }

    @Test
    @DisplayName("a rolled back transaction releases the reservation instead of stranding the key")
    void rollbackReleasesReservation() {
        stubFirstTimeRequest();

        initiateWithinTransaction("key-1", request());
        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        ArgumentCaptor<org.springframework.data.redis.core.script.RedisScript<Long>> script =
                ArgumentCaptor.forClass(org.springframework.data.redis.core.script.RedisScript.class);
        // Only the reservation is passed: the release path has no finalised value or TTL.
        verify(redis, times(1)).execute(script.capture(), any(List.class), anyString());

        // The release script deletes only the caller's own reservation, so a retry after a
        // rollback is not blocked by a stale result, and it never writes a finalised value.
        assertThat(script.getValue().getScriptAsString()).contains("del");
        assertThat(script.getValue().getScriptAsString()).doesNotContain("psetex");
    }

    @Test
    @DisplayName("an identical retry replays the original payment instead of paying again")
    void identicalRetryReturnsOriginalPayment() {
        Payment existing = existingPayment(PaymentStatus.SUCCESS);
        UUID originalId = existing.getId();

        when(valueOperations.get(anyString())).thenReturn(fingerprinted(request(), originalId));
        when(paymentRepository.findByIdAndOwnerId(originalId, OWNER_ID)).thenReturn(Optional.of(existing));

        PaymentResponse replay = paymentService.initiate(principal(), "key-1", request());

        assertThat(replay.id()).isEqualTo(originalId);
        assertThat(replay.status()).isEqualTo(PaymentStatus.SUCCESS);
        verify(paymentRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        // The single most important assertion in this class: no wallet was
        // touched, so the retry did not cost the sender anything.
        verify(walletRepository, never()).findAllByOwnerIdInForUpdate(any());
    }

    @Test
    @DisplayName("a retry spelled with an @ and different case is recognised as the same request")
    void handleSpellingDoesNotBreakReplay() {
        Payment existing = existingPayment(PaymentStatus.SUCCESS);
        UUID originalId = existing.getId();

        // Fingerprinted with the canonical handle, as the first request stored it.
        when(valueOperations.get(anyString())).thenReturn(fingerprinted(request(), originalId));
        when(paymentRepository.findByIdAndOwnerId(originalId, OWNER_ID)).thenReturn(Optional.of(existing));

        PaymentResponse replay = paymentService.initiate(principal(), "key-1",
                new CreatePaymentRequest(new BigDecimal("500.00"), "USD", "merchant", "Lunch"));

        assertThat(replay.id()).isEqualTo(originalId);
    }

    @Test
    @DisplayName("reusing a key with different details is rejected rather than silently charged")
    void sameKeyDifferentRequestIsRejected() {
        when(valueOperations.get(anyString())).thenReturn(fingerprinted(request(), UUID.randomUUID()));

        assertThatThrownBy(() -> paymentService.initiate(principal(), "key-1",
                new CreatePaymentRequest(new BigDecimal("999.00"), "USD", "merchant", "Lunch")))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("different request");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a concurrent identical request is rejected as still processing")
    void concurrentRequestIsRejected() {
        when(valueOperations.get(anyString())).thenReturn("PROCESSING");

        assertThatThrownBy(() -> paymentService.initiate(principal(), "key-1", request()))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("still processing");
    }

    @Test
    @DisplayName("losing the SETNX race means another request already reserved the key")
    void lostReservationRaceIsRejected() {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> paymentService.initiate(principal(), "key-1", request()))
                .isInstanceOf(PaymentConflictException.class)
                .hasMessageContaining("already processing");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("trailing zeros do not change the fingerprint, so 500 and 500.00 are the same request")
    void amountScaleDoesNotAffectFingerprint() {
        Payment existing = existingPayment(PaymentStatus.SUCCESS);
        UUID originalId = existing.getId();
        when(valueOperations.get(anyString())).thenReturn(fingerprinted(request(), originalId));
        when(paymentRepository.findByIdAndOwnerId(originalId, OWNER_ID)).thenReturn(Optional.of(existing));

        PaymentResponse replay = paymentService.initiate(principal(), "key-1",
                new CreatePaymentRequest(new BigDecimal("500"), "USD", "merchant", "Lunch"));

        assertThat(replay.id()).isEqualTo(originalId);
    }

    @Test
    @DisplayName("a blank or oversized Idempotency-Key is rejected before any work happens")
    void invalidIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> paymentService.initiate(principal(), "  ", request()))
                .isInstanceOf(PaymentBadRequestException.class);
        assertThatThrownBy(() -> paymentService.initiate(principal(), null, request()))
                .isInstanceOf(PaymentBadRequestException.class);
        assertThatThrownBy(() -> paymentService.initiate(principal(), "x".repeat(129), request()))
                .isInstanceOf(PaymentBadRequestException.class);
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    @DisplayName("a context with an unparseable ownerId is rejected: it would corrupt the Redis key")
    void malformedOwnerIdIsRejected() {
        GatewayUserContext bad = new GatewayUserContext(42L, "not-a-uuid", "USER", "asha@payflow.dev", "asha");

        assertThatThrownBy(() -> paymentService.initiate(bad, "key-1", request()))
                .isInstanceOf(PaymentForbiddenException.class)
                .hasMessageContaining("owner ID");
    }

    @Test
    @DisplayName("a token issued before the handle claim cannot send money")
    void missingHandleCannotTransfer() {
        GatewayUserContext legacy = GatewayUserContext.withoutHandle(42L, OWNER_ID, "USER", "asha@payflow.dev");

        assertThatThrownBy(() -> paymentService.initiate(legacy, "key-1", request()))
                .isInstanceOf(PaymentForbiddenException.class)
                .hasMessageContaining("sign in again");
        verify(handleResolver, never()).resolve(anyString());
    }

    @Test
    @DisplayName("a caller cannot read another user's payment")
    void paymentIsScopedToOwner() {
        UUID someoneElsesPayment = UUID.randomUUID();
        when(paymentRepository.findByIdAndOwnerId(someoneElsesPayment, OWNER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment(someoneElsesPayment, principal()))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    @DisplayName("a non admin cannot list another user's payments")
    void listingAnotherUsersPaymentsIsForbidden() {
        GatewayUserContext caller = principal();

        assertThatThrownBy(() -> paymentService.getUserPayments(99L, caller))
                .isInstanceOf(PaymentForbiddenException.class);
        verify(paymentRepository, never()).findByUserIdOrderByCreatedAtDesc(any());
        verify(paymentRepository, never()).findActivityByOwnerId(anyString());
    }

    @Test
    @DisplayName("a recipient sees successful incoming transfers in their activity")
    void recipientActivityMarksIncomingTransfersAsReceived() {
        Payment received = existingPayment(PaymentStatus.SUCCESS);
        GatewayUserContext recipient = new GatewayUserContext(99L, RECIPIENT_OWNER_ID,
                "USER", "merchant@payflow.dev", "merchant");
        when(paymentRepository.findActivityByOwnerId(RECIPIENT_OWNER_ID)).thenReturn(List.of(received));

        List<PaymentResponse> activity = paymentService.getUserPayments(99L, recipient);

        assertThat(activity).hasSize(1);
        assertThat(activity.get(0).direction()).isEqualTo(PaymentDirection.RECEIVED);
        assertThat(activity.get(0).senderHandle()).isEqualTo("asha");
        assertThat(activity.get(0).recipientHandle()).isEqualTo("merchant");
        verify(paymentRepository).findActivityByOwnerId(RECIPIENT_OWNER_ID);
    }

    private Payment existingPayment(PaymentStatus status) {
        return new Payment(principal(), request(), "merchant", RECIPIENT_OWNER_ID,
                "merchant@payflow.dev", status);
    }

    /**
     * Rebuilds the Redis value a completed request leaves behind, deriving the hash
     * from the same canonical string PaymentService uses. A change to the fingerprint
     * format on one side only would break this test rather than silently pass.
     *
     * The handle is canonicalised here for the same reason the service does it:
     * "@Merchant" and "merchant" are the same recipient.
     */
    private String fingerprinted(CreatePaymentRequest request, UUID paymentId) {
        String data = request.amount().stripTrailingZeros().toPlainString() + "\n"
                + request.currency() + "\n" + Handle.canonicalize(request.recipientHandle()).orElseThrow() + "\n"
                + (request.description() == null ? "" : request.description());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest) + ":" + paymentId;
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
