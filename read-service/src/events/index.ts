export { EventsModule } from './events.module';
export { EventsController } from './events.controller';
export { EventNormalizer } from './event-normalizer';
export { PROJECTION_PORT, type ProjectionPort } from './projection.port';
export type {
  NormalizedDomainEvent,
  WalletEventType,
  WalletEventPayload,
  WalletCreatedPayload,
  MoneyDepositedPayload,
  MoneyWithdrawnPayload,
  MoneyTransferredPayload,
} from './domain-event';
