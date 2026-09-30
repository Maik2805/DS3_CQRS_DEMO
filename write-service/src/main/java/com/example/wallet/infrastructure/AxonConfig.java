package com.example.wallet.infrastructure;

import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.NamespaceMessageTypeResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.wallet.domain.wallet.events.MoneyDeposited;
import com.example.wallet.domain.wallet.events.MoneyTransferred;
import com.example.wallet.domain.wallet.events.MoneyWithdrawn;
import com.example.wallet.domain.wallet.events.WalletCreated;

/**
 * Axon Framework 5 wiring for the Write Side.
 *
 * <h2>No programmatic entity / command-handler registration (matches the official sample)</h2>
 * <p>Entities and command handlers are auto-detected by the Axon Spring integration, exactly as in
 * the official {@code examples/university-java-springboot-4} sample:</p>
 * <ul>
 *   <li>Event-sourced entities use the Spring stereotype
 *       {@code org.axonframework.extension.spring.stereotype.EventSourced} (with {@code idType}),
 *       so Spring component scanning registers them — no {@code EventSourcedEntityModule} bean.
 *       Here: {@code Wallet} (creation entity, static create handler),
 *       {@code WalletCommandHandler.WalletState} (deposit/withdraw state), and
 *       {@code TransferCommandHandler.TransferState} (composite DCB state).</li>
 *   <li>Mutation/multi-entity command handlers are Spring {@code @Component} classes
 *       ({@code WalletCommandHandler}, {@code TransferCommandHandler}); their {@code @CommandHandler}
 *       methods are auto-detected — no {@code CommandHandlingModule} bean.</li>
 * </ul>
 * <p>This removes the previous programmatic registration that mixed incompatible approaches and
 * caused {@code EntityNotFoundException} on creation.</p>
 *
 * <h2>Custom {@link MessageTypeResolver} (kept — Read Side contract names)</h2>
 * <p>The stored/published event <em>type name</em> is produced by a {@link MessageTypeResolver}.
 * The framework default {@link ClassBasedMessageTypeResolver} would emit the fully-qualified Java
 * class name, leaking package structure across the contract boundary and breaking the NestJS Read
 * Side, which keys its projection on the stable past-tense names {@code WalletCreated} /
 * {@code MoneyDeposited} / {@code MoneyWithdrawn} / {@code MoneyTransferred}. This bean maps each
 * domain event class to a {@code MessageType} whose name is exactly the contract name (empty
 * namespace so no prefix), version {@code "1"}, delegating everything else (e.g. commands) to the
 * class-based fallback so those still resolve.</p>
 */
@Configuration
public class AxonConfig {

    /** Contract version applied to every domain event's {@code MessageType} (matches {@code eventVersion}). */
    private static final String EVENT_VERSION = "1";

    /**
     * Maps each wallet domain event to a stable, contract-defined {@code MessageType} name so the
     * type published to (and read from) the Axon Server {@code wallet} context is the past-tense
     * contract name — never the Java fully-qualified class name.
     *
     * @return a {@link MessageTypeResolver} naming the four domain events by their contract names,
     *         delegating everything else to class-based naming
     */
    @Bean
    public MessageTypeResolver walletMessageTypeResolver() {
        return NamespaceMessageTypeResolver.namespace("")
                .message(WalletCreated.class, WalletCreated.EVENT_TYPE, EVENT_VERSION)
                .message(MoneyDeposited.class, MoneyDeposited.EVENT_TYPE, EVENT_VERSION)
                .message(MoneyWithdrawn.class, MoneyWithdrawn.EVENT_TYPE, EVENT_VERSION)
                .message(MoneyTransferred.class, MoneyTransferred.EVENT_TYPE, EVENT_VERSION)
                .fallback(new ClassBasedMessageTypeResolver());
    }
}
