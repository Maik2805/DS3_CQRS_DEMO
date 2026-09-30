import type { PoolConfig } from 'pg';

/**
 * Resolves PostgreSQL connection settings for the Read Model from environment variables.
 *
 * Defaults match the compose.yml / design.md wiring for the `read-service` container so the
 * app works out of the box inside Docker Compose:
 *   READ_DB_HOST     -> postgres-read
 *   READ_DB_PORT     -> 5432   (container-internal; host maps 5433)
 *   READ_DB_NAME     -> wallet_read
 *   READ_DB_USER     -> wallet_read
 *   READ_DB_PASSWORD -> (no default; must be provided via .env)
 *
 * The Read Model is independent of any Write Side storage or credentials.
 */
export interface ReadDbEnv {
  READ_DB_HOST?: string;
  READ_DB_PORT?: string;
  READ_DB_NAME?: string;
  READ_DB_USER?: string;
  READ_DB_PASSWORD?: string;
}

export function buildPoolConfig(env: ReadDbEnv = process.env): PoolConfig {
  const host = env.READ_DB_HOST ?? 'postgres-read';
  const port = Number.parseInt(env.READ_DB_PORT ?? '5432', 10);
  const database = env.READ_DB_NAME ?? 'wallet_read';
  const user = env.READ_DB_USER ?? 'wallet_read';
  const password = env.READ_DB_PASSWORD;

  return {
    host,
    port,
    database,
    user,
    password,
  };
}
