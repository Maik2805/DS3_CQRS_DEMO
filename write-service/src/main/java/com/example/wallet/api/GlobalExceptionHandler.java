package com.example.wallet.api;

import java.time.Instant;
import java.util.Map;

import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.wallet.domain.wallet.exceptions.CurrencyMismatchException;
import com.example.wallet.domain.wallet.exceptions.DuplicateTransferException;
import com.example.wallet.domain.wallet.exceptions.DuplicateWalletException;
import com.example.wallet.domain.wallet.exceptions.InsufficientFundsException;
import com.example.wallet.domain.wallet.exceptions.InvalidAmountException;
import com.example.wallet.domain.wallet.exceptions.InvalidCommandException;
import com.example.wallet.domain.wallet.exceptions.SelfTransferException;
import com.example.wallet.domain.wallet.exceptions.WalletDomainException;
import com.example.wallet.domain.wallet.exceptions.WalletNotFoundException;

/**
 * Central HTTP error mapping for the write-side REST API (task 4.2).
 *
 * <p>A single {@link RestControllerAdvice} that maps <em>every</em> way a wallet request can fail
 * to a consistent HTTP status and a clean JSON body, applied uniformly across all endpoints
 * (create / deposit / withdraw today; the transfer endpoint added in task 5.3 is covered
 * automatically because the mapping is keyed on the exception <em>type</em>, never on the
 * endpoint). Requirements 1.2, 1.3, 2.2, 2.3, 2.4, 3.2, 3.3, 3.4, 3.5, 16.1, 16.2.</p>
 *
 * <h2>Mapping (design "HTTP error mapping" table + {@code WalletDomainException} Javadoc)</h2>
 * <table>
 *   <caption>Domain violation → HTTP status</caption>
 *   <tr><th>Exception</th><th>Status</th></tr>
 *   <tr><td>{@link InvalidCommandException} (missing/empty field, unsupported currency on create)</td><td>400</td></tr>
 *   <tr><td>{@link InvalidAmountException} (amount ≤ 0 or missing)</td><td>400</td></tr>
 *   <tr><td>{@link WalletNotFoundException}</td><td>404</td></tr>
 *   <tr><td>{@link DuplicateWalletException}</td><td>409</td></tr>
 *   <tr><td>{@link InsufficientFundsException}</td><td>409</td></tr>
 *   <tr><td>{@link SelfTransferException}</td><td>409</td></tr>
 *   <tr><td>{@link DuplicateTransferException} (transferId already processed)</td><td>409</td></tr>
 *   <tr><td>{@link CurrencyMismatchException} (semantic violation)</td><td>422</td></tr>
 * </table>
 * <p>Malformed / unreadable JSON ({@link HttpMessageNotReadableException}) → 400 (requirement 16.1).
 * Any exception that is not a recognised domain violation → 500 with a generic message; the real
 * detail is logged, never returned (no stack traces leak to clients).</p>
 *
 * <h2>Axon Framework 5.3.2 command-failure propagation (verified)</h2>
 * <p>Commands are dispatched via {@code CommandGateway.sendAndWait(...)}. Two shapes of failure can
 * reach this advice, both handled:</p>
 * <ul>
 *   <li><strong>Unwrapped.</strong> Wallet rule violations are unchecked
 *       {@link WalletDomainException} subtypes. Axon only wraps <em>checked</em> exceptions, so for
 *       the in-process command handler these surface directly and are mapped by
 *       {@link #handleWalletDomainException(WalletDomainException)}.</li>
 *   <li><strong>Wrapped.</strong> When a failure crosses the messaging infrastructure it may be
 *       surfaced as an {@code org.axonframework.messaging.commandhandling.CommandExecutionException}
 *       (Axon 5.3.2; extends {@code org.axonframework.messaging.core.HandlerExecutionException}).
 *       {@link #handleCommandExecutionException(CommandExecutionException)} walks the standard
 *       {@link Throwable#getCause()} chain: if a {@link WalletDomainException} is found it is mapped
 *       per the table above; otherwise the failure is treated as a 500.</li>
 * </ul>
 * <p>The {@code CommandExecutionException} type/package and its {@code getCause()} accessor were
 * verified against the Axon Framework {@code axon-5.3.2} source tag
 * ({@code messaging/src/main/java/org/axonframework/messaging/commandhandling/CommandExecutionException.java}
 * and its superclass {@code messaging/.../messaging/core/HandlerExecutionException.java}). The
 * original throwable is carried as the standard exception {@code cause}; the additional
 * {@code getDetails(...)} accessors on {@code HandlerExecutionException} carry app-specific detail
 * objects and are not needed here because the concrete domain exception is available directly on
 * the cause chain.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maps a wallet domain violation that propagated <em>unwrapped</em> (in-process command
     * handling) to its HTTP status and a clean JSON body.
     *
     * @param exception the domain-rule violation
     * @return the mapped error response (400 / 404 / 409 / 422)
     */
    @ExceptionHandler(WalletDomainException.class)
    public ResponseEntity<Map<String, Object>> handleWalletDomainException(WalletDomainException exception) {
        return toResponse(statusFor(exception), exception.getMessage());
    }

    /**
     * Maps an Axon {@link CommandExecutionException} by unwrapping its cause chain. If a
     * {@link WalletDomainException} is found it is mapped per the domain table; any other cause is
     * an unexpected server-side failure and becomes a 500 with a generic message (detail logged).
     *
     * @param exception the wrapping command-execution exception surfaced by {@code sendAndWait}
     * @return the mapped error response
     */
    @ExceptionHandler(CommandExecutionException.class)
    public ResponseEntity<Map<String, Object>> handleCommandExecutionException(CommandExecutionException exception) {
        WalletDomainException domain = findDomainCause(exception);
        if (domain != null) {
            return toResponse(statusFor(domain), domain.getMessage());
        }
        return handleUnexpected(exception);
    }

    /**
     * Maps a malformed / unreadable request body (invalid JSON, wrong types Jackson cannot bind) to
     * {@code 400 Bad Request} (requirement 16.1). The parser detail is not echoed to the client.
     *
     * @param exception the body-not-readable failure raised by Spring's message converters
     * @return a 400 error response with a generic "malformed request body" message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.warn("Rejected malformed request body: {}", exception.getMessage());
        return toResponse(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body");
    }

    /**
     * Catch-all for anything not recognised above. Never leaks internals: the detail is logged and
     * the client receives a generic {@code 500 Internal Server Error} body.
     *
     * @param exception the unexpected exception
     * @return a 500 error response with a generic message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
        log.error("Unhandled exception while processing request", exception);
        return toResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    /**
     * Exhaustive mapping over the {@code sealed} {@link WalletDomainException} hierarchy. Because the
     * hierarchy is sealed, the compiler enforces that this switch covers every variant; adding a new
     * domain exception without mapping it here is a compile error.
     *
     * @param exception the domain violation to classify
     * @return the HTTP status for the given violation
     */
    private static HttpStatus statusFor(WalletDomainException exception) {
        return switch (exception) {
            case InvalidCommandException ignored -> HttpStatus.BAD_REQUEST;      // 400
            case InvalidAmountException ignored -> HttpStatus.BAD_REQUEST;       // 400
            case WalletNotFoundException ignored -> HttpStatus.NOT_FOUND;        // 404
            case DuplicateWalletException ignored -> HttpStatus.CONFLICT;        // 409
            case InsufficientFundsException ignored -> HttpStatus.CONFLICT;      // 409
            case SelfTransferException ignored -> HttpStatus.CONFLICT;           // 409
            case DuplicateTransferException ignored -> HttpStatus.CONFLICT;      // 409
            case CurrencyMismatchException ignored -> HttpStatus.UNPROCESSABLE_CONTENT; // 422
        };
    }

    /**
     * Walks the standard {@link Throwable#getCause()} chain looking for the first
     * {@link WalletDomainException}, so a domain violation wrapped by Axon (or any intermediate
     * exception) is still mapped correctly.
     *
     * @param throwable the exception whose cause chain to inspect (may be {@code null})
     * @return the first {@link WalletDomainException} in the chain, or {@code null} if none
     */
    private static WalletDomainException findDomainCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WalletDomainException domain) {
                return domain;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Builds the clean JSON error body {@code { timestamp, status, error, message }}. No stack trace
     * or internal type information is ever included.
     *
     * @param status  the HTTP status to return
     * @param message a safe, human-readable description of the failure
     * @return the response entity with the given status and JSON body
     */
    private static ResponseEntity<Map<String, Object>> toResponse(HttpStatus status, String message) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? status.getReasonPhrase() : message);
        return ResponseEntity.status(status).body(body);
    }
}
