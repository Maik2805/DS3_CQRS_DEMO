/**
 * Normalized domain event handed from the Wrapped-payload adapter to the projection.
 *
 * This is the Read Side's internal representation of a single wallet domain event,
 * produced after the {@link EventNormalizer} parses one element of an Axon Server
 * `Wrapped` batch (see infrastructure/axon/integration-notes.md, task 8.1) and
 * validates its nested `payload` against the versioned JSON contract in
 * `contracts/events/`.
 *
 * The four concrete payload shapes mirror the JSON Schemas in `contracts/events/`.
 * They are intentionally plain data (no class instances) so this side shares only a
 * contract with the Java Write Side, never a compiled type.
 */

/** Discriminator carried by every wallet domain event contract (`eventType`). */
export type WalletEventType =
  | 'WalletCreated'
  | 'MoneyDeposited'
  | 'MoneyWithdrawn'
  | 'MoneyTransferred';

/** Payload of a `WalletCreated` v1 event (no `eventId`/`occurredAt` by contract). */
export interface WalletCreatedPayload {
  eventType: 'WalletCreated';
  eventVersion: 1;
  walletId: string;
  ownerId: string;
  currency: 'COP';
}

/** Payload of a `MoneyDeposited` v1 event. */
export interface MoneyDepositedPayload {
  eventType: 'MoneyDeposited';
  eventVersion: 1;
  eventId: string;
  walletId: string;
  amount: number;
  currency: 'COP';
  occurredAt: string;
}

/** Payload of a `MoneyWithdrawn` v1 event. */
export interface MoneyWithdrawnPayload {
  eventType: 'MoneyWithdrawn';
  eventVersion: 1;
  eventId: string;
  walletId: string;
  amount: number;
  currency: 'COP';
  occurredAt: string;
}

/** Payload of a `MoneyTransferred` v1 event. */
export interface MoneyTransferredPayload {
  eventType: 'MoneyTransferred';
  eventVersion: 1;
  eventId: string;
  transferId: string;
  sourceWalletId: string;
  targetWalletId: string;
  amount: number;
  currency: 'COP';
  occurredAt: string;
}

/** Any of the four validated wallet event payloads. */
export type WalletEventPayload =
  | WalletCreatedPayload
  | MoneyDepositedPayload
  | MoneyWithdrawnPayload
  | MoneyTransferredPayload;

/**
 * A single normalized domain event ready for projection.
 *
 * - `eventType`  — the contract discriminator, used to route to a projection handler.
 * - `eventId`    — idempotency key. For events whose contract carries `eventId`
 *                  (`MoneyDeposited`/`MoneyWithdrawn`/`MoneyTransferred`) it is the
 *                  payload value; for `WalletCreated` (no contract `eventId`) it is a
 *                  synthesized-but-stable id (see integration-notes.md §2).
 * - `payload`    — the validated event payload (shape depends on `eventType`).
 * - `streamIndex`— Axon Server global index (`index`), the persistent-stream cursor.
 * - `aggregateId`/`sequenceNumber`/`occurredAt` — optional envelope/payload metadata.
 */
export interface NormalizedDomainEvent {
  eventType: WalletEventType;
  eventId: string;
  payload: WalletEventPayload;
  streamIndex?: number;
  aggregateId?: string;
  sequenceNumber?: number;
  occurredAt?: string;
}
