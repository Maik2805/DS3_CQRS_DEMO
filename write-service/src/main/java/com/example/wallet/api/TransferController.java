package com.example.wallet.api;

import java.math.BigDecimal;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.wallet.domain.wallet.commands.TransferMoney;

/**
 * Write-side REST controller for the money-transfer slice (task 5.3).
 *
 * <table>
 *   <caption>Endpoint handled here</caption>
 *   <tr><th>Endpoint</th><th>Command</th><th>Success</th></tr>
 *   <tr><td>{@code POST /api/transfers}</td><td>{@link TransferMoney}</td><td>200 OK</td></tr>
 * </table>
 *
 * <p>Split into its own controller (rather than {@code WalletController}) because the resource is a
 * transfer, not a single wallet — it is not nested under {@code /api/wallets/{walletId}}. Kept thin
 * and consistent with {@code WalletController}: translate HTTP to a command, dispatch through the
 * gateway, and let the central {@code @RestControllerAdvice} (task 4.2) map any domain exception to
 * its HTTP status.</p>
 *
 * <h2>{@code Idempotency-Key} header precedence (requirement 5.2)</h2>
 * <p>{@code transferId} is the Write Side idempotency key. Per requirement 5.2, when the request
 * carries an {@code Idempotency-Key} HTTP header its value <strong>is</strong> the {@code transferId}
 * used for duplicate detection, so the header takes precedence over any {@code transferId} in the
 * body:</p>
 * <ul>
 *   <li><strong>Header present and non-blank</strong> → the header value is used as the
 *       {@code transferId} (the body {@code transferId}, if any, is ignored). This lets a client
 *       retry the exact same HTTP call — same header — and be recognised as a duplicate (→ 409),
 *       without having to thread the id through the body.</li>
 *   <li><strong>Header absent</strong> → the body {@code transferId} is used. It must be non-empty;
 *       the command handler rejects a blank {@code transferId} with HTTP 400 (requirement 5.1).</li>
 * </ul>
 * <p>Recommendation for clients: if both are sent, keep them equal to avoid confusion — the header
 * wins by contract regardless.</p>
 *
 * <h2>Command dispatch and error propagation</h2>
 * <p>The command is dispatched with {@link CommandGateway#sendAndWait(Object)} (same pattern as
 * {@code WalletController}), which blocks until the handler completes so domain exceptions surface
 * synchronously and are mapped by the central advice:</p>
 * <ul>
 *   <li>duplicate {@code transferId} → {@code DuplicateTransferException} → <strong>409</strong>,
 *       no funds moved (requirements 4.8, 5.4);</li>
 *   <li>self-transfer → 409; insufficient funds → 409; missing wallet → 404;
 *       {@code amount <= 0} / blank id → 400; currency mismatch → 422.</li>
 * </ul>
 * <p>On success this responds {@code 200 OK} (the design allows 200/202); no body is returned.</p>
 */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final CommandGateway commandGateway;

    /**
     * @param commandGateway the Axon 5 command gateway (Spring bean from {@code axon-spring-boot-starter})
     */
    public TransferController(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    /**
     * Transfers money between two wallets as a single consistent operation.
     *
     * <p>{@code POST /api/transfers} with body
     * {@code { "transferId", "sourceWalletId", "targetWalletId", "amount", "currency" }} and an
     * optional {@code Idempotency-Key} header. The effective {@code transferId} is the header value
     * when present (requirement 5.2), otherwise the body {@code transferId}. Dispatches
     * {@link TransferMoney}; on success responds {@code 200 OK}.</p>
     *
     * @param idempotencyKey optional {@code Idempotency-Key} header; when present it is used as the
     *                       {@code transferId} (overrides the body value)
     * @param request        the transfer request body
     * @return {@code 200 OK} on success
     */
    @PostMapping
    public ResponseEntity<Void> transfer(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TransferRequest request) {
        String transferId = resolveTransferId(idempotencyKey, request.transferId());
        commandGateway.sendAndWait(
                new TransferMoney(
                        transferId,
                        request.sourceWalletId(),
                        request.targetWalletId(),
                        request.amount(),
                        request.currency()));
        return ResponseEntity.ok().build();
    }

    /**
     * Resolves the effective {@code transferId}: the {@code Idempotency-Key} header wins when it is
     * present and non-blank (requirement 5.2); otherwise the body value is used (and is validated
     * for non-emptiness by the command handler, requirement 5.1).
     *
     * @param idempotencyKey the header value (may be {@code null})
     * @param bodyTransferId the body {@code transferId} (may be {@code null})
     * @return the {@code transferId} to dispatch on the command
     */
    private static String resolveTransferId(String idempotencyKey, String bodyTransferId) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey;
        }
        return bodyTransferId;
    }

    /**
     * Request body for {@code POST /api/transfers}.
     *
     * <p>A dedicated DTO keeps the HTTP contract independent of the {@link TransferMoney} command
     * type and its Axon routing annotations. {@code amount} is a {@link BigDecimal}; Jackson maps a
     * JSON number to {@code BigDecimal} without precision loss, consistent with the {@code NUMERIC(19,2)}
     * read model and the {@code BigDecimal} arithmetic on the write side. {@code transferId} may be
     * omitted when an {@code Idempotency-Key} header is supplied (the header is then authoritative).</p>
     *
     * @param transferId     free-form transfer identifier; ignored when {@code Idempotency-Key} is present
     * @param sourceWalletId free-form source wallet identifier
     * @param targetWalletId free-form target wallet identifier (must differ from source)
     * @param amount         monetary amount as {@link BigDecimal}; validated ({@code > 0}) by the handler
     * @param currency       currency code (expected {@code "COP"}); validated by the command handler
     */
    public record TransferRequest(
            String transferId,
            String sourceWalletId,
            String targetWalletId,
            BigDecimal amount,
            String currency) {
    }
}
