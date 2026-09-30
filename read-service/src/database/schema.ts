import {
  index,
  numeric,
  pgTable,
  timestamp,
  varchar,
} from 'drizzle-orm/pg-core';

/**
 * Drizzle ORM schema for the Wallet CQRS POC Read Model.
 *
 * These table definitions MUST mirror `infrastructure/postgres/init.sql` exactly. The
 * actual schema is created by PostgreSQL's docker-entrypoint-initdb.d mechanism (init.sql)
 * on first container startup — the application does NOT run migrations at runtime and never
 * drops or recreates these tables. This module exists so projection handlers and query
 * services can read/write through a typed Drizzle interface.
 *
 * Design decisions (see design.md "Data Models"):
 *   - Identifiers are VARCHAR (free-form strings), NOT UUID.
 *   - Money uses NUMERIC(19,2), mirroring the BigDecimal scale 2 on the Write Side.
 *   - `wallet_transaction.event_id` UNIQUE is the primary idempotency guard.
 */

/**
 * Current balance per wallet. One row per wallet.
 * Mirrors the `wallet_balance` table in init.sql.
 */
export const walletBalance = pgTable('wallet_balance', {
  walletId: varchar('wallet_id', { length: 255 }).primaryKey(),
  ownerId: varchar('owner_id', { length: 255 }).notNull(),
  currency: varchar('currency', { length: 3 }).notNull(),
  balance: numeric('balance', { precision: 19, scale: 2 }).notNull(),
  updatedAt: timestamp('updated_at', { withTimezone: true }).notNull(),
});

/**
 * Append-only movement log projected from domain events.
 * Mirrors the `wallet_transaction` table in init.sql, including the UNIQUE
 * constraint on `event_id` and the (wallet_id, occurred_at DESC) index.
 */
export const walletTransaction = pgTable(
  'wallet_transaction',
  {
    transactionId: varchar('transaction_id', { length: 255 }).primaryKey(),
    walletId: varchar('wallet_id', { length: 255 }).notNull(),
    // DEPOSIT | WITHDRAWAL | TRANSFER_OUT | TRANSFER_IN
    transactionType: varchar('transaction_type', { length: 30 }).notNull(),
    amount: numeric('amount', { precision: 19, scale: 2 }).notNull(),
    currency: varchar('currency', { length: 3 }).notNull(),
    occurredAt: timestamp('occurred_at', { withTimezone: true }).notNull(),
    eventId: varchar('event_id', { length: 255 }).notNull().unique(),
  },
  (table) => [
    index('idx_wallet_transaction_wallet_occurred').on(
      table.walletId,
      table.occurredAt.desc(),
    ),
  ],
);

/**
 * One row per completed transfer (task 15.1).
 * Mirrors the `wallet_transfer` table in init.sql, including the UNIQUE constraint on
 * `event_id` (the raw MoneyTransferred eventId, this table's idempotency guard).
 */
export const walletTransfer = pgTable('wallet_transfer', {
  transferId: varchar('transfer_id', { length: 255 }).primaryKey(),
  sourceWalletId: varchar('source_wallet_id', { length: 255 }).notNull(),
  targetWalletId: varchar('target_wallet_id', { length: 255 }).notNull(),
  amount: numeric('amount', { precision: 19, scale: 2 }).notNull(),
  currency: varchar('currency', { length: 3 }).notNull(),
  occurredAt: timestamp('occurred_at', { withTimezone: true }).notNull(),
  eventId: varchar('event_id', { length: 255 }).notNull().unique(),
});

/**
 * Aggregate schema object, convenient for passing to `drizzle(pool, { schema })`.
 */
export const schema = {
  walletBalance,
  walletTransaction,
  walletTransfer,
};

export type WalletBalanceRow = typeof walletBalance.$inferSelect;
export type NewWalletBalanceRow = typeof walletBalance.$inferInsert;
export type WalletTransactionRow = typeof walletTransaction.$inferSelect;
export type NewWalletTransactionRow = typeof walletTransaction.$inferInsert;
export type WalletTransferRow = typeof walletTransfer.$inferSelect;
export type NewWalletTransferRow = typeof walletTransfer.$inferInsert;
