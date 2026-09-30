package com.example.wallet.domain.wallet.commands;

import java.math.BigDecimal;

import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Command expressing intent to transfer money between two distinct wallets as a single business
 * operation, handled with an Axon Framework 5 Dynamic Consistency Boundary (DCB) across the source
 * and target wallets.
 *
 * <h2>Composite id wiring (verified against the sample)</h2>
 * <p>Following {@code SubscribeStudentToCourse} from
 * {@code examples/university-java-springboot-4} (slice {@code subscribestudent}), the multi-entity
 * command exposes a <em>composite</em> id through a {@link TargetEntityId}-annotated accessor
 * ({@link #transferTargetId()} returning a {@link TransferId}). The transfer handler's single
 * {@code @InjectEntity TransferState} is bound to this composite id because
 * {@code TransferState}'s {@code idType} is {@link TransferId}; the state's
 * {@code @EventCriteriaBuilder} then uses the three ids to source both wallet streams and the
 * transfer idempotency stream. The plain record components stay for JSON/command construction.</p>
 *
 * <p>Monetary amounts always use {@link BigDecimal} (never {@code double}/{@code float}).</p>
 *
 * @param transferId     free-form, non-empty transfer identifier (idempotency key)
 * @param sourceWalletId free-form, non-empty source wallet identifier
 * @param targetWalletId free-form, non-empty target wallet identifier (must differ from source)
 * @param amount         transfer amount, expected to be strictly greater than zero
 * @param currency       fixed POC currency, expected to be {@code "COP"}
 */
public record TransferMoney(
        String transferId,
        String sourceWalletId,
        String targetWalletId,
        BigDecimal amount,
        String currency
) {

    /**
     * Composite routing/identity key for the transfer command model. Marked {@link TargetEntityId}
     * so Axon binds the handler's {@code @InjectEntity TransferState} (whose {@code idType} is
     * {@link TransferId}) to this value — mirroring {@code SubscribeStudentToCourse.subscriptionId()}.
     *
     * @return the composite transfer id built from the command's three ids
     */
    @TargetEntityId
    public TransferId transferTargetId() {
        return new TransferId(transferId, sourceWalletId, targetWalletId);
    }
}
