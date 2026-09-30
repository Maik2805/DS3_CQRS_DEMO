package com.example.wallet.domain.wallet.exceptions;

/**
 * Raised when a {@code TransferMoney} command carries a {@code transferId} that has already
 * been processed (a prior {@code MoneyTransferred} fact with the same {@code transferId} already
 * exists in the event store).
 *
 * <p>This is the Write Side transfer idempotency guard (requirements 4.8, 5.3, 5.4): a retried
 * transfer must never move funds a second time. Duplicate detection happens <em>inside the
 * Dynamic Consistency Boundary</em> by sourcing any prior {@code MoneyTransferred} tagged with the
 * same {@code transferId} — never a PostgreSQL lookup (requirement 13.2). When a duplicate is
 * detected the handler appends <strong>no</strong> new event.</p>
 *
 * <p>Business conflict. The central {@code @RestControllerAdvice} (task 4.2) maps this to
 * <strong>HTTP 409 Conflict</strong> (requirement 4.8).</p>
 */
public final class DuplicateTransferException extends WalletDomainException {

    /**
     * @param transferId the already-processed transfer identifier that was presented again
     */
    public DuplicateTransferException(String transferId) {
        super("Transfer already processed for transferId " + transferId
                + "; no funds moved a second time");
    }
}
