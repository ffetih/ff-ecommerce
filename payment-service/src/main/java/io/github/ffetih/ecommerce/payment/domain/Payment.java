package io.github.ffetih.ecommerce.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The payment of one order (ADR-0009).
 * <pre>
 *   AUTHORIZING ──approved──► AUTHORIZED ──capture──► CAPTURING ──► CAPTURED
 *        │      ──declined──► DECLINED        │                └──► CAPTURE_FAILED
 *        │                                    └──void──► VOIDING ──► VOIDED
 *        └── approved while a void was requested ──────► VOIDING
 * </pre>
 * The three "-ING" states mean a provider call is in flight and its outcome is not known yet. They
 * end only with the provider's answer, asked again with the same idempotency key if needed; a timeout
 * never turns into a decline. Repeating a step the payment has already taken changes nothing.
 *
 * @param providerReference the provider's id for the authorization, known once it is approved
 * @param voidRequested     the order was cancelled while the authorization was still in flight
 */
public record Payment(
        UUID id,
        UUID orderId,
        UUID customerId,
        Money amount,
        String cardToken,
        Status status,
        String providerReference,
        boolean voidRequested,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status {
        AUTHORIZING, AUTHORIZED, DECLINED, CAPTURING, CAPTURED, CAPTURE_FAILED, VOIDING, VOIDED
    }

    /** The provider operation a payment in flight is waiting for. */
    public enum Operation {
        AUTHORIZE, CAPTURE, VOID
    }

    public Payment {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(status, "status is required");
        if (cardToken == null || cardToken.isBlank()) {
            throw new IllegalArgumentException("A card token is required");
        }
    }

    public static Payment start(UUID orderId, UUID customerId, Money amount, String cardToken, Instant now) {
        return new Payment(UUID.randomUUID(), orderId, customerId, amount, cardToken, Status.AUTHORIZING,
                null, false, null, now, now);
    }

    /** The provider approved the authorization. If the order was cancelled meanwhile, the hold is released next. */
    public Payment authorized(String reference, Instant now) {
        return switch (status) {
            case AUTHORIZING -> with(voidRequested ? Status.VOIDING : Status.AUTHORIZED, reference, failureReason, now);
            case DECLINED -> throw new IllegalPaymentTransitionException(id, status, "approve");
            default -> this; // already past the authorization
        };
    }

    public Payment declined(String reason, Instant now) {
        return switch (status) {
            case AUTHORIZING -> with(Status.DECLINED, providerReference, reason, now);
            case DECLINED -> this;
            default -> throw new IllegalPaymentTransitionException(id, status, "decline");
        };
    }

    /**
     * The order was cancelled before its parcel left. While the authorization is in flight the request
     * is remembered; an approved hold is released; a declined payment holds nothing to release.
     */
    public Payment requestVoid(Instant now) {
        return switch (status) {
            case AUTHORIZING -> voidRequested ? this
                    : new Payment(id, orderId, customerId, amount, cardToken, status, providerReference, true,
                            failureReason, createdAt, now);
            case AUTHORIZED -> with(Status.VOIDING, providerReference, failureReason, now);
            case DECLINED, VOIDING, VOIDED -> this;
            case CAPTURING, CAPTURED, CAPTURE_FAILED -> throw new IllegalPaymentTransitionException(id, status, "void");
        };
    }

    public Payment voided(Instant now) {
        return switch (status) {
            case VOIDING -> with(Status.VOIDED, providerReference, failureReason, now);
            case VOIDED -> this;
            default -> throw new IllegalPaymentTransitionException(id, status, "confirm the void of");
        };
    }

    /** The parcel has left: take the money. */
    public Payment requestCapture(Instant now) {
        return switch (status) {
            case AUTHORIZED -> with(Status.CAPTURING, providerReference, failureReason, now);
            case CAPTURING, CAPTURED, CAPTURE_FAILED -> this;
            default -> throw new IllegalPaymentTransitionException(id, status, "capture");
        };
    }

    public Payment captured(Instant now) {
        return switch (status) {
            case CAPTURING -> with(Status.CAPTURED, providerReference, failureReason, now);
            case CAPTURED -> this;
            default -> throw new IllegalPaymentTransitionException(id, status, "confirm the capture of");
        };
    }

    public Payment captureFailed(String reason, Instant now) {
        return switch (status) {
            case CAPTURING -> with(Status.CAPTURE_FAILED, providerReference, reason, now);
            case CAPTURE_FAILED -> this;
            default -> throw new IllegalPaymentTransitionException(id, status, "fail the capture of");
        };
    }

    /** True while a provider call is in flight and its outcome is not known. */
    public boolean inFlight() {
        return operationInFlight() != null;
    }

    /** The provider operation the payment is waiting for, or {@code null} if none. */
    public Operation operationInFlight() {
        return switch (status) {
            case AUTHORIZING -> Operation.AUTHORIZE;
            case CAPTURING -> Operation.CAPTURE;
            case VOIDING -> Operation.VOID;
            default -> null;
        };
    }

    /**
     * The key for the provider call of {@code operation}: the same for every attempt, so asking again
     * after a timeout returns the first outcome instead of charging twice.
     */
    public String idempotencyKey(Operation operation) {
        return id + ":" + operation.name().toLowerCase(java.util.Locale.ROOT);
    }

    private Payment with(Status next, String reference, String reason, Instant now) {
        return new Payment(id, orderId, customerId, amount, cardToken, next, reference, voidRequested, reason,
                createdAt, now);
    }
}
