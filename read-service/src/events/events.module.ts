import { Module } from '@nestjs/common';

import { EventsController } from './events.controller';
import { EventNormalizer } from './event-normalizer';
import { ProjectionsModule } from '../projections/projections.module';

/**
 * Read Side event-reception module (task 8.2).
 *
 * Wires the `POST /internal/events/axon` adapter: it parses/validates the Axon Server
 * `Wrapped` batch (via {@link EventNormalizer}) and hands each normalized event to the
 * {@link ProjectionPort}.
 *
 * The PROJECTION_PORT token is provided (and exported) by {@link ProjectionsModule},
 * which binds it to the real transactional, idempotent PostgreSQL projection
 * (WalletProjectionService, task 9.2). Importing that module here replaces the former
 * temporary LoggingProjectionAdapter no-op binding. The Drizzle handle the projection
 * needs comes from the global DatabaseModule.
 */
@Module({
  imports: [ProjectionsModule],
  controllers: [EventsController],
  providers: [EventNormalizer],
})
export class EventsModule {}
