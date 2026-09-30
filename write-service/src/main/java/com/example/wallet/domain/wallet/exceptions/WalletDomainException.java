package com.example.wallet.domain.wallet.exceptions;

/**
 * Base type for every wallet domain-rule violation raised while handling a command.
 *
 * <p>These exceptions express <em>business</em> outcomes (a rule was violated), never transport
 * concerns. They deliberately carry <strong>no HTTP status</strong>: mapping a domain violation to
 * an HTTP status code (400/404/409/422) is the sole responsibility of the central
 * {@code @RestControllerAdvice} added in task 4.2. Keeping the mapping out of the domain lets the
 * same exceptions be reused from tests and any future transport without duplicating status logic.</p>
 *
 * <p>The hierarchy is {@code sealed} so task 4.2 can map it exhaustively (e.g. with a pattern
 * {@code switch}) and the compiler flags any new variant that is not yet mapped. Intended mapping:</p>
 * <ul>
 *   <li>{@link InvalidCommandException} — missing/empty required field or invalid currency on create → <strong>400</strong></li>
 *   <li>{@link InvalidAmountException} — {@code amount <= 0} or missing amount → <strong>400</strong></li>
 *   <li>{@link WalletNotFoundException} — wallet does not exist → <strong>404</strong></li>
 *   <li>{@link DuplicateWalletException} — wallet already exists → <strong>409</strong></li>
 *   <li>{@link InsufficientFundsException} — withdrawal exceeds balance → <strong>409</strong></li>
 *   <li>{@link SelfTransferException} — transfer source equals target → <strong>409</strong></li>
 *   <li>{@link DuplicateTransferException} — transferId already processed → <strong>409</strong></li>
 *   <li>{@link CurrencyMismatchException} — command currency differs from wallet currency → <strong>422</strong></li>
 * </ul>
 */
public sealed abstract class WalletDomainException
        extends RuntimeException
        permits InvalidCommandException,
                DuplicateWalletException,
                WalletNotFoundException,
                InvalidAmountException,
                CurrencyMismatchException,
                InsufficientFundsException,
                SelfTransferException,
                DuplicateTransferException {

    /**
     * @param message a human-readable description of the violated rule (safe for logs/diagnostics)
     */
    protected WalletDomainException(String message) {
        super(message);
    }
}
