import { Module } from '@nestjs/common';

import { WalletQueryService } from './wallet-query.service';

/**
 * Query layer module (task 10.1).
 *
 * Provides and exports the read-only {@link WalletQueryService}, which resolves wallet
 * balance/detail/transaction queries exclusively from PostgreSQL (Req 11.5, 11.6). The
 * Drizzle handle it injects (DRIZZLE token) comes from the global DatabaseModule, so no
 * database import is needed here.
 *
 * The Read API controllers live in the `api` layer (ApiModule) and depend on this module
 * for the query service.
 */
@Module({
  providers: [WalletQueryService],
  exports: [WalletQueryService],
})
export class QueriesModule {}
