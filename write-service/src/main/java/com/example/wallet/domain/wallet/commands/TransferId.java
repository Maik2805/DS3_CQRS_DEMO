package com.example.wallet.domain.wallet.commands;

/**
 * Composite identity for a money transfer, mirroring the official Axon Framework 5 Spring Boot 4
 * sample's {@code SubscriptionId} (slice {@code subscribestudent}). In that sample the multi-entity
 * command exposes a composite id via a {@code @TargetEntityId}-annotated method, and the single
 * {@code @InjectEntity State} is bound to it because the state's {@code idType} equals the composite
 * id type. This record plays the same role for {@code TransferMoney}: its type is the
 * {@code idType} of {@code TransferCommandHandler.TransferState}, and it carries the three ids the
 * state's {@code @EventCriteriaBuilder} needs to source both wallet streams and the transfer
 * idempotency stream.
 *
 * @param transferId     free-form, non-empty transfer identifier (idempotency key)
 * @param sourceWalletId free-form, non-empty source wallet identifier
 * @param targetWalletId free-form, non-empty target wallet identifier
 */
public record TransferId(String transferId, String sourceWalletId, String targetWalletId) {
}
