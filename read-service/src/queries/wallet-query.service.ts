import { Inject, Injectable, NotFoundException } from '@nestjs/common';
import { desc, eq } from 'drizzle-orm';

import {
  DRIZZLE,
  type WalletReadDatabase,
} from '../database/database.tokens';
import {
  walletBalance,
  walletTransaction,
  type WalletBalanceRow,
  type WalletTransactionRow,
} from '../database/schema';

/**
 * Response shape for the balance query (`GET /api/wallets/{walletId}/balance`).
 *
 * `balance` is returned as-is — a fixed scale-2 decimal STRING. Drizzle's `numeric`
 * column type maps NUMERIC(19,2) to a JS string at the driver boundary, and the Read
 * Side deliberately preserves that representation rather than coercing to a JS `number`
 * (which cannot exactly represent every scale-2 decimal). The `updatedAt` timestamp is
 * serialized as an ISO-8601 string.
 */
export interface WalletBalanceView {
  walletId: string;
  currency: string;
  balance: string;
  updatedAt: string;
}

/**
 * Response shape for the wallet-detail query (`GET /api/wallets/{walletId}`).
 * Superset of {@link WalletBalanceView} adding `ownerId`.
 */
export interface WalletDetailView {
  walletId: string;
  ownerId: string;
  currency: string;
  balance: string;
  updatedAt: string;
}

/**
 * A single transaction row as returned by
 * `GET /api/wallets/{walletId}/transactions`. `amount` follows the same scale-2
 * decimal-string convention as `balance`; `occurredAt` is ISO-8601.
 */
export interface WalletTransactionView {
  transactionId: string;
  walletId: string;
  transactionType: string;
  amount: string;
  currency: string;
  occurredAt: string;
  eventId: string;
}

/** Paging inputs for the transactions query (already parsed/clamped by the caller). */
export interface TransactionPaging {
  limit: number;
  offset: number;
}

/**
 * Read-only query service for the Wallet Read Model (task 10.1).
 *
 * Resolves every query EXCLUSIVELY from PostgreSQL via the injected Drizzle handle and
 * runs SELECTs only — no INSERT/UPDATE/DELETE, no transactions, no event-store access
 * (Req 11.5, 11.6). Absent wallets surface as {@link NotFoundException} → HTTP 404
 * (Req 11.4).
 *
 * Numeric representation: `balance`/`amount` are NUMERIC(19,2); Drizzle returns them as
 * scale-2 decimal strings and this service passes them through unchanged so no precision
 * is lost. `TIMESTAMPTZ` columns arrive as `Date` and are serialized to ISO-8601 strings.
 */
@Injectable()
export class WalletQueryService {
  constructor(
    @Inject(DRIZZLE) private readonly db: WalletReadDatabase,
  ) {}

  /**
   * Balance projection for a wallet (Req 11.1).
   * @throws NotFoundException when the wallet is absent from the Read Model (Req 11.4).
   */
  async getBalance(walletId: string): Promise<WalletBalanceView> {
    const row = await this.findBalanceRow(walletId);
    return {
      walletId: row.walletId,
      currency: row.currency,
      balance: row.balance,
      updatedAt: this.toIso(row.updatedAt),
    };
  }

  /**
   * Full wallet detail (Req 11.2).
   * @throws NotFoundException when the wallet is absent from the Read Model (Req 11.4).
   */
  async getWalletDetail(walletId: string): Promise<WalletDetailView> {
    const row = await this.findBalanceRow(walletId);
    return {
      walletId: row.walletId,
      ownerId: row.ownerId,
      currency: row.currency,
      balance: row.balance,
      updatedAt: this.toIso(row.updatedAt),
    };
  }

  /**
   * Transactions for a wallet, newest first (Req 11.3).
   *
   * Ordered by `occurred_at` DESC and paged by `limit`/`offset`. A 404 is raised when
   * the wallet itself does not exist so an unknown wallet is distinguishable from a
   * known wallet that simply has no movements yet (Req 11.4).
   *
   * @throws NotFoundException when the wallet is absent from the Read Model.
   */
  async getTransactions(
    walletId: string,
    paging: TransactionPaging,
  ): Promise<WalletTransactionView[]> {
    // Distinguish "unknown wallet" (404) from "known wallet, no transactions" (empty list).
    await this.findBalanceRow(walletId);

    const rows = await this.db
      .select()
      .from(walletTransaction)
      .where(eq(walletTransaction.walletId, walletId))
      .orderBy(desc(walletTransaction.occurredAt))
      .limit(paging.limit)
      .offset(paging.offset);

    return rows.map((row) => this.toTransactionView(row));
  }

  // --- helpers -------------------------------------------------------------

  /** Fetch the single wallet_balance row or throw 404. SELECT only. */
  private async findBalanceRow(walletId: string): Promise<WalletBalanceRow> {
    const rows = await this.db
      .select()
      .from(walletBalance)
      .where(eq(walletBalance.walletId, walletId))
      .limit(1);

    const row = rows[0];
    if (!row) {
      throw new NotFoundException(`Wallet "${walletId}" not found.`);
    }
    return row;
  }

  /** Map a raw transaction row to its API view, keeping money as a scale-2 string. */
  private toTransactionView(row: WalletTransactionRow): WalletTransactionView {
    return {
      transactionId: row.transactionId,
      walletId: row.walletId,
      transactionType: row.transactionType,
      amount: row.amount,
      currency: row.currency,
      occurredAt: this.toIso(row.occurredAt),
      eventId: row.eventId,
    };
  }

  /** Serialize a TIMESTAMPTZ (`Date`) to an ISO-8601 string. */
  private toIso(value: Date): string {
    return value instanceof Date ? value.toISOString() : new Date(value).toISOString();
  }
}
