/**
 * DI token for the Drizzle database handle.
 *
 * Other modules (projections, queries) inject the configured Drizzle instance via:
 *   `@Inject(DRIZZLE) private readonly db: WalletReadDatabase`
 *
 * Kept in a dedicated module to avoid circular imports between the module definition
 * and consumers that only need the token + type.
 */
import type { NodePgDatabase } from 'drizzle-orm/node-postgres';

import type { schema } from './schema';

export const DRIZZLE = Symbol('DRIZZLE');

/**
 * The concrete Drizzle database type bound to the Read Model schema.
 */
export type WalletReadDatabase = NodePgDatabase<typeof schema>;
