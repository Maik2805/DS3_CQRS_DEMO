import type { Config } from 'drizzle-kit';

/**
 * Drizzle Kit configuration — REFERENCE / OPTIONAL GENERATION ONLY.
 *
 * The authoritative Read Model schema is created by PostgreSQL from
 * `infrastructure/postgres/init.sql` via docker-entrypoint-initdb.d on first startup.
 * The application does NOT run migrations at runtime and never drops/recreates tables.
 *
 * This file points Drizzle Kit at the schema module so a developer can optionally run
 * `npx drizzle-kit generate` to inspect generated SQL and confirm it matches init.sql.
 * Do not wire this into the container start command.
 */
export default {
  schema: './src/database/schema.ts',
  out: './drizzle',
  dialect: 'postgresql',
  dbCredentials: {
    host: process.env.READ_DB_HOST ?? 'postgres-read',
    port: Number.parseInt(process.env.READ_DB_PORT ?? '5432', 10),
    database: process.env.READ_DB_NAME ?? 'wallet_read',
    user: process.env.READ_DB_USER ?? 'wallet_read',
    password: process.env.READ_DB_PASSWORD ?? '',
    ssl: false,
  },
} satisfies Config;
