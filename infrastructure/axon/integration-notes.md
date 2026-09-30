# Axon Server Integration Notes — Event Delivery to the NestJS Read Side

Verification note for spec task **8.1** (`wallet-cqrs-poc`). This file documents the
**confirmed, documented** mechanism Axon Server uses to push events over HTTP to an external
(non-Axon-Framework) consumer, so tasks **8.2** (the `POST /internal/events/axon` adapter) and
**8.3** (persistent-stream registration from `TAIL`) implement against a real format rather than
an invented one.

- **Axon Server version (pinned):** `docker.axoniq.io/axoniq/axonserver:2026.0.6` (see `versions.md`).
- **Verification date:** 2026-08.
- **Primary sources (official AxonIQ docs):**
  - Axon Server 2026.0 — Integrations: <https://docs.axoniq.io/axon-server-reference/v2026.0/axon-server/administration/integration/>
  - Axon Server 2026.0 — Persistent Streams: <https://docs.axoniq.io/axon-server-reference/v2026.0/axon-server/administration/persistent-streams/>
  - Axon Server 2026.0 — Introduction: <https://docs.axoniq.io/axon-server-reference/v2026.0/>
  - Axon Server release notes (persistent streams GA history): <https://github.com/AxonIQ/reference-guide/blob/master/release-notes/rn-axon-server/rn-as-major-releases.md>
  - (v2025.0 Integrations page — identical content, cross-checked): <https://docs.axoniq.io/axon-server-reference/v2025.0/axon-server/administration/integration/>

> Content was rephrased/summarized from the sources above for compliance with licensing restrictions.

---

## 1. Confirmed mechanism

The native feature is called **Axon Server Integration** (the "Integrations" section of the admin
docs). It exists specifically for "applications that do not use the Axon Framework, applications
that may not be running in a Java virtual machine" — exactly our NestJS Read Side.

The model has two levels:

1. **Endpoint** — a logical registration of the external application: its base URL, protocol
   (HTTP(s) or RSocket), a **wrapping type**, a **content type**, a **health URL**, and default
   command/query/event URLs.
2. **Event handler** — registered against an endpoint. Registering an event handler is what
   creates a **persistent stream** in Axon Server that feeds events to that handler's URL. The
   docs state directly: *"This name will be the name of the persistent stream serving the events"*
   and *"Event handlers are backed by persistent streams."*

So the chain for our POC is:

```
Axon Server context 'wallet'  --(persistent stream)-->  event handler endpoint (HTTP POST)  -->  Read Service
```

- **Protocol:** HTTP(s). All handlers must be **POST**; the health URL must be a **GET**.
- **`Wrapped` is the correct/current term for this version.** The wrapping type has exactly two
  documented values, `Raw` and `Wrapped`, and this is unchanged between v2025.0 and v2026.0
  (both integration pages carry identical wording). We use **`Wrapped`** because its documented
  benefits are "easier access to the message metadata" and, critically, **"support for batches of
  events"** — for a `Wrapped` **event** handler, *"Axon Server generates a JSON message that
  contains a list of events."*
- **Persistent streams are GA**, not preview, in this line (preview in 2024.0, on-by-default
  since; they back both Axon Framework event processors and integration event handlers).

### Raw vs Wrapped (why Wrapped)

| Wrapping | Body delivered to handler | Metadata location | Batching |
| --- | --- | --- | --- |
| `Raw` | the event payload itself (single event) | HTTP headers (`AxonIQ-EventName`, `AxonIQ-Index`, `AxonIQ-AggregateId`, `AxonIQ-AggregateType`, `AxonIQ-SequenceNumber`, `AxonIQ-DateTime`, `AxonIQ-MessageId`, `Content-Type`) | one event per request |
| `Wrapped` | a **JSON array of event messages**, each with payload + metadata fields | inside each JSON element | **multiple events per request** (`batch size`) |

We choose **`Wrapped`** so the adapter parses a self-describing JSON envelope (event name + payload
+ index/id) and can accept batches.

---

## 2. Delivered payload / envelope structure (Wrapped event handler)

The integration docs state the delivered body for a Wrapped event handler is "a JSON message that
contains a **list of events**. Each element includes a serialized event and all additional
information relevant to the event," and refers to the running server's API documentation for the
byte-level shape.

The **field vocabulary is documented explicitly** in two places on the integration page, and both
agree — they are the same fields used for the symmetric *Sending events (Wrapped)* API and the
per-event metadata keys listed for RSocket:

Per-event fields (from the RSocket `axonserver/x-*` metadata list and the Wrapped send format):

| Field (documented meaning) | RSocket key | Notes |
| --- | --- | --- |
| event payload | (body) | If endpoint content-type is `application/json`, the JSON payload is embedded as JSON. For `application/xml` / `text/*` it is a string; otherwise a base64 string. |
| event name / type | `x-EventName` | How the **event type** is conveyed. This is the discriminator the adapter switches on. |
| payload type | `x-PayloadType` | The serialized payload's type (may equal the event name). |
| message id | `x-MessageId` | Unique per delivered message. |
| date/time | `x-DateTime` | When the event was created. |
| global index | `x-Index` | Global position of the event in the store — the stream progress cursor. |
| aggregate id | `x-AggregateId` | Optional. |
| aggregate type | `x-AggregateType` | Optional. |
| sequence number | `x-SequenceNumber` | Optional; sequence within the aggregate. |
| metadata | `x-Metadata` | Optional event metadata. |

The **Sending events (Wrapped)** API (the inverse direction, publish-to-Axon) uses these JSON key
names for exactly the same concepts, and is the best-documented concrete shape:

```jsonc
// Wrapped "send events" body — POST /v2/events?context=default  (documented example)
[{
  "payload":       { "id": "901aa5ce-...", "text": "New customer created" },
  "name":          "local.application.client.Event",
  "aggregateId":   "901aa5ce-...",
  "aggregateType": "Aggregate",
  "sequenceNumber": 0,
  "dateTime":      "2022-09-22T21:37:00.000+00:00"
  // may also carry: metaData
}]
```

### Expected inbound envelope for our handler (best-supported model)

Combining the "list of events" statement with the documented field names, the body delivered to
`POST /internal/events/axon` is expected to be a **JSON array**, each element shaped like:

```jsonc
[
  {
    "payload": {
      // OUR versioned JSON event contract, embedded as JSON because the endpoint
      // content-type is application/json. Example: MoneyDeposited v1
      "eventVersion": 1,
      "eventId": "0c9f...-...",
      "walletId": "wallet-001",
      "amount": "25000.00",
      "currency": "COP",
      "occurredAt": "2026-08-01T12:00:00.000Z"
    },
    "name": "MoneyDeposited",            // event TYPE — adapter discriminates on this
    "payloadType": "MoneyDeposited",     // usually equals name
    "index": 42,                          // global store index (stream cursor / replay position)
    "aggregateId": "wallet-001",
    "aggregateType": "Wallet",
    "sequenceNumber": 3,
    "dateTime": "2026-08-01T12:00:00.000Z",
    "metaData": { }                       // optional
  }
  // ... more events in the batch (up to the handler's configured batch size)
]
```

Notes for the 8.2 adapter:

- **Event type** is carried in the message-level `name` field (and mirrored by `payloadType`).
  The adapter switches on this to select the projection handler. The Write Side must publish events
  under stable, past-tense type names (`WalletCreated`, `MoneyDeposited`, `MoneyWithdrawn`,
  `MoneyTransferred`) matching the JSON contracts — **the `name` value is the contract, not the Java
  class name.**
- **Our JSON payload is nested** under `payload` (embedded as JSON, not a string, because the
  endpoint content-type is `application/json`). The adapter reads `payload` and validates it
  against the versioned schema in `contracts/events/`.
- **`eventId`** for read-side idempotency: prefer the payload's own `eventId` (guaranteed present
  for `MoneyDeposited`/`MoneyWithdrawn`/`MoneyTransferred` by contract). `WalletCreated` has no
  `eventId` in the contract, so for it derive a stable id from `aggregateId + sequenceNumber` (or
  the message id / global index) for the `wallet_transaction`/dedup path.
- The adapter MUST handle a **batch (array)**: iterate elements in order and apply each within its
  own transaction (or a single transaction per request), preserving order.

> ⚠️ **To validate at runtime (exact JSON casing).** The docs describe the *fields* authoritatively
> but say "refer to the API documentation" for the exact serialized structure of the inbound Wrapped
> event body. The precise key spellings (e.g. `metaData` vs `metadata`, presence of `index`/`messageId`
> at the top level of each element, and whether the top level is a bare array vs an object wrapping an
> array) must be confirmed against the **running** Axon Server's own API console (Swagger/OpenAPI at the
> Axon Server dashboard on host port `8024`) or by logging the first real delivery. Build the 8.2
> adapter tolerant to field casing and to an optional wrapper object, and log the raw body on first
> receipt to lock the shape.

---

## 3. Acknowledgement semantics (ack / nack)

- Delivery is **request/reply over HTTP POST**. Axon Server treats a **successful HTTP response**
  (2xx) as acknowledgement and advances the persistent stream's confirmed position.
- On a **non-success response or timeout**, Axon Server retries. Publishing to an event handler is
  retried "as long as the endpoint is healthy and the event handler is registered," using
  `axoniq.axonserver.integration.event-retry-backoff` (initial 10 ms, exponential up to 30 s). This
  means **the stream will not advance past an event our handler fails to accept** — at-least-once
  delivery with server-tracked progress.
- Therefore the Read Side contract (Requirement 10.5 / 16.3) is: **only return 2xx when the
  projection was durably committed.** On a PostgreSQL failure, respond non-2xx (e.g. 500) so Axon
  Server redelivers; never a silent 200 that would lose the event.
- Because delivery is at-least-once (and unclean shutdowns / `buffer-progress` can resend the last
  events), **idempotency is required** — satisfied by the `wallet_transaction.event_id` UNIQUE
  constraint: a duplicate `eventId` is treated as already processed and acknowledged 2xx without
  re-applying balances.
- **Health gating:** Axon Server periodically calls the endpoint's **health URL (GET)**; when it
  fails, Axon Server stops pushing events and resumes when health recovers. The Read Service already
  exposes `/health`, which can serve as this health URL.

---

## 4. Registering the event handler / persistent stream

Registration is documented as being done **either through the Axon Server Web UI (dashboard, host
port `8024`) or through the Axon Server HTTP API**. Both need the same information. An event-handler
registration requires:

| Field | Value for our POC |
| --- | --- |
| `name` | `wallet-read-projection` (also becomes the persistent stream name) |
| `filter` | restrict to our events, e.g. `payloadType = "WalletCreated" or payloadType = "MoneyDeposited" or payloadType = "MoneyWithdrawn" or payloadType = "MoneyTransferred"` (documented filter form is `payloadType = "..."`) |
| `sequencing policy` | sequential per wallet (sequence by `aggregateId`) so per-wallet events stay ordered |
| `segments` | `1` (single segment — simplest, strict global-ish ordering for the POC) |
| `start position` | **`TAIL`** (oldest event) so the Read Model builds from full history on first run — matches Requirement 8.5 |
| `event URL` | `/internal/events/axon` (relative to the endpoint base URL) |
| `batch size` | small (e.g. `1`–`10`) for the POC; the max events per Wrapped request |

The **endpoint** it attaches to needs:

| Field | Value |
| --- | --- |
| `name` | `wallet-read-service` |
| `context` | `wallet` |
| `type` | HTTP(s) |
| `wrapping type` | **`Wrapped`** |
| `content type` | `application/json` |
| `base URL` | `http://read-service:3000` (compose service name/port) |
| `health URL` | `/health` (GET) |
| `event URL` | `/internal/events/axon` (POST) |

### Automation vs UI

- The docs explicitly support **both** UI and HTTP API registration, so **automation via a script is
  supported**. Task 8.3 should ship a reproducible registration script (e.g. `curl` against the Axon
  Server admin API on `:8024`, run once after the stack is healthy).
- **The exact admin REST paths and request-body JSON for creating an endpoint and an event handler
  are exposed by the running Axon Server's own API console** (Swagger/OpenAPI reachable from the
  dashboard on `:8024`) rather than spelled out verbatim in the prose reference. Confirm the concrete
  paths/bodies there when writing the 8.3 script, then pin them in the script. Provide the UI steps as
  a documented fallback in the README.

Sketch of the automated registration (paths to be confirmed against the instance's API console):

```bash
# 1) Create the endpoint (Wrapped, JSON, HTTP) — path/body confirmed via Axon Server API console on :8024
curl -sS -X POST "http://localhost:8024/<endpoints-path>?context=wallet" \
  -H "Content-Type: application/json" \
  -d '{
        "name": "wallet-read-service",
        "context": "wallet",
        "type": "HTTP",
        "wrappingType": "Wrapped",
        "contentType": "application/json",
        "baseUrl": "http://read-service:3000",
        "healthUrl": "/health",
        "eventUrl": "/internal/events/axon"
      }'

# 2) Register the event handler -> creates the persistent stream from TAIL, single segment
curl -sS -X POST "http://localhost:8024/<event-handlers-path>?context=wallet" \
  -H "Content-Type: application/json" \
  -d '{
        "name": "wallet-read-projection",
        "filter": "payloadType = \"WalletCreated\" or payloadType = \"MoneyDeposited\" or payloadType = \"MoneyWithdrawn\" or payloadType = \"MoneyTransferred\"",
        "sequencingPolicy": "SequentialPerAggregate",
        "segments": 1,
        "startPosition": "TAIL",
        "eventUrl": "/internal/events/axon",
        "batchSize": 1
      }'
```

### Rebuild / replay (relevant to task 11.1)

Persistent streams support **resetting the position to an earlier point**; when reset, older events
are re-sent **with a replay indicator**. This is the documented, single-event-log mechanism for
`POST /admin/projection/rebuild`: truncate the PostgreSQL projection tables and reset the
`wallet-read-projection` stream to `TAIL` — no second event log, no rebuild from PostgreSQL.

---

## 5. Summary for tasks 8.2 / 8.3

- **Mechanism (confirmed):** Axon Server **Integration** event handler over **HTTP POST**, backed by
  a **persistent stream**, using **`Wrapped`** wrapping + `application/json` content type. `Wrapped`
  is the current, correct term for 2026.0.
- **Payload (confirmed fields; exact casing to lock at runtime):** body is a **JSON array of event
  messages**; each has `payload` (our nested JSON contract), `name`/`payloadType` (the **event type**
  discriminator), `index` (global cursor), plus optional `aggregateId`/`aggregateType`/
  `sequenceNumber`/`dateTime`/`metaData`.
- **Ack:** HTTP **2xx = acknowledged** and stream advances; non-2xx/timeout ⇒ retry with backoff
  (at-least-once). Only 2xx after a durable PostgreSQL commit; rely on `event_id` UNIQUE for
  idempotency.
- **Registration:** define an **endpoint** (`Wrapped`, JSON, base URL `http://read-service:3000`,
  health `/health`, event `/internal/events/axon`) and an **event handler** `wallet-read-projection`
  filtered to the four wallet events, `startPosition = TAIL`, `segments = 1`. Automatable via the Axon
  Server HTTP admin API (script) with the UI as fallback; **confirm the exact admin REST paths/bodies
  from the running server's API console on port 8024** before finalizing the 8.3 script.

### What remains to validate at runtime
1. Exact JSON key casing and top-level shape (bare array vs object-wrapping-array) of the inbound
   Wrapped event body — log the first real delivery.
2. Whether `WalletCreated` needs a synthesized `eventId` (contract has none) for the dedup path.
3. The concrete admin REST paths and request bodies for endpoint + event-handler creation, from the
   Axon Server API console (`:8024`).
