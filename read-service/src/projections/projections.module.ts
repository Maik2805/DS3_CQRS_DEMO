import { Module } from '@nestjs/common';

import { PROJECTION_PORT } from '../events/projection.port';
import {
  buildProjectionConfig,
  PROJECTION_CONFIG,
} from './projection.config';
import { WalletProjectionService } from './wallet-projection.service';

/**
 * Read Side projections module (task 9.2).
 *
 * Provides the real transactional, idempotent PostgreSQL projection and binds it to
 * the {@link PROJECTION_PORT} token that the event-reception adapter (EventsModule,
 * task 8.2) depends on. Exporting the token lets EventsModule consume the real
 * projection by importing this module — replacing the temporary LoggingProjectionAdapter.
 *
 * The Drizzle handle (DRIZZLE) is supplied by the global DatabaseModule.
 */
@Module({
  providers: [
    WalletProjectionService,
    { provide: PROJECTION_PORT, useExisting: WalletProjectionService },
    // Eventual-consistency demo delay (task 15.2); resolved once from the environment.
    { provide: PROJECTION_CONFIG, useFactory: () => buildProjectionConfig() },
  ],
  exports: [PROJECTION_PORT],
})
export class ProjectionsModule {}
