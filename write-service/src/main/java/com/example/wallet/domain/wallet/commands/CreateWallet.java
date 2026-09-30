package com.example.wallet.domain.wallet.commands;

import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Command expressing intent to create a new wallet.
 *
 * <p>Identifiers are free-form, non-empty strings (never UUID-formatted for the POC).
 * The currency is fixed to {@code "COP"} for the whole POC; validation of these rules
 * happens in the command handler (task 3.3), not here.</p>
 *
 * <p>{@code walletId} is the routing key: it selects the target {@code Wallet} entity the
 * command is dispatched to. In Axon Framework 5 the command routing key is marked with
 * {@link TargetEntityId} (from {@code org.axonframework.modelling.annotation}), the
 * successor to the legacy {@code @TargetAggregateIdentifier}.</p>
 *
 * @param walletId free-form, non-empty target wallet identifier (routing key)
 * @param ownerId  free-form, non-empty owner identifier
 * @param currency fixed POC currency, expected to be {@code "COP"}
 */
public record CreateWallet(
        @TargetEntityId String walletId,
        String ownerId,
        String currency
) {
}
