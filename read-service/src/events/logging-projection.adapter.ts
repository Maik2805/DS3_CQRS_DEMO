import { Injectable, Logger } from '@nestjs/common';

import type { NormalizedDomainEvent } from './domain-event';
import type { ProjectionPort } from './projection.port';

/**
 * Temporary, non-persistent {@link ProjectionPort} implementation.
 *
 * It only logs each normalized event so the EventsModule is fully wired and the
 * adapter compiles and runs end-to-end at the HTTP level. It performs NO database
 * writes and enforces NO idempotency.
 *
 * NOTE (task 9.2 done): this adapter is NO LONGER bound to PROJECTION_PORT. The real
 * transactional, idempotent PostgreSQL projection (WalletProjectionService, provided by
 * ProjectionsModule) is now the active binding. This file is retained for reference /
 * local debugging only and is not registered in any module.
 */
@Injectable()
export class LoggingProjectionAdapter implements ProjectionPort {
  private readonly logger = new Logger(LoggingProjectionAdapter.name);

  apply(event: NormalizedDomainEvent): Promise<void> {
    this.logger.warn(
      `[NO-OP projection] accepted ${event.eventType} eventId=${event.eventId} ` +
        `(no DB write — task 9.2 pending)`,
    );
    return Promise.resolve();
  }
}
