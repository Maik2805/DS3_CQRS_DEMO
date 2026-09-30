import { Inject, Injectable, Logger } from '@nestjs/common';
import { sql } from 'drizzle-orm';

import {
  DRIZZLE,
  type WalletReadDatabase,
} from '../database/database.tokens';
import { REBUILD_CONFIG, type RebuildConfig } from './rebuild.config';

/**
 * Result summary returned by {@link RebuildService.rebuild}. Serialized as the JSON
 * body of `POST /admin/projection/rebuild` so the operator sees exactly what happened.
 */
export interface RebuildResult {
  /** Projection tables that were cleared, in truncation order. */
  tablesCleared: string[];
  /** The persistent stream whose position was reset. */
  streamName: string;
  /** Whether the stream-reset admin call was issued and accepted. */
  streamResetRequested: boolean;
  /** Human-readable notes (e.g. how replay proceeds, manual alternative pointer). */
  notes: string[];
  /** ISO timestamp the rebuild was performed. */
  performedAt: string;
}

/**
 * Read Model rebuild / replay (task 11.1, Requirements 12.1, 12.2, 12.3).
 *
 * Demonstrates that the Read Model is a *derived projection* of the single event log
 * held by Axon Server. The operation:
 *
 *   1. TRUNCATEs the projection tables (`wallet_balance`, `wallet_transaction`,
 *      `wallet_transfer`) in one PostgreSQL transaction. Clearing `wallet_transaction`
 *      also resets the
 *      idempotency/dedup state (the `event_id` UNIQUE guard), so replayed events
 *      re-apply from scratch rather than being short-circuited as duplicates.
 *   2. Resets the `wallet-read-projection` persistent stream position back to `TAIL`
 *      via the Axon Server admin API. Axon Server then re-delivers ALL historical
 *      events (with a replay indicator) to the existing `POST /internal/events/axon`
 *      adapter, which re-applies them through the unchanged projection handlers.
 *   3. Returns a JSON summary of what was done.
 *
 * Requirement 12.2 / 12.3: the Read Model is reconstructed EXCLUSIVELY from events
 * replayed via the Axon Server persistent stream — there is NO second event log, and
 * this service NEVER reads back a PostgreSQL copy or any auxiliary store to rebuild.
 * The only PostgreSQL interaction here is the destructive TRUNCATE; all reconstruction
 * happens through normal event delivery afterwards.
 *
 * MANUAL ALTERNATIVE (design.md "Rebuild / Replay", Req 12.5): the same effect can be
 * achieved by hand — TRUNCATE the projection tables with psql and re-register / reset
 * the stream to TAIL, matching infrastructure/axon/register-projection.{sh,ps1}:
 *
 *   docker compose exec postgres-read psql -U wallet_read -d wallet_read \
 *     -c "TRUNCATE wallet_transaction; TRUNCATE wallet_balance;"
 *   # then reset/re-register the wallet-read-projection stream to TAIL (see register-projection.sh)
 *
 * This service does the same two steps programmatically; it does NOT implement a second
 * event log or rebuild from a Postgres copy.
 */
@Injectable()
export class RebuildService {
  private readonly logger = new Logger(RebuildService.name);

  constructor(
    @Inject(DRIZZLE) private readonly db: WalletReadDatabase,
    @Inject(REBUILD_CONFIG) private readonly config: RebuildConfig,
  ) {}

  /**
   * Perform the rebuild: truncate the projection tables, then reset the persistent
   * stream to TAIL so Axon Server re-delivers the full history.
   *
   * TRUNCATE runs first and is reported even if the subsequent stream-reset call
   * fails — an operator who sees "tables cleared but reset failed" knows the Read
   * Model is empty and the stream must be reset manually (rather than a silent
   * half-success). A failed reset THROWS so the caller returns a non-2xx.
   */
  async rebuild(): Promise<RebuildResult> {
    const performedAt = new Date().toISOString();

    // --- Step 1 + 2: clear the projection tables (single transaction). -----------
    // TRUNCATE resets both the materialized state AND the event_id dedup state so
    // replayed events re-apply. We keep both truncations in one transaction so the
    // Read Model is never observed in a partially-cleared state.
    const tablesCleared = await this.truncateProjectionTables();
    this.logger.warn(
      `Read Model projection tables truncated: [${tablesCleared.join(', ')}]. ` +
        'Awaiting replay from Axon Server persistent stream.',
    );

    // --- Step 3: reset the persistent-stream position to TAIL. -------------------
    // If this fails we have already truncated; surface the failure loudly (throw)
    // but the thrown error message makes clear the tables were cleared.
    await this.resetStreamToTail(tablesCleared);

    const notes = [
      `Persistent stream "${this.config.persistentStreamName}" reset to TAIL; ` +
        'Axon Server will re-deliver all historical events to POST /internal/events/axon, ' +
        'which the existing idempotent projection re-applies. The Read Model is reconstructed ' +
        'exclusively from replayed events (Req 12.2) — never from PostgreSQL or an auxiliary copy.',
      'No second event log exists (Req 12.3); Axon Server is the single event log.',
      'Manual alternative: TRUNCATE the projection tables via psql and reset/re-register the ' +
        'stream to TAIL (see infrastructure/axon/register-projection.sh and design.md).',
    ];

    return {
      tablesCleared,
      streamName: this.config.persistentStreamName,
      streamResetRequested: true,
      notes,
      performedAt,
    };
  }

  /**
   * TRUNCATE `wallet_balance`, `wallet_transaction` and `wallet_transfer` in a single transaction.
   * Returns the list of cleared tables (in truncation order).
   *
   * TRUNCATE (vs DELETE) is used because it is the fastest way to empty the tables
   * for a full rebuild and it is a bounded, local operation on the Read Model only.
   */
  private async truncateProjectionTables(): Promise<string[]> {
    await this.db.transaction(async (tx) => {
      // Order does not matter here (no FKs between the tables), but we truncate the
      // movement log first, then the transfer log, then the balances. `wallet_transfer`
      // is created by init.sql (task 15.1); a Read Model provisioned before that table
      // existed must be re-initialized per the note in init.sql before rebuilding.
      await tx.execute(sql`TRUNCATE TABLE wallet_transaction`);
      await tx.execute(sql`TRUNCATE TABLE wallet_transfer`);
      await tx.execute(sql`TRUNCATE TABLE wallet_balance`);
    });
    return ['wallet_transaction', 'wallet_transfer', 'wallet_balance'];
  }

  /**
   * Reset the `wallet-read-projection` persistent stream position to `TAIL` via the
   * Axon Server admin REST API v2, so historical events are re-delivered (with a replay
   * indicator) to the `POST /internal/events/axon` adapter and re-applied by the
   * unchanged, idempotent projection handlers.
   *
   * Two verified calls against Axon Server 2026.0.6:
   *   1. GET  /v2/persistentstreams?context={ctx}
   *          -> resolve the stream UUID (`id`) from its human name (`name`), since the
   *             reset path is keyed by the stream UUID, not the name.
   *   2. PATCH /v2/persistentstreams/{streamId}?targetContext={ctx}&type=TAIL
   *          -> reset the position to TAIL (full-history replay). `type` is a required
   *             query param with enum POSITION | HEAD | TAIL | DATETIME.
   *
   * On any failure this THROWS (the caller maps it to a non-2xx HTTP response); the
   * error message states that the tables were already TRUNCATED so the operator knows
   * the Read Model is empty and the stream must be reset manually.
   */
  private async resetStreamToTail(tablesCleared: string[]): Promise<void> {
    const { axonAdminUrl, persistentStreamName, axonContext } = this.config;
    const ctx = encodeURIComponent(axonContext);

    // --- 1) Resolve the persistent-stream UUID from its name. --------------------
    const listUrl = `${axonAdminUrl}/v2/persistentstreams?context=${ctx}`;
    let streamId: string;
    try {
      const listResp = await fetch(listUrl, {
        method: 'GET',
        headers: { Accept: 'application/json' },
      });
      if (!listResp.ok) {
        const responseText = await this.safeReadBody(listResp);
        throw new Error(
          `listing persistent streams returned status ${listResp.status}. Response: ${responseText}`,
        );
      }
      const streams = (await listResp.json()) as Array<{
        id?: string;
        name?: string;
      }>;
      const match = Array.isArray(streams)
        ? streams.find((s) => s?.name === persistentStreamName)
        : undefined;
      if (!match?.id) {
        throw new Error(
          `no persistent stream named "${persistentStreamName}" found in context "${axonContext}". ` +
            'Register it first (see infrastructure/axon/register-projection.{ps1,sh}).',
        );
      }
      streamId = match.id;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      throw new Error(
        `Read Model tables were TRUNCATED [${tablesCleared.join(', ')}] but resolving the ` +
          `persistent-stream id failed (${message}). Confirm the admin API is reachable at ` +
          `${axonAdminUrl} (:8024) and reset "${persistentStreamName}" to TAIL manually ` +
          '(see register-projection.sh).',
      );
    }

    // --- 2) Reset the stream position to TAIL. -----------------------------------
    const resetUrl =
      `${axonAdminUrl}/v2/persistentstreams/${encodeURIComponent(streamId)}` +
      `?targetContext=${ctx}&type=TAIL`;

    this.logger.log(
      `Resetting persistent stream "${persistentStreamName}" (id ${streamId}) to TAIL via ${resetUrl}`,
    );

    let response: Response;
    try {
      response = await fetch(resetUrl, {
        method: 'PATCH',
        headers: { Accept: 'application/json' },
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      throw new Error(
        `Read Model tables were TRUNCATED [${tablesCleared.join(', ')}] but the persistent-stream ` +
          `reset request to Axon Server failed to send (${message}). ` +
          `Confirm the admin API is reachable at ${axonAdminUrl} (:8024) and reset ` +
          `"${persistentStreamName}" to TAIL manually (see register-projection.sh).`,
      );
    }

    if (!response.ok) {
      const responseText = await this.safeReadBody(response);
      throw new Error(
        `Read Model tables were TRUNCATED [${tablesCleared.join(', ')}] but the persistent-stream ` +
          `reset returned an unexpected status ${response.status}. ` +
          `Confirm the admin API at ${axonAdminUrl} (:8024) and reset "${persistentStreamName}" to ` +
          `TAIL. Response: ${responseText}`,
      );
    }

    this.logger.log(
      `Persistent stream "${persistentStreamName}" reset to TAIL accepted (status ${response.status}).`,
    );
  }
  /** Best-effort read of a response body for error reporting; never throws. */
  private async safeReadBody(response: Response): Promise<string> {
    try {
      const text = await response.text();
      return text.length > 500 ? `${text.slice(0, 500)}…` : text;
    } catch {
      return '(unreadable response body)';
    }
  }
}
