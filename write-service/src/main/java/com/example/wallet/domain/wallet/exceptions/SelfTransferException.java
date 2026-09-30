package com.example.wallet.domain.wallet.exceptions;

/**
 * Raised when a transfer names the same wallet as both source and target
 * ({@code sourceWalletId.equals(targetWalletId)}), which is not a meaningful money movement.
 *
 * <p>Business conflict. Task 4.2 / 5.2 maps this to <strong>HTTP 409 Conflict</strong>
 * (requirement 4.2).</p>
 */
public final class SelfTransferException extends WalletDomainException {

    /**
     * @param walletId the wallet named as both source and target of the transfer
     */
    public SelfTransferException(String walletId) {
        super("Self-transfer is not allowed: source and target are the same wallet " + walletId);
    }
}
