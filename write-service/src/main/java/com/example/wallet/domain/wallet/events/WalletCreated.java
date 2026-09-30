package com.example.wallet.domain.wallet.events;

import org.axonframework.eventsourcing.annotation.EventTag;

import com.example.wallet.domain.wallet.Wallet;

/**
 * Domain event: a wallet was created.
 *
 * <p>Past-tense fact, independent of any command class name. Field names and types mirror
 * {@code contracts/events/wallet-created.v1.schema.json} exactly so JSON serialization
 * produces the shared contract shape:</p>
 *
 * <pre>
 * { "eventType": "WalletCreated", "eventVersion": 1,
 *   "walletId": "...", "ownerId": "...", "currency": "COP" }
 * </pre>
 *
 * <p>Unlike the money events, this contract carries neither {@code eventId} nor
 * {@code occurredAt} (per requirement 14.3 and the v1 schema).</p>
 *
 * @param eventType    contract discriminator, always {@value #EVENT_TYPE}
 * @param eventVersion contract version, always {@value #EVENT_VERSION}
 * @param walletId     free-form, non-empty wallet identifier
 * @param ownerId      free-form, non-empty owner identifier
 * @param currency     fixed POC currency, {@code "COP"}
 */
public record WalletCreated(
        String eventType,
        int eventVersion,
        @EventTag(key = Wallet.TAG_KEY) String walletId,
        String ownerId,
        String currency
) {

    /** Contract event name constant (matches the JSON contract {@code eventType} const). */
    public static final String EVENT_TYPE = "WalletCreated";

    /** Contract version constant (matches the JSON contract {@code eventVersion} const). */
    public static final int EVENT_VERSION = 1;

    /**
     * Convenience factory that fills in the contract constants {@code eventType} and
     * {@code eventVersion}, so producers only supply the business fields.
     *
     * @param walletId free-form, non-empty wallet identifier
     * @param ownerId  free-form, non-empty owner identifier
     * @param currency fixed POC currency, {@code "COP"}
     * @return a fully-populated event matching the v1 contract
     */
    public static WalletCreated of(String walletId, String ownerId, String currency) {
        return new WalletCreated(EVENT_TYPE, EVENT_VERSION, walletId, ownerId, currency);
    }
}
