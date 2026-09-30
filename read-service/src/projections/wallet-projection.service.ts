import { Inject, Injectable, Logger } from '@nestjs/common';
import { sql } from 'drizzle-orm';
import { randomUUID } from 'node:crypto';

import {
  DRIZZLE,
  type WalletReadDatabase,
} from '../database/database.tokens';
import type {
  MoneyDepositedPayload,
  MoneyTransferredPayload,
  MoneyWithdrawnPayload,
  NormalizedDomainEvent,
  WalletCreatedPayload,
} from '../events/domain-event';
import type { ProjectionPort } from '../events/projection.port';
import {
  PROJECTION_CONFIG,
  type ProjectionConfig,
} from './projection.config';

/**
 * PostgreSQL unique-constraint violation SQLSTATE.
 * Raised when a redelivered event's `event_id` collides with the UNIQUE
 * constraint on `wallet_transaction.event_id` (the idempotency guard).
 */
const PG_UNIQUE_VIOLATION = '23505';

/**
 * Transaction handle type: the Drizzle transaction callback receives a value with
 * the same query surface as the top-level db. We alias it here so the private
 * per-event handlers can be typed without importing Drizzle-internal transaction types.
 */
type Tx = Parameters<Parameters<WalletReadDatabase['transaction']>[0]>[0];

/**
 * Real transactional, idempotent projection (task 9.2).
 *
 * For each {@link NormalizedDomainEvent} this service runs a SINGLE PostgreSQL
 * transaction that materializes the Read Model:
 *
 *   WalletCreated     -> INSERT wallet_balance (balance 0.00)               [Req 9.1]
 *   MoneyDeposited    -> balance += amount; INSERT wallet_transaction DEPOSIT [Req 9.2]
 *   MoneyWithdrawn    -> balance -= amount; INSERT wallet_transaction WITHDRAWAL [Req 9.3]
 *   MoneyTransferred  -> source -= amount, target += amount; INSERT wallet_transfer  [Req 9.4]
 *                        + derived TRANSFER_OUT / TRANSFER_IN movements               [task 15.1]
 *
 * Idempotency (Req 10.2, 10.3):
 * - The three money events carry a stable `eventId`; the projection is idempotent
 *   on that key. The `wallet_transaction.event_id` UNIQUE constraint is the primary
 *   guard: a pre-check inside the transaction short-circuits a known duplicate, and
 *   a concurrent duplicate that slips past the pre-check is caught via the unique
 *   violation (SQLSTATE 23505) and treated as already-processed success.
 * - WalletCreated carries no wallet_transaction row / no payload eventId, so its
 *   idempotency is guarded by the wallet_balance primary key via
 *   `ON CONFLICT (wallet_id) DO NOTHING` — a redelivered WalletCreated neither errors
 *   nor resets the balance.
 *
 * Numeric correctness (Req 9.2–9.4): money is NUMERIC(19,2). All balance math is done
 * SQL-side (`balance = balance + $amount`) so no JS float rounding is ever applied.
 *
 * Error handling (Req 10.5, 16.3): balance updates for money events assert a non-zero
 * affected-row count — if the target wallet does not exist the UPDATE affects 0 rows and
 * this service THROWS, so the adapter returns non-2xx and Axon Server retries. Unknown
 * event types are logged as explicit errors and rejected. Nothing is swallowed into a
 * silent success.
 */
@Injectable()
export class WalletProjectionService implements ProjectionPort {
  private readonly logger = new Logger(WalletProjectionService.name);

  constructor(
    @Inject(DRIZZLE) private readonly db: WalletReadDatabase,
    @Inject(PROJECTION_CONFIG) private readonly config: ProjectionConfig,
  ) {}

  /**
   * Apply one normalized event inside a single PostgreSQL transaction.
   *
   * Resolves once the event is durably committed OR recognised as an already-applied
   * duplicate; rejects (throws) on any non-idempotent failure so the event is retried.
   *
   * Eventual-consistency demo (task 15.2): when READ_PROJECTION_DELAY_MS > 0 we sleep
   * that many milliseconds BEFORE opening the transaction, widening the observable
   * window in which the Event Store is already updated but the Read Model still lags.
   * The delay precedes the durable commit, so the 2xx-only-after-commit contract is
   * intact — a failure after the delay still throws and Axon Server retries. Default 0
   * is a true no-op (the sleep is skipped entirely).
   */
  async apply(event: NormalizedDomainEvent): Promise<void> {
    await this.applyEventualConsistencyDelay(event);
    try {
      await this.db.transaction(async (tx) => {
        await this.dispatch(tx, event);
      });
    } catch (error) {
      // A concurrent duplicate can surface as a unique violation on event_id even
      // when the in-transaction pre-check missed it (two deliveries racing). Treat
      // it as already processed: the first writer's effect is authoritative.
      if (this.isUniqueViolation(error)) {
        this.logger.log(
          `Duplicate event ignored (unique violation on event_id) ` +
            `eventType=${event.eventType} eventId=${event.eventId}`,
        );
        return;
      }
      throw error;
    }
  }

  /** Route an event to its per-type handler; unknown types are explicit errors. */
  private async dispatch(
    tx: Tx,
    event: NormalizedDomainEvent,
  ): Promise<void> {
    switch (event.eventType) {
      case 'WalletCreated':
        return this.applyWalletCreated(tx, event, event.payload as WalletCreatedPayload);
      case 'MoneyDeposited':
        return this.applyMoneyDeposited(tx, event, event.payload as MoneyDepositedPayload);
      case 'MoneyWithdrawn':
        return this.applyMoneyWithdrawn(tx, event, event.payload as MoneyWithdrawnPayload);
      case 'MoneyTransferred':
        return this.applyMoneyTransferred(tx, event, event.payload as MoneyTransferredPayload);
      default: {
        // Requirement 10.4: unknown event type -> explicit error log + reject.
        const unknownType: string = (event as NormalizedDomainEvent).eventType;
        this.logger.error(
          `Unknown event type "${unknownType}" eventId=${event.eventId}; not applied.`,
        );
        throw new Error(`Unknown event type "${unknownType}"`);
      }
    }
  }

  // --- WalletCreated -------------------------------------------------------

  /**
   * INSERT the wallet_balance row with balance 0.00 (Req 9.1). Idempotent via the
   * wallet_balance primary key: a redelivered WalletCreated is a no-op that neither
   * errors nor resets the balance.
   */
  private async applyWalletCreated(
    tx: Tx,
    event: NormalizedDomainEvent,
    payload: WalletCreatedPayload,
  ): Promise<void> {
    const result = await tx.execute(sql`
      INSERT INTO wallet_balance (wallet_id, owner_id, currency, balance, updated_at)
      VALUES (${payload.walletId}, ${payload.ownerId}, ${payload.currency}, 0.00, ${this.occurredAtIso(event)})
      ON CONFLICT (wallet_id) DO NOTHING
    `);

    if (this.rowCount(result) === 0) {
      this.logger.log(
        `WalletCreated already applied (wallet exists) walletId=${payload.walletId} eventId=${event.eventId}`,
      );
    } else {
      this.logger.log(
        `WalletCreated applied walletId=${payload.walletId} ownerId=${payload.ownerId} eventId=${event.eventId}`,
      );
    }
  }

  // --- MoneyDeposited ------------------------------------------------------

  /** balance += amount and append a DEPOSIT movement (Req 9.2). */
  private async applyMoneyDeposited(
    tx: Tx,
    event: NormalizedDomainEvent,
    payload: MoneyDepositedPayload,
  ): Promise<void> {
    if (await this.alreadyProcessed(tx, event.eventId)) {
      this.logger.log(
        `MoneyDeposited already applied (eventId seen) eventId=${event.eventId}`,
      );
      return;
    }

    await this.adjustBalance(tx, payload.walletId, payload.amount, '+', event);
    await this.insertTransaction(tx, {
      walletId: payload.walletId,
      transactionType: 'DEPOSIT',
      amount: payload.amount,
      currency: payload.currency,
      occurredAt: this.occurredAtIso(event, payload.occurredAt),
      eventId: event.eventId,
    });

    this.logger.log(
      `MoneyDeposited applied walletId=${payload.walletId} amount=${payload.amount} eventId=${event.eventId}`,
    );
  }

  // --- MoneyWithdrawn ------------------------------------------------------

  /** balance -= amount and append a WITHDRAWAL movement (Req 9.3). */
  private async applyMoneyWithdrawn(
    tx: Tx,
    event: NormalizedDomainEvent,
    payload: MoneyWithdrawnPayload,
  ): Promise<void> {
    if (await this.alreadyProcessed(tx, event.eventId)) {
      this.logger.log(
        `MoneyWithdrawn already applied (eventId seen) eventId=${event.eventId}`,
      );
      return;
    }

    await this.adjustBalance(tx, payload.walletId, payload.amount, '-', event);
    await this.insertTransaction(tx, {
      walletId: payload.walletId,
      transactionType: 'WITHDRAWAL',
      amount: payload.amount,
      currency: payload.currency,
      occurredAt: this.occurredAtIso(event, payload.occurredAt),
      eventId: event.eventId,
    });

    this.logger.log(
      `MoneyWithdrawn applied walletId=${payload.walletId} amount=${payload.amount} eventId=${event.eventId}`,
    );
  }

  // --- MoneyTransferred ----------------------------------------------------

  /**
   * source -= amount, target += amount, within ONE transaction (Req 9.4), plus (task
   * 15.1):
   *   - one `wallet_transfer` row keyed by `payload.transferId`, guarded for
   *     idempotency by its own UNIQUE `event_id` (the RAW MoneyTransferred eventId);
   *   - TWO derived `wallet_transaction` movements — a `TRANSFER_OUT` against the source
   *     and a `TRANSFER_IN` against the target — replacing the former single `TRANSFER`
   *     row.
   *
   * Idempotency: `wallet_transaction.event_id` is UNIQUE, so the two movements cannot
   * share the raw eventId. We derive two STABLE, deterministic ids — `${eventId}:OUT`
   * and `${eventId}:IN` — so (a) they satisfy the UNIQUE constraint, (b) redelivery
   * re-derives the same ids and is short-circuited (pre-check / 23505), and (c) the
   * `wallet_transfer` row keeps the raw eventId as its guard. The in-transaction
   * pre-check keys on the OUT-movement id: if that row is already present the whole
   * transfer has been applied, so we skip re-applying balances, the transfer row, and
   * both movements.
   */
  private async applyMoneyTransferred(
    tx: Tx,
    event: NormalizedDomainEvent,
    payload: MoneyTransferredPayload,
  ): Promise<void> {
    const outEventId = this.derivedEventId(event.eventId, 'OUT');
    const inEventId = this.derivedEventId(event.eventId, 'IN');

    // The OUT movement is written first and shares the transfer's lifecycle, so its
    // presence means the whole transfer already applied (all writes share one tx).
    if (await this.alreadyProcessed(tx, outEventId)) {
      this.logger.log(
        `MoneyTransferred already applied (eventId seen) eventId=${event.eventId} transferId=${payload.transferId}`,
      );
      return;
    }

    const occurredAt = this.occurredAtIso(event, payload.occurredAt);

    await this.adjustBalance(tx, payload.sourceWalletId, payload.amount, '-', event);
    await this.adjustBalance(tx, payload.targetWalletId, payload.amount, '+', event);

    // Record the transfer itself (one row per completed transfer).
    await this.insertTransfer(tx, {
      transferId: payload.transferId,
      sourceWalletId: payload.sourceWalletId,
      targetWalletId: payload.targetWalletId,
      amount: payload.amount,
      currency: payload.currency,
      occurredAt,
      eventId: event.eventId,
    });

    // Two derived movements: OUT against the source, IN against the target.
    await this.insertTransaction(tx, {
      walletId: payload.sourceWalletId,
      transactionType: 'TRANSFER_OUT',
      amount: payload.amount,
      currency: payload.currency,
      occurredAt,
      eventId: outEventId,
    });
    await this.insertTransaction(tx, {
      walletId: payload.targetWalletId,
      transactionType: 'TRANSFER_IN',
      amount: payload.amount,
      currency: payload.currency,
      occurredAt,
      eventId: inEventId,
    });

    this.logger.log(
      `MoneyTransferred applied transferId=${payload.transferId} ` +
        `source=${payload.sourceWalletId} target=${payload.targetWalletId} ` +
        `amount=${payload.amount} eventId=${event.eventId} ` +
        `(movements ${outEventId}, ${inEventId})`,
    );
  }

  // --- shared SQL helpers --------------------------------------------------

  /**
   * SQL-side balance adjustment. Uses `balance = balance +/- $amount` so arithmetic
   * runs in PostgreSQL at NUMERIC(19,2) precision — no JS float rounding.
   *
   * Asserts exactly one row was updated; if the wallet does not exist the UPDATE
   * affects 0 rows and this THROWS (Req 10.5 / 16.3) so the event is retried rather
   * than silently lost.
   */
  private async adjustBalance(
    tx: Tx,
    walletId: string,
    amount: number,
    op: '+' | '-',
    event: NormalizedDomainEvent,
  ): Promise<void> {
    const amountText = this.moneyText(amount);
    const delta =
      op === '+'
        ? sql`balance + ${amountText}::numeric`
        : sql`balance - ${amountText}::numeric`;

    const result = await tx.execute(sql`
      UPDATE wallet_balance
      SET balance = ${delta},
          updated_at = ${this.occurredAtIso(event)}
      WHERE wallet_id = ${walletId}
    `);

    if (this.rowCount(result) === 0) {
      // Wallet missing: do not swallow. Throwing aborts the transaction and yields
      // a non-2xx so Axon Server retries (e.g. the WalletCreated may not have arrived yet).
      throw new Error(
        `Cannot apply ${event.eventType} (eventId=${event.eventId}): ` +
          `wallet_balance row for walletId=${walletId} does not exist.`,
      );
    }
  }

  /**
   * INSERT a wallet_transaction row. The `event_id` UNIQUE constraint is the
   * idempotency guard; a colliding redelivery raises SQLSTATE 23505, handled in
   * {@link apply}.
   */
  private async insertTransaction(
    tx: Tx,
    row: {
      walletId: string;
      transactionType: 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER_OUT' | 'TRANSFER_IN';
      amount: number;
      currency: string;
      occurredAt: string;
      eventId: string;
    },
  ): Promise<void> {
    await tx.execute(sql`
      INSERT INTO wallet_transaction
        (transaction_id, wallet_id, transaction_type, amount, currency, occurred_at, event_id)
      VALUES
        (${randomUUID()}, ${row.walletId}, ${row.transactionType},
         ${this.moneyText(row.amount)}::numeric, ${row.currency}, ${row.occurredAt}, ${row.eventId})
    `);
  }

  /**
   * INSERT a `wallet_transfer` row (task 15.1). Idempotency is provided by the table's
   * own UNIQUE `event_id` (the raw MoneyTransferred eventId); a redelivery that reaches
   * here raises SQLSTATE 23505, handled in {@link apply} as already-processed. In
   * practice the OUT-movement pre-check short-circuits before we get here on redelivery.
   */
  private async insertTransfer(
    tx: Tx,
    row: {
      transferId: string;
      sourceWalletId: string;
      targetWalletId: string;
      amount: number;
      currency: string;
      occurredAt: string;
      eventId: string;
    },
  ): Promise<void> {
    await tx.execute(sql`
      INSERT INTO wallet_transfer
        (transfer_id, source_wallet_id, target_wallet_id, amount, currency, occurred_at, event_id)
      VALUES
        (${row.transferId}, ${row.sourceWalletId}, ${row.targetWalletId},
         ${this.moneyText(row.amount)}::numeric, ${row.currency}, ${row.occurredAt}, ${row.eventId})
    `);
  }

  /**
   * Derive a stable, deterministic per-movement event id from a MoneyTransferred
   * eventId so the two derived wallet_transaction rows (TRANSFER_OUT / TRANSFER_IN)
   * each get a distinct id that still satisfies the UNIQUE constraint and stays
   * idempotent on redelivery.
   */
  private derivedEventId(eventId: string, leg: 'OUT' | 'IN'): string {
    return `${eventId}:${leg}`;
  }

  /**
   * In-transaction existence pre-check on `event_id`. Combined with the UNIQUE
   * constraint (and the 23505 catch), this makes projection idempotent even under
   * concurrent redelivery (Req 10.2, 10.3).
   */
  private async alreadyProcessed(tx: Tx, eventId: string): Promise<boolean> {
    const result = await tx.execute(sql`
      SELECT 1 FROM wallet_transaction WHERE event_id = ${eventId} LIMIT 1
    `);
    return this.rowCount(result) > 0;
  }

  // --- utilities -----------------------------------------------------------

  /**
   * Sleep READ_PROJECTION_DELAY_MS before projecting, when configured (task 15.2).
   * A zero/disabled delay returns immediately (no artificial latency, no log). A
   * non-zero delay is logged with the eventId so the demo window is visible.
   */
  private async applyEventualConsistencyDelay(
    event: NormalizedDomainEvent,
  ): Promise<void> {
    const delayMs = this.config.readProjectionDelayMs;
    if (delayMs <= 0) {
      return;
    }
    this.logger.log(
      `Eventual-consistency demo: delaying projection by ${delayMs}ms ` +
        `eventType=${event.eventType} eventId=${event.eventId}`,
    );
    await new Promise<void>((resolve) => setTimeout(resolve, delayMs));
  }

  /** Detect a PostgreSQL unique-constraint violation (SQLSTATE 23505). */
  private isUniqueViolation(error: unknown): boolean {
    return (
      typeof error === 'object' &&
      error !== null &&
      'code' in error &&
      (error as { code?: unknown }).code === PG_UNIQUE_VIOLATION
    );
  }

  /**
   * Render an amount as a fixed scale-2 decimal string for `::numeric` casting.
   * Passing a string (not a JS number) avoids any float representation issue at the
   * driver boundary; PostgreSQL performs the arithmetic at NUMERIC(19,2).
   */
  private moneyText(amount: number): string {
    return amount.toFixed(2);
  }

  /**
   * Resolve the timestamp to store / stamp `updated_at`. Prefers the event's
   * `occurredAt` (payload or envelope), falling back to now.
   */
  private occurredAtIso(
    event: NormalizedDomainEvent,
    payloadOccurredAt?: string,
  ): string {
    const value = payloadOccurredAt ?? event.occurredAt;
    if (value) {
      const parsed = new Date(value);
      if (!Number.isNaN(parsed.getTime())) {
        return parsed.toISOString();
      }
    }
    return new Date().toISOString();
  }

  /** Read the affected/returned row count from a node-postgres result. */
  private rowCount(result: unknown): number {
    if (
      typeof result === 'object' &&
      result !== null &&
      'rowCount' in result &&
      typeof (result as { rowCount?: unknown }).rowCount === 'number'
    ) {
      return (result as { rowCount: number }).rowCount;
    }
    // Fallback: some drivers surface `rows`.
    if (
      typeof result === 'object' &&
      result !== null &&
      'rows' in result &&
      Array.isArray((result as { rows?: unknown }).rows)
    ) {
      return (result as { rows: unknown[] }).rows.length;
    }
    return 0;
  }
}
