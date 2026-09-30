package com.example.wallet.domain.wallet.exceptions;

/**
 * Raised when a command (deposit/withdraw) targets a {@code walletId} that has never been created.
 *
 * <p>Task 4.2 maps this to <strong>HTTP 404 Not Found</strong> (requirements 2.3, 3.3).</p>
 */
public final class WalletNotFoundException extends WalletDomainException {

    /**
     * @param walletId the wallet identifier that does not exist
     */
    public WalletNotFoundException(String walletId) {
        super("Wallet not found: " + walletId);
    }
}
