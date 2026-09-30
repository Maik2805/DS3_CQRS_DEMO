package com.example.wallet.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

import com.example.wallet.domain.wallet.Wallet;
import com.example.wallet.domain.wallet.commands.TransferId;
import com.example.wallet.domain.wallet.commands.TransferMoney;
import com.example.wallet.domain.wallet.events.MoneyDeposited;
import com.example.wallet.domain.wallet.events.MoneyTransferred;
import com.example.wallet.domain.wallet.events.MoneyWithdrawn;
import com.example.wallet.domain.wallet.events.WalletCreated;
import com.example.wallet.domain.wallet.exceptions.CurrencyMismatchException;
import com.example.wallet.domain.wallet.exceptions.DuplicateTransferException;
import com.example.wallet.domain.wallet.exceptions.InsufficientFundsException;
import com.example.wallet.domain.wallet.exceptions.InvalidAmountException;
import com.example.wallet.domain.wallet.exceptions.InvalidCommandException;
import com.example.wallet.domain.wallet.exceptions.SelfTransferException;
import com.example.wallet.domain.wallet.exceptions.WalletNotFoundException;

/**
 * Money-transfer command handler, following the official Axon Framework 5 Spring Boot 4 sample
 * <em>Pattern C</em> (multi-entity / DCB — slice {@code subscribestudent}, class
 * {@code SubscribeStudentToCourseCommandHandler} in {@code examples/university-java-springboot-4}):
 * a Spring {@code @Component} whose {@code @CommandHandler} takes the command, a <strong>single</strong>
 * {@code @InjectEntity} composite state, and an {@link EventAppender}. The composite
 * {@link TransferState} is bound by the command's {@code @TargetEntityId} {@link TransferId}
 * (its {@code idType}); its {@code @EventCriteriaBuilder} sources events for both wallets and the
 * transfer idempotency stream, so the union of those tag streams forms the DCB.
 *
 * <h2>Why one composite state instead of multiple {@code @InjectEntity} parameters</h2>
 * <p>The sample loads the source and target participants in <em>one</em> {@code State} whose
 * {@code @EventCriteriaBuilder} returns {@link EventCriteria#either(EventCriteria...)} over the
 * relevant tag streams. We mirror that exactly: {@link TransferState#resolveCriteria(TransferId)}
 * selects (a) the source wallet stream, (b) the target wallet stream, and (c) the {@code transferId}
 * stream (for in-boundary idempotency). Because those streams are all read while loading the state,
 * Axon Server rejects the append if any advanced concurrently, guaranteeing the transfer never
 * commits against a stale balance and that two concurrent first-time transfers with the same
 * {@code transferId} cannot both commit (requirements 4.7, 4.8, 5.4, 13.1, 13.3).</p>
 */
@Component
public class TransferCommandHandler {

    /**
     * Handles {@link TransferMoney}: validates the transfer rules against the composite
     * event-sourced {@link TransferState} and, on success, appends exactly one
     * {@link MoneyTransferred} tagged with both {@code walletId}s and the {@code transferId}
     * (requirements 4.1–4.8).
     *
     * @param command       the transfer intent
     * @param state         the composite transfer state (both wallets + idempotency), bound by the
     *                      command's {@code @TargetEntityId} {@link TransferId}
     * @param eventAppender appends the single resulting event into the current unit of work
     */
    @CommandHandler
    void handle(TransferMoney command, @InjectEntity TransferState state, EventAppender eventAppender) {
        eventAppender.append(decideTransfer(command, state));
    }

    // --- Pure decision function (no side effects; returns the single event to append) ---------

    private List<MoneyTransferred> decideTransfer(TransferMoney command, TransferState state) {
        requireNonBlank(command.transferId(), "transferId");        // Req 5.1 -> 400
        requireNonBlank(command.sourceWalletId(), "sourceWalletId"); // -> 400
        requireNonBlank(command.targetWalletId(), "targetWalletId"); // -> 400
        requireNotAlreadyProcessed(state, command.transferId());    // Req 4.8, 5.4 -> 409
        requireDistinctWallets(command);                            // Req 4.2 -> 409
        requirePositiveAmount(command.amount());                    // Req 4.3 -> 400
        requireSourceExists(state, command.sourceWalletId());       // Req 4.4 -> 404
        requireTargetExists(state, command.targetWalletId());       // Req 4.4 -> 404
        requireSourceCurrency(state, command.currency());           // Req 4.6 -> 422
        requireTargetCurrency(state, command.currency());           // Req 4.6 -> 422
        requireSufficientFunds(state, command.amount());            // Req 4.5 -> 409

        return List.of(MoneyTransferred.of(
                newEventId(),
                command.transferId(),
                command.sourceWalletId(),
                command.targetWalletId(),
                command.amount(),
                command.currency(),
                now()));
    }

    // --- Validation helpers -------------------------------------------------------------------

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw InvalidCommandException.missingField(fieldName);
        }
    }

    private static void requireNotAlreadyProcessed(TransferState state, String transferId) {
        if (state.alreadyProcessed) {
            throw new DuplicateTransferException(transferId);
        }
    }

    private static void requireDistinctWallets(TransferMoney command) {
        if (command.sourceWalletId().equals(command.targetWalletId())) {
            throw new SelfTransferException(command.sourceWalletId());
        }
    }

    private static void requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw InvalidAmountException.notPositive(amount);
        }
    }

    private static void requireSourceExists(TransferState state, String walletId) {
        if (!state.sourceCreated) {
            throw new WalletNotFoundException(walletId);
        }
    }

    private static void requireTargetExists(TransferState state, String walletId) {
        if (!state.targetCreated) {
            throw new WalletNotFoundException(walletId);
        }
    }

    private static void requireSourceCurrency(TransferState state, String commandCurrency) {
        if (!commandCurrency.equals(state.sourceCurrency)) {
            throw new CurrencyMismatchException(state.sourceWalletId, state.sourceCurrency, commandCurrency);
        }
    }

    private static void requireTargetCurrency(TransferState state, String commandCurrency) {
        if (!commandCurrency.equals(state.targetCurrency)) {
            throw new CurrencyMismatchException(state.targetWalletId, state.targetCurrency, commandCurrency);
        }
    }

    private static void requireSufficientFunds(TransferState state, BigDecimal amount) {
        if (state.sourceBalance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(state.sourceWalletId, state.sourceBalance, amount);
        }
    }

    private static String newEventId() {
        return UUID.randomUUID().toString();
    }

    private static Instant now() {
        return Instant.now();
    }

    /**
     * Composite, event-sourced state spanning the source wallet, the target wallet, and the
     * transfer idempotency stream (Pattern C). Its {@code idType} is {@link TransferId}, so the
     * command's {@code @TargetEntityId} composite id binds here; the {@code @EventCriteriaBuilder}
     * defines the three streams that make up the consistency boundary.
     *
     * <p>Note: no class-level {@code tagKey} — the {@code @EventCriteriaBuilder} defines all tag
     * streams explicitly, exactly as the sample's {@code State} (which is
     * {@code @EventSourced(idType = SubscriptionId.class)} with no {@code tagKey}).</p>
     */
    @EventSourced(idType = TransferId.class)
    static class TransferState {

        private static final int MONEY_SCALE = Wallet.MONEY_SCALE;
        private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
        private static final BigDecimal ZERO_MONEY =
                BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING);

        // Bound from the composite id so the fold methods can attribute events to the right side.
        private String transferId;
        private String sourceWalletId;
        private String targetWalletId;

        private boolean sourceCreated = false;
        private String sourceCurrency;
        private BigDecimal sourceBalance = ZERO_MONEY;

        private boolean targetCreated = false;
        private String targetCurrency;

        private boolean alreadyProcessed = false;

        /**
         * Entity creator taking the composite id (verified {@code EntityCreator} javadoc pattern:
         * "a constructor ... can define no payload. It can still define the identifier as an
         * argument. This will always initialize the entity, even if no events are found ... useful
         * for entities ... with a dynamic boundary"). The {@link InjectEntityId} annotation
         * disambiguates the id from a payload. Seeding the ids here lets the {@code void} fold
         * methods attribute each {@link WalletCreated}/{@link MoneyTransferred} to the source or
         * target side — both wallets emit the same event types, so the ids are required to tell
         * them apart.
         *
         * @param id the composite transfer id bound from the command's {@code @TargetEntityId}
         */
        @EntityCreator
        public TransferState(@InjectEntityId TransferId id) {
            this.transferId = id.transferId();
            this.sourceWalletId = id.sourceWalletId();
            this.targetWalletId = id.targetWalletId();
        }

        @EventSourcingHandler
        void evolve(WalletCreated event) {
            if (event.walletId().equals(sourceWalletId)) {
                this.sourceCreated = true;
                this.sourceCurrency = event.currency();
                this.sourceBalance = ZERO_MONEY;
            }
            if (event.walletId().equals(targetWalletId)) {
                this.targetCreated = true;
                this.targetCurrency = event.currency();
            }
        }

        @EventSourcingHandler
        void evolve(MoneyDeposited event) {
            if (event.walletId().equals(sourceWalletId)) {
                this.sourceBalance = normalize(this.sourceBalance.add(event.amount()));
            }
        }

        @EventSourcingHandler
        void evolve(MoneyWithdrawn event) {
            if (event.walletId().equals(sourceWalletId)) {
                this.sourceBalance = normalize(this.sourceBalance.subtract(event.amount()));
            }
        }

        @EventSourcingHandler
        void evolve(MoneyTransferred event) {
            // Idempotency: any prior transfer carrying this transferId marks it processed.
            if (event.transferId().equals(transferId)) {
                this.alreadyProcessed = true;
            }
            // Fold the source wallet's balance for prior transfers it participated in.
            if (event.sourceWalletId().equals(sourceWalletId)) {
                this.sourceBalance = normalize(this.sourceBalance.subtract(event.amount()));
            }
            if (event.targetWalletId().equals(sourceWalletId)) {
                this.sourceBalance = normalize(this.sourceBalance.add(event.amount()));
            }
        }

        private static BigDecimal normalize(BigDecimal value) {
            return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
        }

        /**
         * Builds the DCB criteria for a transfer: the source wallet stream, the target wallet
         * stream, and the {@code transferId} stream, mirroring the sample's
         * {@code EventCriteria.either(...)} over multiple tags. The state's own id fields are seeded
         * by the {@code @EntityCreator} from the composite id, so the fold methods can attribute
         * each event to the correct side.
         *
         * @param id the composite transfer id bound from the command's {@code @TargetEntityId}
         * @return criteria selecting the events that make up the transfer's consistency boundary
         */
        @EventCriteriaBuilder
        private static EventCriteria resolveCriteria(TransferId id) {
            // IMPORTANT: andBeingOneOfTypes(...) matches against each event's resolved
            // MessageType QualifiedName — NOT the Java FQCN. The write side installs a custom
            // MessageTypeResolver (AxonConfig.walletMessageTypeResolver) that names every domain
            // event by its stable contract name (empty namespace), e.g. "WalletCreated",
            // "MoneyDeposited", "MoneyWithdrawn", "MoneyTransferred". Passing Class#getName() here
            // (the FQCN) therefore matched ZERO stored events, so the source stream was empty and
            // the id-only @EntityCreator was never invoked (it only initializes when a first event
            // is found) — which is why InjectEntityParameterResolver reported EntityNotFoundException
            // even though the wallets clearly had events. Use the contract names to align with what
            // is actually persisted.
            String[] walletEventTypes = {
                    WalletCreated.EVENT_TYPE,
                    MoneyDeposited.EVENT_TYPE,
                    MoneyWithdrawn.EVENT_TYPE,
                    MoneyTransferred.EVENT_TYPE
            };
            return EventCriteria.either(
                    EventCriteria
                            .havingTags(Tag.of(Wallet.TAG_KEY, id.sourceWalletId()))
                            .andBeingOneOfTypes(walletEventTypes),
                    EventCriteria
                            .havingTags(Tag.of(Wallet.TAG_KEY, id.targetWalletId()))
                            .andBeingOneOfTypes(walletEventTypes),
                    EventCriteria
                            .havingTags(Tag.of("transferId", id.transferId()))
                            .andBeingOneOfTypes(MoneyTransferred.EVENT_TYPE)
            );
        }
    }
}
