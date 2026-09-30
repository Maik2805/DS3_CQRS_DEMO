import {
  Body,
  Controller,
  HttpCode,
  HttpStatus,
  Inject,
  InternalServerErrorException,
  Logger,
  Post,
} from '@nestjs/common';

import type { NormalizedDomainEvent } from './domain-event';
import { EventNormalizer } from './event-normalizer';
import { PROJECTION_PORT, type ProjectionPort } from './projection.port';

/**
 * Internal endpoint that receives domain events from Axon Server's native
 * persistent-stream HTTP integration (`Wrapped` wrapping, `application/json`).
 *
 * Flow (see infrastructure/axon/integration-notes.md, Requirements 8.1–8.4, 16.5):
 * 1. Log the raw body once on first receipt (locks the exact wire shape at runtime).
 * 2. Normalize the Wrapped batch into ordered domain events (parse + contract validate).
 * 3. Apply each event, in order, through the projection port.
 * 4. Return 2xx only after the whole batch is accepted; if the projection throws,
 *    propagate a non-2xx (500) so Axon Server retries — never a silent 200 (Req 16.3).
 *
 * This adapter does NOT use Axon Framework (Req 8.3) and does NOT write to the
 * database itself — persistence is the projection port's responsibility (task 9.2).
 */
@Controller('internal/events')
export class EventsController {
  private readonly logger = new Logger(EventsController.name);
  private rawBodyLogged = false;

  constructor(
    private readonly normalizer: EventNormalizer,
    @Inject(PROJECTION_PORT) private readonly projection: ProjectionPort,
  ) {}

  @Post('axon')
  @HttpCode(HttpStatus.OK)
  async receive(@Body() body: unknown): Promise<{ accepted: number }> {
    this.logRawBodyOnce(body);

    // Parse + validate. Invalid envelopes/payloads throw BadRequestException (400),
    // which Axon Server treats as a nack and retries — the batch is not lost.
    const events = this.normalizer.normalizeBatch(body);

    this.logger.log(
      `Received Wrapped batch: ${events.length} event(s) ` +
        `[${events.map((e) => e.eventType).join(', ')}]`,
    );

    // Apply strictly in order. Only after every event is accepted do we return 2xx,
    // which lets Axon Server advance the persistent-stream cursor.
    for (const event of events) {
      await this.applyOne(event);
    }

    return { accepted: events.length };
  }

  /** Apply a single event through the projection port with structured logging. */
  private async applyOne(event: NormalizedDomainEvent): Promise<void> {
    // Structured, correlation-friendly log (Req 16.5): always eventId, plus
    // walletId / transferId where the payload carries them.
    this.logger.log(
      `Projecting event ${JSON.stringify(this.logContext(event))}`,
    );

    try {
      await this.projection.apply(event);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      // Do NOT swallow — surface a non-2xx so Axon Server redelivers (Req 16.3).
      this.logger.error(
        `Projection failed for ${event.eventType} eventId=${event.eventId}: ${message}`,
      );
      throw new InternalServerErrorException(
        `Projection failed for eventId=${event.eventId}; event will be retried.`,
      );
    }
  }

  /** Build the structured log context for an event (Req 16.5). */
  private logContext(event: NormalizedDomainEvent): Record<string, unknown> {
    const payload = event.payload as unknown as Record<string, unknown>;
    const context: Record<string, unknown> = {
      eventType: event.eventType,
      eventId: event.eventId,
    };
    if (event.streamIndex !== undefined) {
      context.streamIndex = event.streamIndex;
    }
    if (typeof payload.walletId === 'string') {
      context.walletId = payload.walletId;
    }
    if (typeof payload.sourceWalletId === 'string') {
      context.sourceWalletId = payload.sourceWalletId;
    }
    if (typeof payload.targetWalletId === 'string') {
      context.targetWalletId = payload.targetWalletId;
    }
    if (typeof payload.transferId === 'string') {
      context.transferId = payload.transferId;
    }
    return context;
  }

  /**
   * Log the raw delivered body exactly once, to help lock the exact Wrapped shape
   * (field casing, wrapper vs bare array) against the running Axon Server.
   */
  private logRawBodyOnce(body: unknown): void {
    if (this.rawBodyLogged) {
      return;
    }
    this.rawBodyLogged = true;
    try {
      this.logger.log(
        `First Wrapped delivery — raw body (shape lock): ${JSON.stringify(body)}`,
      );
    } catch {
      this.logger.log('First Wrapped delivery — raw body could not be stringified.');
    }
  }
}
