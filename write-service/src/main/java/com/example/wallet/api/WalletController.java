package com.example.wallet.api;

import java.math.BigDecimal;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.wallet.domain.wallet.commands.CreateWallet;
import com.example.wallet.domain.wallet.commands.DepositMoney;
import com.example.wallet.domain.wallet.commands.WithdrawMoney;

/**
 * Write-side REST controller for the wallet create / deposit / withdraw slice (task 4.1).
 *
 * <p>Endpoints (mapped from the design "REST controllers and HTTP error mapping" table):</p>
 * <table>
 *   <caption>Endpoints handled here</caption>
 *   <tr><th>Endpoint</th><th>Command</th><th>Success</th></tr>
 *   <tr><td>{@code POST /api/wallets}</td><td>{@link CreateWallet}</td><td>201 Created</td></tr>
 *   <tr><td>{@code POST /api/wallets/{walletId}/deposits}</td><td>{@link DepositMoney}</td><td>200 OK</td></tr>
 *   <tr><td>{@code POST /api/wallets/{walletId}/withdrawals}</td><td>{@link WithdrawMoney}</td><td>200 OK</td></tr>
 * </table>
 *
 * <p>The transfer endpoint ({@code POST /api/transfers}) lives in {@code TransferController}
 * (task 5.3) because its resource is a transfer, not a single wallet.</p>
 *
 * <h2>Axon Framework 5 command dispatch (verified)</h2>
 * <p>Commands are dispatched through the Axon 5 {@link CommandGateway}
 * ({@code org.axonframework.messaging.commandhandling.gateway.CommandGateway}), injected as a
 * Spring bean provided by {@code axon-spring-boot-starter}. The controller calls
 * {@link CommandGateway#sendAndWait(Object)}, which dispatches the command and blocks until the
 * command handler completes, so any exception thrown while handling the command surfaces
 * synchronously in the controller thread. This API and the constructor-injected-gateway +
 * request-DTO pattern were verified against the official Axon Framework 5 Spring Boot 4 sample
 * {@code AxonIQ/AxonFramework} → {@code examples/university-java-springboot-4} →
 * {@code write/createcourse/CreateCourseController} (which uses
 * {@code commandGateway.sendAndWait(command)} from a {@code @RestController}).</p>
 *
 * <h2>How domain exceptions propagate (boundary with task 4.2)</h2>
 * <p>This controller deliberately does <strong>not</strong> catch or map exceptions — that is the
 * sole responsibility of the central {@code @RestControllerAdvice} added in task 4.2. The
 * propagation contract, verified against the Axon 5 {@code CommandGateway} Javadoc, is:</p>
 * <ul>
 *   <li>{@code sendAndWait} throws when command handling is unsuccessful. Its Javadoc states that
 *       only <em>checked</em> exceptions are wrapped in a
 *       {@code org.axonframework.messaging.commandhandling.CommandExecutionException}.</li>
 *   <li>Every wallet rule violation is a
 *       {@code com.example.wallet.domain.wallet.exceptions.WalletDomainException}, which extends
 *       {@link RuntimeException} (unchecked). It is therefore <em>not</em> wrapped by the gateway
 *       and surfaces directly from {@code sendAndWait} for the local/in-process command handler,
 *       so task 4.2's advice can map the concrete {@code WalletDomainException} subtype to its HTTP
 *       status (400/404/409/422).</li>
 *   <li>When the command is handled remotely (distributed command bus over Axon Server), the
 *       framework may surface the failure as a {@code CommandExecutionException} carrying the
 *       original cause. Task 4.2's advice is responsible for unwrapping that cause and mapping the
 *       underlying {@code WalletDomainException}; this task leaves that mapping to 4.2 and does not
 *       catch here.</li>
 * </ul>
 * <p>Controllers stay thin: they translate HTTP to a command, dispatch it, and — on success —
 * return the mapped status. No validation or business logic lives here.</p>
 */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final CommandGateway commandGateway;

    /**
     * @param commandGateway the Axon 5 command gateway (Spring bean from {@code axon-spring-boot-starter})
     */
    public WalletController(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    /**
     * Creates a wallet.
     *
     * <p>{@code POST /api/wallets} with body {@code { "walletId", "ownerId", "currency" }}.
     * Dispatches {@link CreateWallet} and, on success, responds {@code 201 Created}
     * (requirement 1.1). Rule violations (empty ids, unsupported currency, duplicate wallet)
     * propagate as a {@code WalletDomainException} for task 4.2 to map.</p>
     *
     * @param request the create-wallet request body
     * @return {@code 201 Created} on success
     */
    @PostMapping
    public ResponseEntity<Void> createWallet(@RequestBody CreateWalletRequest request) {
        commandGateway.sendAndWait(
                new CreateWallet(request.walletId(), request.ownerId(), request.currency()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Deposits money into an existing wallet.
     *
     * <p>{@code POST /api/wallets/{walletId}/deposits} with body {@code { "amount", "currency" }}.
     * The {@code walletId} comes from the path; the command is constructed from path + body.
     * Dispatches {@link DepositMoney} and, on success, responds {@code 200 OK} (requirement 2.1).</p>
     *
     * @param walletId the target wallet identifier (from the path)
     * @param request  the deposit request body ({@code amount}, {@code currency})
     * @return {@code 200 OK} on success
     */
    @PostMapping("/{walletId}/deposits")
    public ResponseEntity<Void> deposit(
            @PathVariable String walletId,
            @RequestBody MovementRequest request) {
        commandGateway.sendAndWait(
                new DepositMoney(walletId, request.amount(), request.currency()));
        return ResponseEntity.ok().build();
    }

    /**
     * Withdraws money from an existing wallet.
     *
     * <p>{@code POST /api/wallets/{walletId}/withdrawals} with body {@code { "amount", "currency" }}.
     * The {@code walletId} comes from the path; the command is constructed from path + body.
     * Dispatches {@link WithdrawMoney} and, on success, responds {@code 200 OK} (requirement 3.1).
     * Insufficient funds / currency mismatch / missing wallet propagate for task 4.2 to map.</p>
     *
     * @param walletId the target wallet identifier (from the path)
     * @param request  the withdrawal request body ({@code amount}, {@code currency})
     * @return {@code 200 OK} on success
     */
    @PostMapping("/{walletId}/withdrawals")
    public ResponseEntity<Void> withdraw(
            @PathVariable String walletId,
            @RequestBody MovementRequest request) {
        commandGateway.sendAndWait(
                new WithdrawMoney(walletId, request.amount(), request.currency()));
        return ResponseEntity.ok().build();
    }

    /**
     * Request body for {@code POST /api/wallets}.
     *
     * <p>A dedicated DTO (rather than binding directly to {@link CreateWallet}) keeps the HTTP
     * contract independent of the command type and the Axon routing annotations on the command.</p>
     *
     * @param walletId free-form wallet identifier
     * @param ownerId  free-form owner identifier
     * @param currency currency code (expected {@code "COP"}); validated by the command handler
     */
    public record CreateWalletRequest(String walletId, String ownerId, String currency) {
    }

    /**
     * Request body for deposit and withdrawal endpoints ({@code { "amount", "currency" }}).
     *
     * <p>The target {@code walletId} is carried by the URL path, not this body, so the command is
     * assembled from the path variable plus these fields. {@code amount} is a {@link BigDecimal};
     * Jackson maps a JSON number to {@code BigDecimal} without precision loss, consistent with the
     * {@code NUMERIC(19,2)} read model and the {@code BigDecimal} arithmetic on the write side.</p>
     *
     * @param amount   monetary amount as {@link BigDecimal}; validated ({@code > 0}) by the handler
     * @param currency currency code (expected {@code "COP"}); validated by the command handler
     */
    public record MovementRequest(BigDecimal amount, String currency) {
    }
}
