# Axon Framework 5 DCB API — Verification Notes (Task 5.1)

Verification/implementation-prep for the `TransferMoney` flow (Requirements 4.7, 13.1, 13.3).
This note confirms the concrete Axon Framework 5.3.2 Dynamic Consistency Boundary (DCB) API so
that task 5.2 implements the two-wallet transfer against **verified** APIs rather than guessing.

- **Pinned versions** (from `versions.md`): Axon Framework `5.3.2`, Axon Server `2026.0.6`,
  Java 21, Spring Boot 4.1.1.
- **Verification date:** 2026-08.

## TL;DR — Recommendation

**Use native Axon Framework 5 DCB** for the transfer. It is stable and available in the pinned
versions, and it is the exact scenario the official Axon 5 sample demonstrates (a single command
that reads/validates **two** entities and appends **one** event spanning both). Axon Server
`2026.0.6` is well past the `2025.2+` line that introduced DCB support.

For the transfer command handler, adopt the **multi-entity** shape from the AxonIQ sample:
inject both wallets with two `@InjectEntity` parameters into one `@CommandHandler`, validate, and
append a single `MoneyTransferred` event that is **tagged with both wallet ids** via `@EventTag`.
Axon derives the consistency boundary from the tagged event streams that were read, so a concurrent
withdraw/transfer on the same source cannot commit against a stale balance — the losing command
fails (maps to HTTP 409).

The documented sequenced-debit fallback is **not needed** and is recorded at the end only as a
contingency.

## Sources (all verified on the date above)

- AxonIQ/university-demo — the official Axon Framework 5 + DCB sample.
  <https://github.com/AxonIQ/university-demo>
  Files this note is based on (canonical multi-entity command spanning two entities in one
  consistency boundary — the direct analogue of source+target wallets):
  - `faculty/write/subscribestudentmulti/SubscribeStudentToCourseCommandHandler.java` — one
    `@CommandHandler` with **two** `@InjectEntity` params (`Course`, `Student`) + `EventAppender`.
  - `faculty/write/subscribestudentmulti/Course.java`, `.../Student.java` — two
    `@EventSourcedEntity(tagKey = ...)` models, each folded by `@EventSourcingHandler`.
  - `faculty/write/subscribestudentmulti/SubscribeStudentMultiEntityConfiguration.java` — registers
    both entities (`EventSourcedEntityModule.autodetected(...)`) and the command-handling module.
  - `faculty/events/StudentSubscribedToCourse.java` — a single event whose record fields carry
    **multiple** `@EventTag(key = ...)` annotations (facultyId, studentId, courseId).
  - `faculty/events/CourseCreated.java` — confirms `@EventTag` on a normal single-entity event.
  - `faculty/write/subscribestudent/SubscribeStudentToCourseCommandHandler.java` — the
    **alternative** single-composite-state shape using `@EventCriteriaBuilder` returning
    `EventCriteria.either(...)` with `Tag.of(...)` + `.andBeingOneOfTypes(...)`.
  - `faculty/FacultyTags.java` — tag-key string constants.
- AxonIQ "Dynamic Consistency Boundaries" agent-skill (concept vocabulary confirmation:
  `@EventTag`, `@EventSourcedEntity`, `Tag`, `EventCriteria`, `@EventCriteriaBuilder`,
  `AppendCondition`, `SourcingCondition`, `ConsistencyMarker`, optimistic concurrency in the event
  store). <https://skillsmp.com/creators/axoniq/axonframework/claude-skills-dynamic-consistency-boundaries>
- Axon Framework reference / API docs entry points:
  <https://docs.axoniq.io/axon-framework-reference/> , <https://apidocs.axoniq.io/>
- DCB concept: <https://dcb.events/>

> Content was rephrased/summarized for compliance with licensing restrictions; code sketches below
> are original, adapted to the wallet domain, and grounded in the cited sample files.

---

## 1. Does 5.3.2 support DCB for a two-wallet transfer in one consistency boundary?

**Yes.** This is precisely the scenario the official Axon Framework 5 sample demonstrates:
`SubscribeStudentToCourse` is a single command that must read and validate **two distinct
entities** (a `Course` and a `Student`, each event-sourced under its own tag key) and then append
**one** event (`StudentSubscribedToCourse`) that belongs to both entities' streams. Our transfer is
the same shape: read/validate a **source** wallet and a **target** wallet, then append one
`MoneyTransferred` fact spanning both.

How the consistency boundary is enforced (DCB model):

- Each entity is event-sourced by loading the events that match its **tag** (e.g.
  `walletId = <source>`). Loading records the read position / consistency marker for those
  tag-filtered streams.
- On append, Axon Server uses an **append condition** derived from what was read: the append
  succeeds only if **no new event matching those same tag filters has been written since** the read
  position. If a concurrent command already appended to the source wallet's tag stream (e.g. another
  withdrawal or transfer), this transfer's append is rejected and the command fails — it can never
  commit against a stale balance. This is the DCB analogue of aggregate optimistic concurrency, but
  scoped to the dynamic set of tags the command actually touched rather than to one fixed aggregate.

Axon Server `2026.0.6` supports the DCB event store (DCB shipped on the `2025.2+` line), so the
pinned stack fully supports this. No fallback is required.

---

## 2. Exact API + code sketch for the `TransferMoney` handler

### API surface (verified against the sample; exact packages)

- `org.axonframework.eventsourcing.annotation.EventSourcedEntity` — declares an event-sourced
  entity with a `tagKey`. (The sample uses this on `Course`/`Student`.)
  - **Note on our existing code:** `Wallet.java` currently uses the Spring stereotype
    `org.axonframework.extension.spring.stereotype.EventSourced(tagKey = "walletId", idType = String.class)`,
    which is the Spring-autodetected equivalent that registers the same kind of event-sourced entity.
    Either annotation works; the DCB behavior comes from the **tag key**, not from which stereotype
    declares the entity. Task 5.2 can keep `Wallet` as the single `@EventSourced` entity keyed by
    `walletId` and inject it **twice** (once per role) — see below.
- `org.axonframework.eventsourcing.annotation.EventSourcingHandler` — folds each event into entity
  state (already used by `Wallet.evolve(...)`).
- `org.axonframework.eventsourcing.annotation.reflection.EntityCreator` — no-arg creator so a
  not-yet-created wallet is injectable as a non-null instance with `exists() == false` (already used
  by `Wallet`).
- `org.axonframework.modelling.annotation.InjectEntity` — injects a loaded (event-sourced) entity
  into a command-handler parameter. `idProperty` names the **command** property carrying that
  entity's id. Multiple `@InjectEntity` params in one handler = multiple entities in one consistency
  boundary.
- `org.axonframework.messaging.commandhandling.annotation.CommandHandler` — the handler method.
- `org.axonframework.messaging.eventhandling.gateway.EventAppender` — `append(event)` publishes the
  resulting event(s) into the unit of work; Axon tags each event using the `@EventTag`s declared on
  the event record.
- `org.axonframework.eventsourcing.annotation.EventTag` — placed on **event record fields** to tag
  the event with `key = <tagKey>` and the field value. **This is how one event is tagged with two
  wallet ids.**
- (Alternative, single-composite-state route) `org.axonframework.eventsourcing.annotation.EventCriteriaBuilder`,
  `org.axonframework.messaging.eventstreaming.EventCriteria`,
  `org.axonframework.messaging.eventstreaming.Tag` — for building an explicit
  `EventCriteria.either(havingTags(Tag.of(...)).andBeingOneOfTypes(...), ...)` over both tags.

### Routing note (important for task 5.2)

`TransferMoney` currently has `@TargetEntityId` on `transferId`. For the **multi-entity** handler,
the two wallet entities are located by `@InjectEntity(idProperty = "sourceWalletId")` and
`@InjectEntity(idProperty = "targetWalletId")` — those `idProperty` names must match accessor names
on the command (`sourceWalletId()`, `targetWalletId()`), which already exist. Keep `transferId`
available for idempotency (section 4). Whether `@TargetEntityId` stays on `transferId` or is removed
is a task-5.2 wiring detail; the entity loading is driven by the `@InjectEntity` `idProperty`
values, not by `@TargetEntityId`.

### Code sketch — multi-entity transfer handler (RECOMMENDED)

Grounded in `subscribestudentmulti/SubscribeStudentToCourseCommandHandler.java`. `Wallet` is reused
as the injected entity for both roles (it is `@EventSourced(tagKey = "walletId")`).

```java
// application/TransferCommandHandler.java  (task 5.2)
@Component
public class TransferCommandHandler {

    private static final String COP = "COP";

    @CommandHandler
    public void handle(
            TransferMoney command,
            @InjectEntity(idProperty = "sourceWalletId") Wallet source,
            @InjectEntity(idProperty = "targetWalletId") Wallet target,
            EventAppender eventAppender) {
        eventAppender.append(decide(command, source, target));
    }

    private MoneyTransferred decide(TransferMoney c, Wallet source, Wallet target) {
        requireNonBlank(c.transferId(), "transferId");            // Req 5.1
        requireNonBlank(c.sourceWalletId(), "sourceWalletId");
        requireNonBlank(c.targetWalletId(), "targetWalletId");
        if (c.sourceWalletId().equals(c.targetWalletId())) {       // Req 4.2 -> 409
            throw new SelfTransferException(c.sourceWalletId());
        }
        requirePositiveAmount(c.amount());                         // Req 4.3 -> 400
        if (!source.exists()) throw new WalletNotFoundException(c.sourceWalletId()); // Req 4.4 -> 404
        if (!target.exists()) throw new WalletNotFoundException(c.targetWalletId()); // Req 4.4 -> 404
        // currency check across both wallets: Req 4.6 -> 422
        if (!source.currency().equals(c.currency()) || !target.currency().equals(c.currency())) {
            throw new CurrencyMismatchException(/* ... */);
        }
        if (source.balance().compareTo(c.amount()) < 0) {          // Req 4.5 -> 409
            throw new InsufficientFundsException(c.sourceWalletId(), source.balance(), c.amount());
        }
        // Optional: duplicate-transferId check within the boundary (see section 4).
        return MoneyTransferred.of(
                UUID.randomUUID().toString(), c.transferId(),
                c.sourceWalletId(), c.targetWalletId(), c.amount(), c.currency(), Instant.now());
    }
}
```

Because `MoneyTransferred` is tagged with **both** wallet ids (section 3), reading `source` and
`target` records both tag streams into the consistency boundary; on append Axon Server rejects the
write if either the source or target `walletId` stream advanced since it was read. That is the DCB
guarantee for Req 13.1 / 13.3 — no stale-balance validation under concurrency.

### How the consistency boundary is declared

- **Multi-entity route (recommended):** the boundary is **implicit** — it is the union of the tag
  streams read via the two `@InjectEntity` params. No `@EventCriteriaBuilder` needed; Axon derives
  the append condition from the entities loaded plus the `@EventTag`s on the appended event.
- **Single-composite-state route (alternative):** declare it **explicitly** with an
  `@EventCriteriaBuilder` on a composite `State` (as in `subscribestudent/...CommandHandler.java`):

```java
@EventCriteriaBuilder
private static EventCriteria resolveCriteria(TransferId id) { // some id carrying both wallet ids
    return EventCriteria.either(
        EventCriteria.havingTags(Tag.of("walletId", id.sourceWalletId()))
            .andBeingOneOfTypes(WalletCreated.class.getName(), MoneyDeposited.class.getName(),
                                MoneyWithdrawn.class.getName(), MoneyTransferred.class.getName()),
        EventCriteria.havingTags(Tag.of("walletId", id.targetWalletId()))
            .andBeingOneOfTypes(WalletCreated.class.getName(), MoneyDeposited.class.getName(),
                                MoneyWithdrawn.class.getName(), MoneyTransferred.class.getName())
    );
}
```

Prefer the multi-entity route: it reuses the existing `Wallet` entity unchanged and needs no new
composite state class.

### Registration wiring

The existing Write Side is Spring-autodetected (`Wallet` via `@EventSourced`, handlers via
`@Component`), so a new `@Component TransferCommandHandler` is auto-registered — no manual
`EventSourcedEntityModule`/`CommandHandlingModule` needed. (The sample's
`SubscribeStudentMultiEntityConfiguration` shows the equivalent **programmatic** registration for a
non-Spring app; it confirms both entities + the handler module are registered together, which the
Spring starter does automatically here.)

---

## 3. Folding `MoneyTransferred` into each wallet (informs Wallet.evolve, tasks 5.2/5.3)

**Tagging one event with two `walletId` tags is the correct DCB modeling** and is exactly what the
sample does (`StudentSubscribedToCourse` carries `@EventTag(studentId)` **and**
`@EventTag(courseId)`). Add two `@EventTag(key = "walletId")` annotations to `MoneyTransferred` — one
on `sourceWalletId`, one on `targetWalletId`:

```java
public record MoneyTransferred(
        String eventType, int eventVersion, String eventId, String transferId,
        @EventTag(key = Wallet.TAG_KEY) String sourceWalletId,   // tag: walletId = source
        @EventTag(key = Wallet.TAG_KEY) String targetWalletId,   // tag: walletId = target
        BigDecimal amount, String currency, Instant occurredAt) { ... }
```

With both fields tagged `walletId`, the single event is delivered into **both** wallet streams. Each
side then folds **its own delta** in `Wallet.evolve(MoneyTransferred)` by comparing the event's ids
to its own `walletId` (mirrors how the sample's `State.evolve(StudentSubscribedToCourse)` checks
`equals(courseId)` / `equals(studentId)` to decide which counter to move):

```java
@EventSourcingHandler
public void evolve(MoneyTransferred event) {
    if (this.walletId != null && this.walletId.equals(event.sourceWalletId())) {
        this.balance = normalize(this.balance.subtract(event.amount())); // source -= amount
    }
    if (this.walletId != null && this.walletId.equals(event.targetWalletId())) {
        this.balance = normalize(this.balance.add(event.amount()));      // target += amount
    }
}
```

This replaces the current deferred no-op in `Wallet.evolve(MoneyTransferred)` and satisfies
Property 3 (fund conservation) per wallet. It also keeps the single-entity `Wallet` model — no
separate source/target entity types are needed.

> Read Side note: the projection (task 9.2) already folds source `-=` / target `+=` in one Postgres
> transaction, independent of these tags. The `@EventTag`s affect only Write Side event-store
> delivery/consistency, not the JSON contract shape, so `money-transferred.v1.schema.json` is
> unchanged.

---

## 4. `transferId` idempotency within the DCB model (informs task 5.3)

Detect a duplicate `transferId` **inside the consistency boundary**, using the event store — never a
Postgres lookup (Req 13.2). Recommended approach, consistent with Req 4.8 / 5.1–5.4:

- Tag `MoneyTransferred` **additionally** with the `transferId` so completed transfers are queryable
  by that tag:

  ```java
  @EventTag(key = "transferId") String transferId,
  ```

- In the transfer handler, source the "already processed?" state for this `transferId` and reject
  duplicates. Two equivalent ways to make that state available:
  - **Explicit criteria (preferred for idempotency):** add an `@EventCriteriaBuilder` /
    `EventCriteria.havingTags(Tag.of("transferId", id)).andBeingOneOfTypes(MoneyTransferred...)`
    that loads any prior `MoneyTransferred` with the same `transferId` into a small
    `TransferMarker`-style entity/state; if one exists, throw `DuplicateTransferException` → HTTP 409
    and append nothing (Req 4.8, 5.4).
  - Because the marker is also part of the boundary, two concurrent first-time commands with the
    same `transferId` cannot both commit: whichever appends first advances the `transferId` tag
    stream, and the other's append condition fails → 409. This makes idempotency safe under
    concurrency without a database unique constraint.

- The first successful transfer "records the transfer as completed" (Req 5.3) simply by the
  existence of the `transferId`-tagged `MoneyTransferred` event — no separate marker event is
  required, though a dedicated `TransferCompleted` marker is an option if a distinct fact is
  preferred.

Task 5.3 will also map the `Idempotency-Key` HTTP header to `transferId` at the controller (Req 5.2)
and add `POST /api/transfers`.

---

## 5. Fallback (contingency only — NOT recommended for this stack)

If DCB were unavailable/unstable (it is not, for the pinned versions), the design's documented
fallback is a **sequenced-debit coordinator**: an application service/saga that first performs a
guarded debit on the source `Wallet` (using the source's own optimistic concurrency to reject
stale-balance debits with 409), then credits the target, emitting `MoneyTransferred` only after the
debit is confirmed. Sketch:

```java
// Fallback only — do NOT implement unless DCB is proven unavailable.
public void transfer(TransferMoney c) {
    // 1) guarded debit on source (its own aggregate stream enforces no-negative + no stale balance)
    commandGateway.sendAndWait(new DebitForTransfer(c.sourceWalletId(), c.amount(), c.transferId()));
    // 2) credit target once debit is confirmed
    commandGateway.sendAndWait(new CreditForTransfer(c.targetWalletId(), c.amount(), c.transferId()));
    // MoneyTransferred emitted as the single business fact after the debit commits.
}
```

Trade-off: two events / two steps instead of one atomic fact, plus compensation logic if the credit
fails — strictly worse than DCB here. **Invariant preserved either way:** a transfer can never
validate against a stale balance under concurrency, and the source balance can never go negative.

---

## Decision for task 5.2

- **Path:** Native Axon Framework 5 DCB, **multi-entity** handler shape.
- **Key APIs:** `@CommandHandler` + two `@InjectEntity(idProperty = "sourceWalletId" / "targetWalletId") Wallet`
  + `EventAppender`; single `MoneyTransferred` event with two `@EventTag(key = "walletId")` fields
  (source + target) plus an `@EventTag(key = "transferId")` for idempotency; per-side fold in
  `Wallet.evolve(MoneyTransferred)`.
- **Consistency:** implicit boundary = the union of the two `walletId` tag streams read via
  `@InjectEntity`; append condition rejects stale writes (Req 4.7, 13.1, 13.3).
- **Idempotency (task 5.3):** duplicate `transferId` detected within the boundary via the
  `transferId` tag / explicit `EventCriteria`; duplicate → 409, no new event (Req 4.8, 5.x).
- **Fallback:** not needed; recorded above only as a contingency.
