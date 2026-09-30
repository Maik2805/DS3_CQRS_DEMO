/**
 * Configuration for the projection (task 15.2, Req 18.3).
 *
 * The only setting is the eventual-consistency demo delay `READ_PROJECTION_DELAY_MS`:
 * an artificial pause applied BEFORE each event is projected into PostgreSQL, so the
 * window in which the Write Side / Event Store is already updated but the Read Model
 * still lags becomes observable in the demo (original spec §16).
 *
 * Resolved from the environment once, mirroring the rebuild.config.ts pattern:
 *   READ_PROJECTION_DELAY_MS -> integer milliseconds; default 0 (a true no-op — no
 *                               artificial latency is introduced).
 *
 * The value is clamped to a non-negative integer: a missing, non-numeric, or negative
 * value falls back to 0 so a misconfiguration never blocks the projection indefinitely.
 */
export interface ProjectionEnv {
  READ_PROJECTION_DELAY_MS?: string;
}

export interface ProjectionConfig {
  /** Artificial pre-projection delay in milliseconds. 0 = disabled (no delay). */
  readProjectionDelayMs: number;
}

export function buildProjectionConfig(
  env: ProjectionEnv = process.env,
): ProjectionConfig {
  const raw = env.READ_PROJECTION_DELAY_MS;
  const parsed = raw === undefined ? 0 : Number.parseInt(raw, 10);
  const readProjectionDelayMs =
    Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : 0;
  return { readProjectionDelayMs };
}

/** DI token carrying the resolved {@link ProjectionConfig}. */
export const PROJECTION_CONFIG = Symbol('PROJECTION_CONFIG');
