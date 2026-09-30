import { Module } from '@nestjs/common';

import { AdminModule } from './admin/admin.module';
import { ApiModule } from './api/api.module';
import { DatabaseModule } from './database/database.module';
import { EventsModule } from './events/events.module';

/**
 * Root application module.
 *
 * The DatabaseModule is global and provides the Drizzle handle (DRIZZLE token) to
 * projection handlers and query services. The EventsModule exposes the
 * `POST /internal/events/axon` persistent-stream adapter (task 8.2). The ApiModule
 * exposes the Read API queries (balance / detail / transactions, task 10.1) and the
 * `/health` endpoints (task 10.2).
 *
 * The AdminModule exposes `POST /admin/projection/rebuild` for the Read Model
 * rebuild/replay demo (task 11.1).
 */
@Module({
  imports: [DatabaseModule.forRoot(), EventsModule, ApiModule, AdminModule],
  controllers: [],
  providers: [],
})
export class AppModule {}
