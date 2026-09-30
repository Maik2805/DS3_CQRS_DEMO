/**
 * Configuration for the Read Model rebuild / replay operation (task 11.1, Req 12).
 *
 * All values are resolved from environment variables so the operation works both
 * inside Docker Compose (service-name hosts) and against a local/manual Axon Server,
 * without hardcoding infrastructure addresses in code.
 *
 * Defaults mirror infrastructure/axon/register-projection.sh / .ps1 and design.md:
 *   AXON_ADMIN_URL         -> http://axon-server:8024   (Axon Server admin/dashboard, container-internal)
 *   PERSISTENT_STREAM_NAME -> wallet-read-projection     (the event handler / persistent stream name)
 *   AXON_CONTEXT           -> wallet                      (bounded-context)
 *   ADMIN_TOKEN            -> (no default)                 (guards the endpoint; see AdminController)
 *   ADMIN_ENDPOINT_NAME    -> wallet-read-service          (the integration endpoint the handler is registered against)
 *
 * NOTE (CONFIRM @ :8024): the exact admin REST path/body for RESETTING a persistent
 * stream position is exposed by the running Axon Server's own API console
 * (Swagger/OpenAPI on host port 8024). The value below is the best-documented form;
 * confirm it against the instance before relying on it in a demo.
 */
export interface RebuildEnv {
  AXON_ADMIN_URL?: string;
  PERSISTENT_STREAM_NAME?: string;
  AXON_CONTEXT?: string;
  ADMIN_TOKEN?: string;
  ADMIN_ENDPOINT_NAME?: string;
}

export interface RebuildConfig {
  /** Axon Server admin/dashboard base URL (host port 8024). Trailing slash stripped. */
  axonAdminUrl: string;
  /** Persistent stream / event-handler name to reset (design: `wallet-read-projection`). */
  persistentStreamName: string;
  /** Axon Server bounded-context. */
  axonContext: string;
  /** Integration endpoint the event handler is registered against. */
  endpointName: string;
  /** Token that must be presented in the `X-Admin-Token` header (undefined = guard disabled/open). */
  adminToken?: string;
}

export function buildRebuildConfig(env: RebuildEnv = process.env): RebuildConfig {
  const axonAdminUrl = (env.AXON_ADMIN_URL ?? 'http://axon-server:8024').replace(
    /\/+$/,
    '',
  );
  return {
    axonAdminUrl,
    persistentStreamName: env.PERSISTENT_STREAM_NAME ?? 'wallet-read-projection',
    axonContext: env.AXON_CONTEXT ?? 'wallet',
    endpointName: env.ADMIN_ENDPOINT_NAME ?? 'wallet-read-service',
    adminToken: env.ADMIN_TOKEN,
  };
}

/** DI token carrying the resolved {@link RebuildConfig}. */
export const REBUILD_CONFIG = Symbol('REBUILD_CONFIG');
