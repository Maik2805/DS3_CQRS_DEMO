package com.example.wallet.domain.wallet.exceptions;

/**
 * Raised when a {@code CreateWallet} command targets a {@code walletId} that already exists
 * (a wallet cannot be created twice).
 *
 * <p>Business conflict. Task 4.2 maps this to <strong>HTTP 409 Conflict</strong>
 * (requirement 1.2).</p>
 */
public final class DuplicateWalletException extends WalletDomainException {

    /**
     * @param walletId the already-existing wallet identifier that was requested again
     */
    public DuplicateWalletException(String walletId) {
        super("Wallet already exists: " + walletId);
    }
}
