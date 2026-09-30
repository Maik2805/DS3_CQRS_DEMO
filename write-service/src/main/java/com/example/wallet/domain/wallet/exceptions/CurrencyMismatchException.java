package com.example.wallet.domain.wallet.exceptions;

/**
 * Raised when a command's {@code currency} differs from the wallet's currency (a semantic
 * violation rather than a malformed request).
 *
 * <p>Task 4.2 maps this to <strong>HTTP 422 Unprocessable Entity</strong>
 * (requirements 2.4, 3.5).</p>
 */
public final class CurrencyMismatchException extends WalletDomainException {

    /**
     * @param walletId        the wallet whose currency was violated
     * @param walletCurrency  the wallet's actual currency
     * @param commandCurrency the currency supplied by the command
     */
    public CurrencyMismatchException(String walletId, String walletCurrency, String commandCurrency) {
        super("Currency mismatch for wallet " + walletId
                + ": wallet currency is " + walletCurrency
                + " but command specified " + commandCurrency);
    }
}
