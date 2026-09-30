package com.example.wallet.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

import com.example.wallet.domain.wallet.Wallet;
import com.example.wallet.domain.wallet.commands.DepositMoney;
import com.example.wallet.domain.wallet.commands.WithdrawMoney;
import com.example.wallet.domain.wallet.events.MoneyDeposited;
import com.example.wallet.domain.wallet.events.MoneyTransferred;
import com.example.wallet.domain.wallet.events.MoneyWithdrawn;
import com.example.wallet.domain.wallet.events.WalletCreated;
import com.example.wallet.domain.wallet.exceptions.CurrencyMismatchException;
import com.example.wallet.domain.wallet.exceptions.InsufficientFundsException;
import com.example.wallet.domain.wallet.exceptions.InvalidAmountException;
import com.example.wallet.domain.wallet.exceptions.WalletNotFoundException;

/**
 * Deposit / withdraw command handler, following the official Axon Framework 5 Spring Boot 4 sample
 * <em>Pattern B</em> (slice {@code changecoursecapacity}, class
 * {@code ChangeCourseCapacityCommandHandler} in {@code examples/university-java-springboot-4}):
 * a Spring {@code @Component} whose {@code @CommandHandler} methods take the command, one
 * {@code @InjectEntity} state, and an {@link EventAppender}; the state is a <em>nested static</em>
 * entity annotated with the Spring stereotype {@link EventSourced}, with a no-arg
 * {@link EntityCreator} and {@code void} {@link EventSourcingHandler} fold methods.
 *
 * <h2>Entity injection by convention (verified)</h2>
 * <p>{@code @InjectEntity} carries <strong>no</strong> {@code idProperty} (mirroring the sample).
 * Axon binds the entity by the command property whose type matches the state's {@code idType}
 * ({@code String}): the command's {@code @TargetEntityId String walletId()} accessor. Because
 * {@link WalletState} has a no-arg {@link EntityCreator}, a not-yet-created wallet is injected as a
 * non-{@code null} instance with {@code created == false}, so deposits/withdrawals against a
 * missing wallet are rejected with {@link WalletNotFoundException} (HTTP 404).</p>
 *
 * <h2>Why a separate {@code WalletState} (not the {@code Wallet} creation entity)</h2>
 * <p>The sample keeps creation ({@code CourseCreation}, static handler, event-taking
 * {@code @EntityCreator}) separate from mutation ({@code ChangeCourseCapacityCommandHandler.State},
 * no-arg {@code @EntityCreator}). We mirror that: {@link Wallet} owns creation; this handler owns a
 * nested {@link WalletState} for the deposit/withdraw reads. Both are registered on the same
 * {@code walletId} tag stream via the same {@code tagKey}/{@code idType}, exactly as the sample
 * registers {@code CourseCreation} and {@code State} on the {@code courseId} tag.</p>
 */
@Component
public class WalletCommandHandler {

    /**
     * Handles {@link DepositMoney}: validates the wallet exists, {@code amount > 0}, and matching
     * currency; on success appends exactly one {@link MoneyDeposited} (requirements 2.1–2.5).
     *
     * @param command       the deposit intent
     * @param state         the wallet state event-sourced for {@code command.walletId()}
     * @param eventAppender appends the resulting event into the current unit of work
     */
    @CommandHandler
    void handle(DepositMoney command, @InjectEntity WalletState state, EventAppender eventAppender) {
        eventAppender.append(decideDeposit(command, state));
    }

    /**
     * Handles {@link WithdrawMoney}: validates the wallet exists, {@code amount > 0}, matching
     * currency, and sufficient funds; on success appends exactly one {@link MoneyWithdrawn}
     * (requirements 3.1–3.6).
     *
     * @param command       the withdrawal intent
     * @param state         the wallet state event-sourced for {@code command.walletId()}
     * @param eventAppender appends the resulting event into the current unit of work
     */
    @CommandHandler
    void handle(WithdrawMoney command, @InjectEntity WalletState state, EventAppender eventAppender) {
        eventAppender.append(decideWithdraw(command, state));
    }

    // --- Pure decision functions (no side effects; return the single event to append) ---------

    private List<MoneyDeposited> decideDeposit(DepositMoney command, WalletState state) {
        requireExistingWallet(state, command.walletId());
        requirePositiveAmount(command.amount());
        requireMatchingCurrency(state, command.currency());
        return List.of(MoneyDeposited.of(
                newEventId(), command.walletId(), command.amount(), command.currency(), now()));
    }

    private List<MoneyWithdrawn> decideWithdraw(WithdrawMoney command, WalletState state) {
        requireExistingWallet(state, command.walletId());
        requirePositiveAmount(command.amount());
        requireMatchingCurrency(state, command.currency());
        requireSufficientFunds(state, command.amount());
        return List.of(MoneyWithdrawn.of(
                newEventId(), command.walletId(), command.amount(), command.currency(), now()));
    }

    // --- Validation helpers -------------------------------------------------------------------

    private static void requireExistingWallet(WalletState state, String walletId) {
        if (!state.created) {
            throw new WalletNotFoundException(walletId);
        }
    }

    private static void requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw InvalidAmountException.notPositive(amount);
        }
    }

    private static void requireMatchingCurrency(WalletState state, String commandCurrency) {
        if (!state.currency.equals(commandCurrency)) {
            throw new CurrencyMismatchException(state.walletId, state.currency, commandCurrency);
        }
    }

    private static void requireSufficientFunds(WalletState state, BigDecimal amount) {
        if (state.balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(state.walletId, state.balance, amount);
        }
    }

    private static String newEventId() {
        return UUID.randomUUID().toString();
    }

    private static Instant now() {
        return Instant.now();
    }

    /**
     * Nested, event-sourced state for the deposit/withdraw slice (Pattern B). Reconstructs
     * {@code created}, {@code currency} and {@code balance} purely from the wallet's event stream;
     * never reads PostgreSQL. Registered on the {@code walletId} tag stream with a {@code String}
     * id, matching the {@link Wallet} creation entity.
     */
    @EventSourced(tagKey = Wallet.TAG_KEY, idType = String.class)
    static class WalletState {

        private static final int MONEY_SCALE = Wallet.MONEY_SCALE;
        private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
        private static final BigDecimal ZERO_MONEY =
                BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);

        private boolean created = false;
        private String walletId;
        private String currency;
        private BigDecimal balance = ZERO_MONEY;

        @EntityCreator
        public WalletState() {
        }

        @EventSourcingHandler
        void evolve(WalletCreated event) {
            this.created = true;
            this.walletId = event.walletId();
            this.currency = event.currency();
            this.balance = ZERO_MONEY;
        }

        @EventSourcingHandler
        void evolve(MoneyDeposited event) {
            this.balance = normalize(this.balance.add(event.amount()));
        }

        @EventSourcingHandler
        void evolve(MoneyWithdrawn event) {
            this.balance = normalize(this.balance.subtract(event.amount()));
        }

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
    }
}
