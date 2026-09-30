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
 * Event-sourced <strong>creation</strong> entity for a wallet, following the official Axon
 * Framework 5 Spring Boot 4 sample pattern for entity creation (slice {@code createcourse},
 * class {@code CourseCreation} in {@code AxonIQ/AxonFramework} →
 * {@code examples/university-java-springboot-4}). This is <em>Pattern A</em> from that sample:
 * an entity annotated with the Spring stereotype {@link EventSourced} that owns a
 * <strong>static</strong> {@link CommandHandler} for the creation command plus an
 * {@link EntityCreator} constructor that takes the <em>creation event</em>.
 *
 * <h2>Why this fixes the {@code EntityNotFoundException}</h2>
 * <p>The static creation {@link CommandHandler} takes only the command and an {@link EventAppender}
 * — it does <strong>not</strong> take an {@code @InjectEntity} parameter, so Axon never tries to
 * load (event-source) a not-yet-existing wallet for {@code CreateWallet}. That is exactly why the
 * sample's create path never throws {@code EntityNotFoundException}. Deposit/withdraw and transfer
 * live in separate {@code @Component} handlers with their own nested state entities
 * ({@code WalletCommandHandler.WalletState}, {@code TransferCommandHandler.TransferState}).</p>
 *
 * <h2>Duplicate-wallet handling (deviation, documented)</h2>
 * <p>A static creation handler cannot see prior wallet state, so it cannot reject a duplicate
 * {@code CreateWallet} the way the old code did. Matching the sample ({@code CourseCreation.handle}
 * does not guard for pre-existence), this handler always appends {@link WalletCreated} on valid
 * input. Under Axon Server the append is still consistency-checked on the wallet's tag stream, but
 * an explicit {@code DuplicateWalletException} (HTTP 409) is no longer raised from creation. This
 * is the documented deviation requested when adopting the sample pattern.</p>
 *
 * <h2>Annotations (verified against the sample)</h2>
 * <ul>
 *   <li>{@link EventSourced} — {@code org.axonframework.extension.spring.stereotype.EventSourced}
 *       (Spring stereotype, auto-detected — no programmatic registration). {@code tagKey} is the
 *       wallet id tag; {@code idType} is {@code String} (free-form POC ids).</li>
 *   <li>{@link CommandHandler} — {@code org.axonframework.messaging.commandhandling.annotation.CommandHandler},
 *       here a {@code static} method for the creation command.</li>
 *   <li>{@link EntityCreator} — {@code org.axonframework.eventsourcing.annotation.reflection.EntityCreator},
 *       a constructor that takes the {@link WalletCreated} creation event.</li>
 *   <li>{@link EventSourcingHandler} — {@code org.axonframework.eventsourcing.annotation.EventSourcingHandler},
 *       {@code void} fold methods that evolve balance as later events are applied.</li>
 * </ul>
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
     * Static creation command handler for {@link CreateWallet} (Pattern A, sample
     * {@code CourseCreation.handle}). Validates non-blank {@code walletId}/{@code ownerId} and the
     * fixed {@code COP} currency, then appends exactly one {@link WalletCreated} event. Takes no
     * {@code @InjectEntity} parameter, so no wallet is event-sourced for a create — this is what
     * avoids {@code EntityNotFoundException} on the first command (requirements 1.1–1.4).
     *
     * @param command  the create-wallet intent
     * @param appender appends the resulting {@link WalletCreated} into the current unit of work
     */
    @CommandHandler
    public static void handle(CreateWallet command, EventAppender appender) {
        requireNonBlank(command.walletId(), "walletId");
        requireNonBlank(command.ownerId(), "ownerId");
        requireSupportedCurrency(command.currency());
        // NOTE (deviation): matching the sample's CourseCreation.handle, we do NOT guard duplicate
        // creation here — a static creation handler cannot read prior state. Always append on valid
        // input; Axon Server still consistency-checks the append against the wallet's tag stream.
        appender.append(WalletCreated.of(command.walletId(), command.ownerId(), command.currency()));
    }

    /**
     * Entity creator invoked from the {@link WalletCreated} creation event (Pattern A). Initializes
     * identity and a {@code 0.00} balance (requirement 1.4).
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
     * (requirement 2.5). Returns {@code void} per the sample's fold shape.
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
