package com.example.wallet.domain.wallet.events;

import java.math.BigDecimal;
import java.time.Instant;

import org.axonframework.eventsourcing.annotation.EventTag;

import com.example.wallet.domain.wallet.Wallet;

/**
 * Domain event: money was withdrawn from a wallet.
 *
 * <p>Past-tense fact, independent of any command class name. Field names and types mirror
 * {@code contracts/events/money-withdrawn.v1.schema.json} exactly (same shape as
 * {@link MoneyDeposited}) so JSON serialization produces the shared contract shape:</p>
 *
 * <pre>
 * { "eventType": "MoneyWithdrawn", "eventVersion": 1, "eventId": "...",
 *   "walletId": "...", "amount": 1000, "currency": "COP",
 *   "occurredAt": "2026-01-01T00:00:00Z" }
 * </pre>
 *
 * <p>Monetary values use {@link BigDecimal}; {@code occurredAt} is a {@link Instant}
 * (serialized as an ISO-8601 {@code date-time}).</p>
 *
 * @param eventType    contract discriminator, always {@value #EVENT_TYPE}
 * @param eventVersion contract version, always {@value #EVENT_VERSION}
 * @param eventId      unique event identifier, used by the Read Side for idempotency
 * @param walletId     free-form, non-empty wallet identifier
 * @param amount       withdrawn amount, strictly greater than zero
 * @param currency     fixed POC currency, {@code "COP"}
 * @param occurredAt   timestamp when the event occurred
 */
public record MoneyWithdrawn(
        String eventType,
        int eventVersion,
        String eventId,
        @EventTag(key = Wallet.TAG_KEY) String walletId,
        BigDecimal amount,
        String currency,
        Instant occurredAt
) {

    /** Contract event name constant (matches the JSON contract {@code eventType} const). */
    public static final String EVENT_TYPE = "MoneyWithdrawn";

    /** Contract version constant (matches the JSON contract {@code eventVersion} const). */
    public static final int EVENT_VERSION = 1;

    /**
     * Convenience factory that fills in the contract constants {@code eventType} and
     * {@code eventVersion}, so producers only supply the business fields.
     *
     * @param eventId    unique event identifier
     * @param walletId   free-form, non-empty wallet identifier
     * @param amount     withdrawn amount, strictly greater than zero
     * @param currency   fixed POC currency, {@code "COP"}
     * @param occurredAt timestamp when the event occurred
     * @return a fully-populated event matching the v1 contract
     */
    public static MoneyWithdrawn of(
            String eventId,
            String walletId,
            BigDecimal amount,
            String currency,
            Instant occurredAt) {
        return new MoneyWithdrawn(EVENT_TYPE, EVENT_VERSION, eventId, walletId, amount, currency, occurredAt);
    }
}
