import {
  Global,
  Inject,
  Module,
  type DynamicModule,
  type OnModuleDestroy,
} from '@nestjs/common';
import { drizzle } from 'drizzle-orm/node-postgres';
import { Pool } from 'pg';

import { buildPoolConfig } from './database.config';
import { DRIZZLE, type WalletReadDatabase } from './database.tokens';
import { schema } from './schema';

/**
 * Internal DI token for the raw pg Pool, so the module can close it on shutdown.
 */
const PG_POOL = Symbol('PG_POOL');

/**
 * Global database module for the Read Side.
 *
 * Constructs a single pg Pool + Drizzle instance from the READ_DB_* environment variables
 * and exposes the Drizzle handle under the {@link DRIZZLE} token for injection by projection
 * handlers and query services.
 *
 * The schema itself is applied by PostgreSQL via docker-entrypoint-initdb.d (init.sql); this
 * module does NOT run migrations at startup and never drops/recreates tables. A
 * `drizzle.config.ts` at the read-service root points at the schema for optional reference
 * generation only.
 */
@Global()
@Module({})
export class DatabaseModule implements OnModuleDestroy {
  constructor(@Inject(PG_POOL) private readonly pool: Pool) {}

  static forRoot(): DynamicModule {
    const poolProvider = {
      provide: PG_POOL,
      useFactory: (): Pool => new Pool(buildPoolConfig()),
    };

    const drizzleProvider = {
      provide: DRIZZLE,
      useFactory: (pool: Pool): WalletReadDatabase =>
        drizzle(pool, { schema }),
      inject: [PG_POOL],
    };

    return {
      module: DatabaseModule,
      providers: [poolProvider, drizzleProvider],
      exports: [DRIZZLE],
    };
  }

  /**
   * Gracefully close the connection pool when the Nest application shuts down.
   */
  async onModuleDestroy(): Promise<void> {
    await this.pool.end();
  }
}
