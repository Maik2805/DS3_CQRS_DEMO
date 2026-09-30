package com.example.wallet.domain.wallet.commands;

import java.math.BigDecimal;

import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Command expressing intent to withdraw money from an existing wallet.
 *
 * <p>Monetary amounts always use {@link BigDecimal} (never {@code double}/{@code float}).
 * Domain-rule validation (amount &gt; 0, wallet exists, currency matches, balance &ge; amount)
 * is performed by the command handler (task 3.3).</p>
 *
 * <p>{@code walletId} is the routing key selecting the target {@code Wallet} entity.</p>
 *
 * @param walletId free-form, non-empty target wallet identifier (routing key)
 * @param amount   withdrawal amount, expected to be strictly greater than zero
 * @param currency fixed POC currency, expected to be {@code "COP"}
 */
public record WithdrawMoney(
        @TargetEntityId String walletId,
        BigDecimal amount,
        String currency
) {
}
