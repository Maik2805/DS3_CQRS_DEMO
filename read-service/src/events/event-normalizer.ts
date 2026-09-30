import { BadRequestException, Injectable, Logger } from '@nestjs/common';
import Ajv2020, { type ValidateFunction } from 'ajv/dist/2020';
import addFormats from 'ajv-formats';

import type {
  NormalizedDomainEvent,
  WalletEventPayload,
  WalletEventType,
} from './domain-event';
import { EVENT_SCHEMAS, KNOWN_EVENT_TYPES } from './event-schemas';

/**
 * Parses and normalizes an Axon Server `Wrapped` event-delivery body into an ordered
 * list of {@link NormalizedDomainEvent}.
 *
 * The exact wire shape is documented in `infrastructure/axon/integration-notes.md`
 * (task 8.1). Per that note the precise JSON casing and whether the top level is a
 * bare array or an object wrapping an array must be locked at runtime, so this parser
 * is deliberately tolerant:
 * - accepts a bare array, or an object with an `events`/`items`/`messages`/`payload`/
 *   `body`/`data` array property;
 * - reads message fields case-insensitively (`name`, `payloadType`, `index`,
 *   `aggregateId`, `sequenceNumber`, `dateTime`, `payload`, `messageId`, ...);
 * - derives the event type from `name`, falling back to `payloadType`.
 *
 * Each message's nested `payload` (our contract) is validated against the versioned
 * JSON Schema selected by the event type. Invalid payloads / unknown types throw
 * {@link BadRequestException} so the whole batch is rejected (non-2xx → Axon retries),
 * rather than silently dropping an event.
 */
@Injectable()
export class EventNormalizer {
  private readonly logger = new Logger(EventNormalizer.name);
  private readonly ajv: Ajv2020;
  private readonly validators = new Map<WalletEventType, ValidateFunction>();

  constructor() {
    this.ajv = new Ajv2020({ allErrors: true, strict: false });
    // Enable `date-time` (and other) format assertions used by the money contracts.
    addFormats(this.ajv);
    for (const eventType of KNOWN_EVENT_TYPES) {
      this.validators.set(
        eventType,
        this.ajv.compile(EVENT_SCHEMAS[eventType]),
      );
    }
  }

  /**
   * Normalize a raw Wrapped body into ordered domain events.
   *
   * @param rawBody the parsed JSON body delivered to POST /internal/events/axon
   * @returns normalized events in the same order they appear in the batch
   * @throws BadRequestException if the envelope shape, an event type, or a payload is invalid
   */
  normalizeBatch(rawBody: unknown): NormalizedDomainEvent[] {
    const messages = this.extractMessageArray(rawBody);
    return messages.map((message, position) =>
      this.normalizeMessage(message, position),
    );
  }

  /** Resolve the batch to an array of message objects, tolerating a wrapper object. */
  private extractMessageArray(rawBody: unknown): Record<string, unknown>[] {
    let candidate: unknown = rawBody;

    if (!Array.isArray(candidate) && this.isObject(candidate)) {
      // Tolerate an object that wraps the array under a common property name.
      const wrapper = candidate;
      const wrapperKeys = ['events', 'items', 'messages', 'payload', 'body', 'data'];
      const key = wrapperKeys.find((k) => Array.isArray(wrapper[k]));
      if (key) {
        candidate = wrapper[key];
      } else {
        // A single message object (not wrapped in an array).
        candidate = [wrapper];
      }
    }

    if (!Array.isArray(candidate)) {
      throw new BadRequestException(
        'Wrapped event body must be a JSON array of event messages (or an object wrapping one).',
      );
    }

    return candidate.map((element, position) => {
      if (!this.isObject(element)) {
        throw new BadRequestException(
          `Wrapped event message at index ${position} is not a JSON object.`,
        );
      }
      return element;
    });
  }

  /** Normalize a single Wrapped message into a domain event. */
  private normalizeMessage(
    message: Record<string, unknown>,
    position: number,
  ): NormalizedDomainEvent {
    const eventType = this.resolveEventType(message, position);

    const payloadValue = this.readField(message, 'payload');
    if (!this.isObject(payloadValue)) {
      throw new BadRequestException(
        `Wrapped message at index ${position} (${eventType}) has a missing or non-object payload.`,
      );
    }

    this.validatePayload(eventType, payloadValue, position);
    const payload = payloadValue as unknown as WalletEventPayload;

    const streamIndex = this.readNumber(message, ['index', 'globalIndex']);
    const aggregateId = this.readString(message, ['aggregateId']);
    const sequenceNumber = this.readNumber(message, ['sequenceNumber']);
    const messageId = this.readString(message, ['messageId']);
    const dateTime = this.readString(message, ['dateTime']);

    const occurredAt =
      this.payloadOccurredAt(payload) ?? dateTime ?? undefined;

    const eventId = this.resolveEventId(eventType, payload, {
      aggregateId,
      sequenceNumber,
      messageId,
      streamIndex,
    });

    return {
      eventType,
      eventId,
      payload,
      streamIndex,
      aggregateId,
      sequenceNumber,
      occurredAt,
    };
  }

  /** Derive the event type from `name`, falling back to `payloadType`. */
  private resolveEventType(
    message: Record<string, unknown>,
    position: number,
  ): WalletEventType {
    const name =
      this.readString(message, ['name', 'eventName']) ??
      this.readString(message, ['payloadType', 'type']);

    if (!name) {
      throw new BadRequestException(
        `Wrapped message at index ${position} is missing an event type (name/payloadType).`,
      );
    }

    if (!KNOWN_EVENT_TYPES.includes(name as WalletEventType)) {
      // Requirement 10.4 spirit: unknown types are explicit errors, not silent drops.
      throw new BadRequestException(
        `Unknown event type "${name}" in Wrapped message at index ${position}.`,
      );
    }

    return name as WalletEventType;
  }

  /** Validate a payload against its versioned JSON Schema. */
  private validatePayload(
    eventType: WalletEventType,
    payload: Record<string, unknown>,
    position: number,
  ): void {
    const validate = this.validators.get(eventType);
    // validator always present for a known type; guard defensively.
    if (!validate) {
      throw new BadRequestException(
        `No schema registered for event type "${eventType}".`,
      );
    }
    if (!validate(payload)) {
      const details = this.ajv.errorsText(validate.errors, {
        separator: '; ',
      });
      throw new BadRequestException(
        `Payload for ${eventType} at index ${position} failed contract validation: ${details}`,
      );
    }
  }

  /**
   * Resolve the idempotency key.
   *
   * Prefer the payload's own `eventId` (present for the three money events by
   * contract). `WalletCreated` has none, so synthesize a stable id from
   * aggregateId + sequenceNumber, falling back to messageId or the global index
   * (see integration-notes.md §2).
   */
  private resolveEventId(
    eventType: WalletEventType,
    payload: WalletEventPayload,
    envelope: {
      aggregateId?: string;
      sequenceNumber?: number;
      messageId?: string;
      streamIndex?: number;
    },
  ): string {
    const contractEventId = this.readString(
      payload as unknown as Record<string, unknown>,
      ['eventId'],
    );
    if (contractEventId) {
      return contractEventId;
    }

    // WalletCreated (or any contract without eventId): synthesize a stable id.
    const walletId = this.readString(
      payload as unknown as Record<string, unknown>,
      ['walletId'],
    );
    const aggregateId = envelope.aggregateId ?? walletId;

    if (aggregateId && envelope.sequenceNumber !== undefined) {
      return `${eventType}:${aggregateId}:${envelope.sequenceNumber}`;
    }
    if (envelope.messageId) {
      return `${eventType}:msg:${envelope.messageId}`;
    }
    if (envelope.streamIndex !== undefined) {
      return `${eventType}:idx:${envelope.streamIndex}`;
    }
    // Last resort: aggregate id alone (WalletCreated is once-per-wallet).
    if (aggregateId) {
      return `${eventType}:${aggregateId}`;
    }
    throw new BadRequestException(
      `Cannot derive a stable eventId for ${eventType}: no eventId, aggregateId, messageId, or index present.`,
    );
  }

  private payloadOccurredAt(payload: WalletEventPayload): string | undefined {
    return this.readString(payload as unknown as Record<string, unknown>, [
      'occurredAt',
    ]);
  }

  // --- case-insensitive field readers -------------------------------------

  /** Read a field by any of the given keys, case-insensitively. */
  private readField(
    obj: Record<string, unknown>,
    ...keys: string[]
  ): unknown {
    const lowerKeys = keys.map((k) => k.toLowerCase());
    for (const actualKey of Object.keys(obj)) {
      if (lowerKeys.includes(actualKey.toLowerCase())) {
        return obj[actualKey];
      }
    }
    return undefined;
  }

  private readString(
    obj: Record<string, unknown>,
    keys: string[],
  ): string | undefined {
    const value = this.readField(obj, ...keys);
    return typeof value === 'string' && value.length > 0 ? value : undefined;
  }

  private readNumber(
    obj: Record<string, unknown>,
    keys: string[],
  ): number | undefined {
    const value = this.readField(obj, ...keys);
    if (typeof value === 'number' && Number.isFinite(value)) {
      return value;
    }
    if (typeof value === 'string' && value.trim() !== '') {
      const parsed = Number(value);
      return Number.isFinite(parsed) ? parsed : undefined;
    }
    return undefined;
  }

  private isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
  }
}
