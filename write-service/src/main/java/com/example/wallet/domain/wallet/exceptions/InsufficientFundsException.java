package com.example.wallet.domain.wallet.exceptions;

import java.math.BigDecimal;

/**
 * Raised when a withdrawal would drive the balance negative (requested {@code amount} exceeds the
 * current wallet balance).
 *
 * <p>Business conflict. Task 4.2 maps this to <strong>HTTP 409 Conflict</strong>
 * (requirement 3.4).</p>
 */
public final class InsufficientFundsException extends WalletDomainException {

    /**
     * @param walletId  the wallet with insufficient funds
     * @param balance   the current balance
     * @param requested the amount that could not be withdrawn
     */
    public InsufficientFundsException(String walletId, BigDecimal balance, BigDecimal requested) {
        super("Insufficient funds in wallet " + walletId
                + ": balance is " + balance + " but withdrawal requested " + requested);
    }
}
