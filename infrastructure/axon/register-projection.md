# Registering the `wallet-read-projection` persistent stream

This document describes how to register the Axon Server **Integration endpoint** and
**event handler** (persistent stream, starting from `TAIL`) that feed wallet domain events
to the NestJS Read Service at `POST /internal/events/axon`.

It is the human/UI fallback for the automated scripts in this folder:

- `register-projection.sh` &mdash; bash (Linux/macOS/Git Bash/WSL)
- `register-projection.ps1` &mdash; PowerShell (Windows; parses on Windows PowerShell 5.1 and PowerShell 7)

Background and the verified delivery mechanism (why `Wrapped` + HTTP + persistent streams)
are documented in [`integration-notes.md`](./integration-notes.md) (spec task 8.1).

> Pinned Axon Server: `docker.axoniq.io/axoniq/axonserver:2026.0.6`. Context: `wallet`.
> Requirement: **8.5** &mdash; the Read Model projection consumes events from `TAIL` (full history)
> on first run.

---

## Prerequisites

1. The stack is up (`docker compose up`) and Axon Server is healthy.
2. The Axon Server dashboard is reachable on host port **8024**: <http://localhost:8024>.
3. The Read Service is reachable **from inside the compose network** at
   `http://read-service:3000` and exposes `GET /health` and `POST /internal/events/axon`.
   (Axon Server calls the Read Service by its compose service name, not `localhost`.)

---

## Option A &mdash; Automated script (preferred)

Run once after the stack is healthy. The scripts poll Axon Server health, ensure the
`wallet` context, register the endpoint, then register the event handler from `TAIL`.
They are idempotent: re-running tolerates "already exists" responses.

**Windows (PowerShell):**

```powershell
# from repo root
./infrastructure/axon/register-projection.ps1
# or with overrides
./infrastructure/axon/register-projection.ps1 -AxonAdminUrl http://localhost:8024 -Context wallet
```

**Linux / macOS / Git Bash / WSL:**

```bash
# from repo root
bash infrastructure/axon/register-projection.sh
# or with overrides
AXON_ADMIN_URL=http://localhost:8024 AXON_CONTEXT=wallet bash infrastructure/axon/register-projection.sh
```

Configuration (environment variables, both scripts):

| Variable         | Default                       | Meaning                                   |
| ---------------- | ----------------------------- | ----------------------------------------- |
| `AXON_ADMIN_URL` | `http://localhost:8024`       | Axon Server admin/dashboard base URL      |
| `AXON_CONTEXT`   | `wallet`                      | Axon Server context                       |
| `READ_BASE_URL`  | `http://read-service:3000`    | Read Service base URL (compose name)      |
| `WAIT_TIMEOUT`   | `120`                         | Seconds to wait for Axon Server health    |

> **Runtime confirmation.** The exact admin REST paths and JSON bodies for context,
> endpoint, and event-handler creation are exposed by the running Axon Server's own API
> console (Swagger/OpenAPI, reachable from the dashboard on `:8024`). Every such path/body
> in the scripts is marked `# CONFIRM @ :8024`. If a call returns an unexpected status the
> script fails loudly (non-zero exit) and points you at the API console. If the scripts fail
> because your build uses different paths, either update the marked paths or fall back to the
> UI steps below.

---

## Option B &mdash; Axon Server Web UI (manual fallback)

Use this if the scripts cannot reach the admin API or the REST paths differ on your build.
Open the dashboard at <http://localhost:8024> and navigate to the **Integration**
(a.k.a. Integrations / Endpoints) section. Registration is a two-step process: first the
**endpoint**, then the **event handler** attached to it.

### Step 0 &mdash; Ensure the `wallet` context exists

In the dashboard's **Contexts** area, confirm a context named `wallet` exists. If not,
create it (name: `wallet`). The Write Service may also create it at startup; if it is
already present, leave it as is.

### Step 1 &mdash; Create the integration endpoint

Create a new integration endpoint with exactly these values:

| Field           | Value                          |
| --------------- | ------------------------------ |
| Name            | `wallet-read-service`          |
| Context         | `wallet`                       |
| Type / Protocol | `HTTP` (HTTP(s))               |
| Wrapping type   | `Wrapped`                      |
| Content type    | `application/json`             |
| Base URL        | `http://read-service:3000`     |
| Health URL      | `/health` (GET)                |
| Event URL       | `/internal/events/axon` (POST) |

Notes:
- **Wrapped** (not `Raw`) is required so the Read Service receives a self-describing JSON
  array of event messages and can accept batches (see `integration-notes.md` &sect;2).
- The base URL uses the **compose service name** `read-service`, because Axon Server calls
  it from inside the compose network &mdash; not `localhost`.

### Step 2 &mdash; Register the event handler (creates the persistent stream from TAIL)

Create an event handler **against the `wallet-read-service` endpoint** with these values:

| Field                | Value                                                                                                                            |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| Name                 | `wallet-read-projection` (this also becomes the persistent stream name)                                                          |
| Endpoint             | `wallet-read-service`                                                                                                            |
| Context              | `wallet`                                                                                                                         |
| Filter               | `payloadType = "WalletCreated" or payloadType = "MoneyDeposited" or payloadType = "MoneyWithdrawn" or payloadType = "MoneyTransferred"` |
| Sequencing policy    | Sequential per aggregate (sequence by `aggregateId`)                                                                             |
| Segments             | `1`                                                                                                                              |
| Start position       | `TAIL` (oldest event &mdash; build the Read Model from full history)                                                             |
| Event URL            | `/internal/events/axon` (POST)                                                                                                   |
| Batch size           | small, e.g. `1` (max events per Wrapped request)                                                                                 |

Notes:
- **`TAIL`** satisfies Requirement 8.5: on first run the projection consumes the entire
  event history.
- **Segments = 1** keeps a single, simple ordering for the POC.
- **Sequential per aggregate** keeps each wallet's events in order.
- The filter uses the documented `payloadType = "..."` form and is restricted to the four
  wallet event types so unrelated events are never delivered.

### Step 3 &mdash; Verify

- In the dashboard, confirm the `wallet-read-projection` stream exists and is active/healthy.
- Axon Server periodically calls the endpoint's health URL (`GET /health`); if it fails,
  delivery pauses and resumes when health recovers.
- Publish (or replay) a wallet event and confirm the Read Service logs a delivery at
  `POST /internal/events/axon` and the projection updates PostgreSQL.

---

## Rebuild / replay (related, task 11.1)

Persistent streams support resetting the position. To rebuild the Read Model, truncate the
PostgreSQL projection tables and reset the `wallet-read-projection` stream back to `TAIL`;
Axon Server re-sends historical events (with a replay indicator). There is no second event
log &mdash; the Event Store is the single source of truth. See `integration-notes.md` &sect;4.

---

## Where to find the exact admin REST API

The concrete REST paths and request bodies for context/endpoint/event-handler creation are
published by the **running** Axon Server instance, not hard-coded in the prose reference:

- Open the dashboard: <http://localhost:8024>
- Find the API console (Swagger/OpenAPI) link from the dashboard.
- Confirm the paths/bodies there, then, if needed, update the `# CONFIRM @ :8024`
  lines in `register-projection.sh` / `register-projection.ps1`.
