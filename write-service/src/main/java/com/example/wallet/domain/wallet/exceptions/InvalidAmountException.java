package com.example.wallet.domain.wallet.exceptions;

import java.math.BigDecimal;

/**
 * Raised when a monetary amount fails a basic validity rule: a missing amount, or an amount that
 * is not strictly greater than zero (deposit/withdraw require {@code amount > 0}).
 *
 * <p>Task 4.2 maps this to <strong>HTTP 400 Bad Request</strong> (requirements 2.2, 3.2).</p>
 */
public final class InvalidAmountException extends WalletDomainException {

    private InvalidAmountException(String message) {
        super(message);
    }

    /**
     * @return an exception for an amount that is {@code null} or not strictly positive
     * @param amount the offending amount ({@code null} allowed)
     */
    public static InvalidAmountException notPositive(BigDecimal amount) {
        return new InvalidAmountException("Amount must be greater than 0 but was: " + amount);
    }
}
