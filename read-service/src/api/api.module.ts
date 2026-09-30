import { Module } from '@nestjs/common';

import { QueriesModule } from '../queries/queries.module';
import { HealthController } from './health.controller';
import { WalletQueryController } from './wallet-query.controller';

/**
 * Read API module (tasks 10.1 + 10.2).
 *
 * Exposes the HTTP query surface of the Read Service:
 *   - {@link WalletQueryController}: balance / detail / transactions (Req 11.1–11.4).
 *   - {@link HealthController}: `/health` liveness + `/health/ready` readiness (Req 16.4).
 *
 * Imports {@link QueriesModule} for the read-only WalletQueryService. The HealthController
 * injects the Drizzle handle directly from the global DatabaseModule for its readiness
 * `SELECT 1`.
 */
@Module({
  imports: [QueriesModule],
  controllers: [WalletQueryController, HealthController],
})
export class ApiModule {}
