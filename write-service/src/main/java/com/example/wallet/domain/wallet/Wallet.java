package com.example.wallet.domain.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

import com.example.wallet.domain.wallet.commands.CreateWallet;
import com.example.wallet.domain.wallet.events.MoneyDeposited;
import com.example.wallet.domain.wallet.events.MoneyTransferred;
import com.example.wallet.domain.wallet.events.MoneyWithdrawn;
import com.example.wallet.domain.wallet.events.WalletCreated;
import com.example.wallet.domain.wallet.exceptions.InvalidCommandException;

/**
 * Event-sourced <strong>wallet</strong> entity and <strong>creation</strong> command handler,
 * following the official Axon Framework 5 Spring Boot 4 sample pattern for entity creation (slice
 * {@code createcourse}, class {@code CourseCreation} in {@code examples/university-java-springboot-4}).
 * This is <em>Pattern A</em>: a {@link EventSourced} entity owning a <strong>static</strong>
 * {@link CommandHandler} for the creation command plus an {@link EntityCreator} constructor that
 * takes the creation event.
 *
 * <h2>Why a static creation handler (and why duplicate rejection is handled at the API edge)</h2>
 * <p>The static creation handler takes only the command and an {@link EventAppender}; it does NOT
 * {@code @InjectEntity}, so Axon never event-sources a not-yet-existing wallet for {@code
 * CreateWallet}. This is what reliably avoids {@code EntityNotFoundException} on the first command
 * — a problem that reappears if creation is modeled with an injected state entity, because the
 * convention-based {@code @InjectEntity} loader treats "zero events" as a missing entity.</p>
 *
 * <p>Because a static handler cannot read prior state, it cannot itself reject a duplicate. The
 * duplicate rule is still enforced authoritatively: when a {@code CreateWallet} targets a
 * {@code walletId} that already has events, Axon Server's creational consistency check rejects the
 * append and the framework raises {@code EntityAlreadyExistsForCreationalCommandHandlerException}.
 * The write-side {@code GlobalExceptionHandler} translates that into the domain-meaningful
 * {@code DuplicateWalletException} semantics and returns <strong>HTTP 409 Conflict</strong> with a
 * clean message — never a 500. This also covers the concurrent double-create race for free, since
 * the consistency check is evaluated at append time on the single wallet tag stream.</p>
 *
 * <h2>Monetary arithmetic</h2>
 * <p>All money is {@link BigDecimal} at {@link #MONEY_SCALE scale 2} using
 * {@link RoundingMode#HALF_UP}, matching the read model's {@code NUMERIC(19,2)} column.</p>
 */
@EventSourced(tagKey = Wallet.TAG_KEY, idType = String.class)
public class Wallet {

    /** Event tag key correlating all events of a single wallet (the wallet identifier). */
    public static final String TAG_KEY = "walletId";

    /** Scale applied to every monetary {@link BigDecimal} (mirrors {@code NUMERIC(19,2)}). */
    public static final int MONEY_SCALE = 2;

    /** The fixed currency of the whole POC (Colombian Peso). */
    private static final String SUPPORTED_CURRENCY = "COP";

    /** Rounding mode applied whenever a monetary value is normalized to {@link #MONEY_SCALE}. */
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    /** Zero balance normalized to the money scale — the balance right after {@code WalletCreated}. */
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);

    private boolean created;
    private String walletId;
    private String ownerId;
    private String currency;
    private BigDecimal balance;

    /**
     * Static creation command handler for {@link CreateWallet} (Pattern A). Validates non-blank
     * {@code walletId}/{@code ownerId} and the fixed {@code COP} currency, then appends exactly one
     * {@link WalletCreated}. Takes no {@code @InjectEntity} parameter, so no wallet is event-sourced
     * for a create — this avoids {@code EntityNotFoundException} on the first command (requirements
     * 1.1-1.4). A duplicate {@code CreateWallet} is rejected by Axon Server''s creational
     * consistency check on append and mapped to HTTP 409 by {@code GlobalExceptionHandler}.
     *
     * @param command  the create-wallet intent
     * @param appender appends the resulting {@link WalletCreated} into the current unit of work
     */
    @CommandHandler
    public static void handle(CreateWallet command, EventAppender appender) {
        requireNonBlank(command.walletId(), "walletId");
        requireNonBlank(command.ownerId(), "ownerId");
        requireSupportedCurrency(command.currency());
        appender.append(WalletCreated.of(command.walletId(), command.ownerId(), command.currency()));
    }

    /**
     * Entity creator invoked from the {@link WalletCreated} creation event. Initializes identity and
     * a {@code 0.00} balance (requirement 1.4).
     *
     * @param event the wallet creation fact that begins this wallet's event stream
     */
    @EntityCreator
    public Wallet(WalletCreated event) {
        this.created = true;
        this.walletId = event.walletId();
        this.ownerId = event.ownerId();
        this.currency = event.currency();
        this.balance = ZERO_MONEY;
    }

    /**
     * Folds a {@link MoneyDeposited} fact: increases the balance by the deposited amount
     * (requirement 2.5).
     *
     * @param event the deposit fact
     */
    @EventSourcingHandler
    void evolve(MoneyDeposited event) {
        this.balance = normalize(this.balance.add(event.amount()));
    }

    /**
     * Folds a {@link MoneyWithdrawn} fact: decreases the balance by the withdrawn amount
     * (requirement 3.6).
     *
     * @param event the withdrawal fact
     */
    @EventSourcingHandler
    void evolve(MoneyWithdrawn event) {
        this.balance = normalize(this.balance.subtract(event.amount()));
    }

    /**
     * Folds a {@link MoneyTransferred} fact for a wallet participating in the transfer. The event is
     * tagged with both {@code walletId}s, so each side folds only its own delta: source is debited,
     * target is credited (requirements 4.7, 6.1, 13.3).
     *
     * @param event the transfer fact
     */
    @EventSourcingHandler
    void evolve(MoneyTransferred event) {
        if (this.walletId != null && this.walletId.equals(event.sourceWalletId())) {
            this.balance = normalize(this.balance.subtract(event.amount()));
        }
        if (this.walletId != null && this.walletId.equals(event.targetWalletId())) {
            this.balance = normalize(this.balance.add(event.amount()));
        }
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw InvalidCommandException.missingField(fieldName);
        }
    }

    private static void requireSupportedCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw InvalidCommandException.missingField("currency");
        }
        if (!SUPPORTED_CURRENCY.equals(currency)) {
            throw InvalidCommandException.unsupportedCurrency(currency, SUPPORTED_CURRENCY);
        }
    }

    /**
     * @return {@code true} once a {@link WalletCreated} event has been folded (the wallet exists)
     */
    public boolean exists() {
        return created;
    }

    /** @return the wallet identifier. */
    public String walletId() {
        return walletId;
    }

    /** @return the owner identifier. */
    public String ownerId() {
        return ownerId;
    }

    /** @return the wallet currency ({@code "COP"}). */
    public String currency() {
        return currency;
    }

    /** @return the current balance (scale 2). */
    public BigDecimal balance() {
        return balance;
    }
}