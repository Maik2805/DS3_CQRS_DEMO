import type { NormalizedDomainEvent } from './domain-event';

/**
 * Injection token for the projection port.
 *
 * The event-reception adapter (task 8.2) depends only on this port; the real
 * transactional, idempotent PostgreSQL projection is supplied by task 9.2 and bound
 * to this token in the projections module.
 */
export const PROJECTION_PORT = Symbol('PROJECTION_PORT');

/**
 * Port the event adapter calls, once per normalized event, in stream order.
 *
 * Contract (see integration-notes.md §3 and Requirements 10.5 / 16.3):
 * - `apply` MUST resolve only after the event has been durably committed (or
 *   recognised as an already-applied duplicate). The adapter returns HTTP 2xx to
 *   Axon Server only after every event in the batch has been accepted, which
 *   advances the persistent-stream cursor.
 * - If `apply` rejects, the adapter propagates a non-2xx response so Axon Server
 *   retries the batch. Implementations MUST NOT swallow failures into a silent
 *   success — that would logically lose an event.
 * - Delivery is at-least-once, so `apply` MUST be idempotent on `eventId`
 *   (task 9.2 relies on the `wallet_transaction.event_id` UNIQUE constraint).
 */
export interface ProjectionPort {
  apply(event: NormalizedDomainEvent): Promise<void>;
}
