import type { WalletEventType } from './domain-event';

/**
 * Versioned JSON Schemas for the four wallet domain events.
 *
 * These are copies of the shared contracts in `contracts/events/*.v1.schema.json`
 * (the single source of truth authored in task 2.1). They are embedded here — rather
 * than imported across the repo boundary — so the Read Service compiles and its
 * Docker image builds without depending on the `contracts/` folder being present in
 * the build context. If a contract changes, update the matching schema below.
 *
 * The adapter validates each Wrapped message's nested `payload` against the schema
 * selected by its `eventType` discriminator (Requirement 8.4: deserialize per the
 * versioned JSON contract, do not invent a format).
 */

const walletCreatedV1 = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: 'https://example.com/wallet-cqrs-poc/contracts/events/wallet-created.v1.schema.json',
  title: 'WalletCreated v1',
  type: 'object',
  properties: {
    eventType: { type: 'string', const: 'WalletCreated' },
    eventVersion: { type: 'integer', const: 1 },
    walletId: { type: 'string', minLength: 1 },
    ownerId: { type: 'string', minLength: 1 },
    currency: { type: 'string', const: 'COP' },
  },
  required: ['eventType', 'eventVersion', 'walletId', 'ownerId', 'currency'],
  additionalProperties: false,
} as const;

const moneyDepositedV1 = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: 'https://example.com/wallet-cqrs-poc/contracts/events/money-deposited.v1.schema.json',
  title: 'MoneyDeposited v1',
  type: 'object',
  properties: {
    eventType: { type: 'string', const: 'MoneyDeposited' },
    eventVersion: { type: 'integer', const: 1 },
    eventId: { type: 'string', minLength: 1 },
    walletId: { type: 'string', minLength: 1 },
    amount: { type: 'number', exclusiveMinimum: 0 },
    currency: { type: 'string', const: 'COP' },
    occurredAt: { type: 'string', format: 'date-time' },
  },
  required: [
    'eventType',
    'eventVersion',
    'eventId',
    'walletId',
    'amount',
    'currency',
    'occurredAt',
  ],
  additionalProperties: false,
} as const;

const moneyWithdrawnV1 = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: 'https://example.com/wallet-cqrs-poc/contracts/events/money-withdrawn.v1.schema.json',
  title: 'MoneyWithdrawn v1',
  type: 'object',
  properties: {
    eventType: { type: 'string', const: 'MoneyWithdrawn' },
    eventVersion: { type: 'integer', const: 1 },
    eventId: { type: 'string', minLength: 1 },
    walletId: { type: 'string', minLength: 1 },
    amount: { type: 'number', exclusiveMinimum: 0 },
    currency: { type: 'string', const: 'COP' },
    occurredAt: { type: 'string', format: 'date-time' },
  },
  required: [
    'eventType',
    'eventVersion',
    'eventId',
    'walletId',
    'amount',
    'currency',
    'occurredAt',
  ],
  additionalProperties: false,
} as const;

const moneyTransferredV1 = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  $id: 'https://example.com/wallet-cqrs-poc/contracts/events/money-transferred.v1.schema.json',
  title: 'MoneyTransferred v1',
  type: 'object',
  properties: {
    eventType: { type: 'string', const: 'MoneyTransferred' },
    eventVersion: { type: 'integer', const: 1 },
    eventId: { type: 'string', minLength: 1 },
    transferId: { type: 'string', minLength: 1 },
    sourceWalletId: { type: 'string', minLength: 1 },
    targetWalletId: { type: 'string', minLength: 1 },
    amount: { type: 'number', exclusiveMinimum: 0 },
    currency: { type: 'string', const: 'COP' },
    occurredAt: { type: 'string', format: 'date-time' },
  },
  required: [
    'eventType',
    'eventVersion',
    'eventId',
    'transferId',
    'sourceWalletId',
    'targetWalletId',
    'amount',
    'currency',
    'occurredAt',
  ],
  additionalProperties: false,
} as const;

/** Map from event type discriminator to its versioned JSON Schema. */
export const EVENT_SCHEMAS: Record<WalletEventType, object> = {
  WalletCreated: walletCreatedV1,
  MoneyDeposited: moneyDepositedV1,
  MoneyWithdrawn: moneyWithdrawnV1,
  MoneyTransferred: moneyTransferredV1,
};

/** The event types this Read Side knows how to project. */
export const KNOWN_EVENT_TYPES: readonly WalletEventType[] = [
  'WalletCreated',
  'MoneyDeposited',
  'MoneyWithdrawn',
  'MoneyTransferred',
];
