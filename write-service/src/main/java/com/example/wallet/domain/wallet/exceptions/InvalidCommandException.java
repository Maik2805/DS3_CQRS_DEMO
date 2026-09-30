package com.example.wallet.domain.wallet.exceptions;

/**
 * Raised for a malformed/invalid command that is not specifically about an amount: a missing or
 * empty required identifier ({@code walletId}, {@code ownerId}), a missing currency, or a currency
 * that is not the fixed POC currency {@code "COP"} on wallet creation.
 *
 * <p>Task 4.2 maps this to <strong>HTTP 400 Bad Request</strong> (requirements 1.3, 2.2, 3.2).
 * Note: a wrong currency on <em>create</em> is a 400 (invalid payload for the fixed-currency POC),
 * whereas a currency that differs from an <em>existing</em> wallet's currency on deposit/withdraw
 * is a semantic {@link CurrencyMismatchException} (422).</p>
 */
public final class InvalidCommandException extends WalletDomainException {

    private InvalidCommandException(String message) {
        super(message);
    }

    /**
     * @return an exception for a required string field that is {@code null} or blank
     * @param fieldName the name of the missing/empty field
     */
    public static InvalidCommandException missingField(String fieldName) {
        return new InvalidCommandException("Required field is missing or empty: " + fieldName);
    }

    /**
     * @return an exception for a create command whose currency is not the fixed POC currency
     * @param currency        the currency supplied by the command ({@code null} allowed)
     * @param requiredCurrency the required fixed currency ({@code "COP"})
     */
    public static InvalidCommandException unsupportedCurrency(String currency, String requiredCurrency) {
        return new InvalidCommandException(
                "Unsupported currency: " + currency + " (only " + requiredCurrency + " is supported)");
    }
}
