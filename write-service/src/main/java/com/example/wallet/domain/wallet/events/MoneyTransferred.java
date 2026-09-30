package com.example.wallet.domain.wallet.events;

import java.math.BigDecimal;
import java.time.Instant;

import org.axonframework.eventsourcing.annotation.EventTag;

import com.example.wallet.domain.wallet.Wallet;

/**
 * Domain event: money was transferred between two wallets as a single business fact.
 *
 * <p>Past-tense fact, independent of any command class name. Field names and types mirror
 * {@code contracts/events/money-transferred.v1.schema.json} exactly so JSON serialization
 * produces the shared contract shape:</p>
 *
 * <pre>
 * { "eventType": "MoneyTransferred", "eventVersion": 1, "eventId": "...",
 *   "transferId": "...", "sourceWalletId": "...", "targetWalletId": "...",
 *   "amount": 1000, "currency": "COP", "occurredAt": "2026-01-01T00:00:00Z" }
 * </pre>
 *
 * <p>Monetary values use {@link BigDecimal}; {@code occurredAt} is a {@link Instant}
 * (serialized as an ISO-8601 {@code date-time}).</p>
 *
 * <h2>Dynamic Consistency Boundary tagging (task 5.2, verified — see
 * {@code infrastructure/axon/dcb-notes.md})</h2>
 * <p>This single event is tagged so it belongs to <strong>both</strong> participating wallet
 * event streams. {@link EventTag} (from {@code org.axonframework.eventsourcing.annotation}) is
 * placed on record components; Axon reads these annotations at append time to derive the event's
 * tags, and derives the DCB append condition from the tag streams that were read while loading the
 * source and target wallets. Two {@code @EventTag(key = }{@link Wallet#TAG_KEY}{@code )}
 * annotations — one on {@code sourceWalletId}, one on {@code targetWalletId} — make the fact land
 * in both {@code walletId} streams so each side folds its own delta (requirements 4.7, 13.1,
 * 13.3). A third {@code @EventTag(key = "transferId")} tags the event by transfer identity to
 * enable event-store-based idempotency (task 5.3); tagging now is harmless and does not implement
 * the duplicate-detection logic (that is task 5.3).</p>
 *
 * <p><strong>JSON contract is unchanged.</strong> {@code @EventTag} is a write-side-only Axon
 * annotation with no {@code @JacksonAnnotationsInside} meta-annotation and no Jackson semantics;
 * serialization still uses the record component names, so the on-the-wire shape defined by
 * {@code money-transferred.v1.schema.json} is identical.</p>
 *
 * @param eventType      contract discriminator, always {@value #EVENT_TYPE}
 * @param eventVersion   contract version, always {@value #EVENT_VERSION}
 * @param eventId        unique event identifier, used by the Read Side for idempotency
 * @param transferId     free-form, non-empty transfer identifier (Write Side idempotency key)
 * @param sourceWalletId free-form, non-empty source wallet identifier
 * @param targetWalletId free-form, non-empty target wallet identifier
 * @param amount         transferred amount, strictly greater than zero
 * @param currency       fixed POC currency, {@code "COP"}
 * @param occurredAt     timestamp when the event occurred
 */
public record MoneyTransferred(
        String eventType,
        int eventVersion,
        String eventId,
        @EventTag(key = "transferId") String transferId,
        @EventTag(key = Wallet.TAG_KEY) String sourceWalletId,
        @EventTag(key = Wallet.TAG_KEY) String targetWalletId,
        BigDecimal amount,
        String currency,
        Instant occurredAt
) {

    /** Contract event name constant (matches the JSON contract {@code eventType} const). */
    public static final String EVENT_TYPE = "MoneyTransferred";

    /** Contract version constant (matches the JSON contract {@code eventVersion} const). */
    public static final int EVENT_VERSION = 1;

    /**
     * Convenience factory that fills in the contract constants {@code eventType} and
     * {@code eventVersion}, so producers only supply the business fields.
     *
     * @param eventId        unique event identifier
     * @param transferId     free-form, non-empty transfer identifier
     * @param sourceWalletId free-form, non-empty source wallet identifier
     * @param targetWalletId free-form, non-empty target wallet identifier
     * @param amount         transferred amount, strictly greater than zero
     * @param currency       fixed POC currency, {@code "COP"}
     * @param occurredAt     timestamp when the event occurred
     * @return a fully-populated event matching the v1 contract
     */
    public static MoneyTransferred of(
            String eventId,
            String transferId,
            String sourceWalletId,
            String targetWalletId,
            BigDecimal amount,
            String currency,
            Instant occurredAt) {
        return new MoneyTransferred(
                EVENT_TYPE, EVENT_VERSION, eventId, transferId,
                sourceWalletId, targetWalletId, amount, currency, occurredAt);
    }
}
