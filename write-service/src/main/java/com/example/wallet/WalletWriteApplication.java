package com.example.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Wallet CQRS POC Write Side.
 *
 * <p>The Write Service owns all domain rules and produces immutable domain events that are
 * appended to the Axon Server Event Store within the {@code wallet} context. It never reads
 * PostgreSQL to resolve rules or reconstruct aggregate state.
 */
@SpringBootApplication
public class WalletWriteApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletWriteApplication.class, args);
    }
}
