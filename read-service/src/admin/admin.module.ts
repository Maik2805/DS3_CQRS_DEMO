import { Module } from '@nestjs/common';

import { AdminController } from './admin.controller';
import { buildRebuildConfig, REBUILD_CONFIG } from './rebuild.config';
import { RebuildService } from './rebuild.service';

/**
 * Read Side admin module (task 11.1, Requirements 12.1, 12.2, 12.3).
 *
 * Wires `POST /admin/projection/rebuild`: the {@link AdminController} guards the request
 * (X-Admin-Token) and delegates to {@link RebuildService}, which TRUNCATEs the projection
 * tables and resets the `wallet-read-projection` persistent stream to TAIL so Axon Server
 * re-delivers the full history.
 *
 * The Drizzle handle (DRIZZLE token) the RebuildService uses for TRUNCATE is supplied by
 * the global DatabaseModule (imported at the app root), so this module only needs to
 * declare the controller, the service, and the resolved {@link REBUILD_CONFIG}.
 */
@Module({
  controllers: [AdminController],
  providers: [
    RebuildService,
    { provide: REBUILD_CONFIG, useFactory: () => buildRebuildConfig() },
  ],
})
export class AdminModule {}
