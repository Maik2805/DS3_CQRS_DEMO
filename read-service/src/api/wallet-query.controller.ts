import { Controller, Get, Param, Query } from '@nestjs/common';

import {
  WalletQueryService,
  type WalletBalanceView,
  type WalletDetailView,
  type WalletTransactionView,
} from '../queries/wallet-query.service';

/** Default page size for the transactions query when `limit` is absent/invalid. */
const DEFAULT_LIMIT = 50;
/** Hard cap on `limit` so a single request can never fetch an unbounded page. */
const MAX_LIMIT = 200;
/** Default offset when `offset` is absent/invalid. */
const DEFAULT_OFFSET = 0;

/**
 * Read API controller for wallet queries (task 10.1, Req 11.1–11.4).
 *
 * Routes (base path `api/wallets`):
 *   GET /api/wallets/{walletId}/balance       -> { walletId, currency, balance, updatedAt }
 *   GET /api/wallets/{walletId}               -> { walletId, ownerId, currency, balance, updatedAt }
 *   GET /api/wallets/{walletId}/transactions  -> WalletTransactionView[] (occurred_at DESC)
 *
 * The controller is a thin HTTP adapter: it parses/validates paging params and delegates
 * to the read-only {@link WalletQueryService}. Absent wallets become 404 via the service's
 * NotFoundException. These handlers perform no write side effects (Req 11.6).
 *
 * NOTE ON ROUTE ORDER: the more specific `/:walletId/balance` and `/:walletId/transactions`
 * routes are declared before the catch-all `/:walletId` detail route. Nest matches in
 * declaration order, so this prevents `balance`/`transactions` from being swallowed as a
 * `walletId` value.
 */
@Controller('api/wallets')
export class WalletQueryController {
  constructor(private readonly queryService: WalletQueryService) {}

  /** GET /api/wallets/{walletId}/balance (Req 11.1). */
  @Get(':walletId/balance')
  getBalance(
    @Param('walletId') walletId: string,
  ): Promise<WalletBalanceView> {
    return this.queryService.getBalance(walletId);
  }

  /** GET /api/wallets/{walletId}/transactions (Req 11.3). */
  @Get(':walletId/transactions')
  getTransactions(
    @Param('walletId') walletId: string,
    @Query('limit') limit?: string,
    @Query('offset') offset?: string,
  ): Promise<WalletTransactionView[]> {
    return this.queryService.getTransactions(walletId, {
      limit: this.parseLimit(limit),
      offset: this.parseOffset(offset),
    });
  }

  /** GET /api/wallets/{walletId} (Req 11.2). Declared last (see class note). */
  @Get(':walletId')
  getWalletDetail(
    @Param('walletId') walletId: string,
  ): Promise<WalletDetailView> {
    return this.queryService.getWalletDetail(walletId);
  }

  // --- paging param parsing ------------------------------------------------

  /**
   * Parse `limit` to a positive integer within [1, MAX_LIMIT]. Missing, non-numeric,
   * or non-positive values fall back to DEFAULT_LIMIT; oversized values are capped.
   */
  private parseLimit(raw?: string): number {
    const parsed = this.parseInteger(raw);
    if (parsed === undefined || parsed <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(parsed, MAX_LIMIT);
  }

  /**
   * Parse `offset` to a non-negative integer. Missing, non-numeric, or negative values
   * fall back to DEFAULT_OFFSET (0).
   */
  private parseOffset(raw?: string): number {
    const parsed = this.parseInteger(raw);
    if (parsed === undefined || parsed < 0) {
      return DEFAULT_OFFSET;
    }
    return parsed;
  }

  /** Parse a base-10 integer; returns undefined for absent or non-integer input. */
  private parseInteger(raw?: string): number | undefined {
    if (raw === undefined || raw.trim() === '') {
      return undefined;
    }
    const value = Number(raw);
    if (!Number.isInteger(value)) {
      return undefined;
    }
    return value;
  }
}
