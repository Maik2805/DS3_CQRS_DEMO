-- infrastructure/postgres/init.sql
--
-- Read Model schema for the Wallet CQRS + Event Sourcing POC.
--
-- This script initializes the PostgreSQL Read Model database `wallet_read`. It is
-- mounted into the postgres container at /docker-entrypoint-initdb.d/init.sql and
-- executed automatically on first startup against database `wallet_read`.
--
-- Design decisions (see design.md "Data Models"):
--   * Identifiers are VARCHAR (free-form strings), NOT UUID.
--   * Money uses NUMERIC(19,2), mirroring the BigDecimal scale 2 on the Write Side.
--   * `wallet_transaction.event_id` UNIQUE is the primary idempotency guard: a
--     redelivered event carrying an already-seen event_id is rejected by this
--     constraint, so a projection can treat it as already processed.
--   * This Read Model is independent of any Write Side storage or credentials.
--   * The `wallet_transfer` table (task 15.1) records one row per completed transfer;
--     each MoneyTransferred also produces two derived wallet_transaction movements
--     (TRANSFER_OUT against the source, TRANSFER_IN against the target).
--
-- CREATE TABLE / INDEX IF NOT EXISTS keeps the script safe to re-run.
--
-- SCHEMA CHANGE NOTE (task 15.1): this file runs only once, on an EMPTY postgres
-- data volume (docker-entrypoint-initdb.d). If you are adding the `wallet_transfer`
-- table (and the TRANSFER_OUT/TRANSFER_IN movement types) to an already-initialized
-- volume, either recreate the volume with `docker compose down -v` (destroys the Read
-- Model — it is rebuildable from Axon Server) or apply the `wallet_transfer` CREATE
-- TABLE below manually with psql. After recreating, `POST /admin/projection/rebuild`
-- repopulates the Read Model by replaying events.

-- Current balance per wallet. One row per wallet.
CREATE TABLE IF NOT EXISTS wallet_balance (
    wallet_id   VARCHAR(255)  PRIMARY KEY,
    owner_id    VARCHAR(255)  NOT NULL,
    currency    VARCHAR(3)    NOT NULL,
    balance     NUMERIC(19,2) NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL
);

-- Append-only movement log projected from domain events.
-- transaction_type is one of: DEPOSIT | WITHDRAWAL | TRANSFER_OUT | TRANSFER_IN.
-- event_id is UNIQUE and provides the idempotency guard against event redelivery.
-- A single MoneyTransferred produces TWO rows (TRANSFER_OUT + TRANSFER_IN); each
-- carries a distinct, deterministic derived event_id (`<eventId>:OUT` / `<eventId>:IN`)
-- so both satisfy the UNIQUE constraint and redelivery stays idempotent.
CREATE TABLE IF NOT EXISTS wallet_transaction (
    transaction_id   VARCHAR(255)  PRIMARY KEY,
    wallet_id        VARCHAR(255)  NOT NULL,
    transaction_type VARCHAR(30)   NOT NULL,   -- DEPOSIT | WITHDRAWAL | TRANSFER_OUT | TRANSFER_IN
    amount           NUMERIC(19,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    occurred_at      TIMESTAMPTZ   NOT NULL,
    event_id         VARCHAR(255)  NOT NULL UNIQUE
);

-- Supports the transaction history query: filter by wallet, newest first.
CREATE INDEX IF NOT EXISTS idx_wallet_transaction_wallet_occurred
    ON wallet_transaction (wallet_id, occurred_at DESC);

-- One row per completed transfer (task 15.1). This is a Read Model projection of the
-- MoneyTransferred fact, not a relational replica of the aggregate. VARCHAR ids match
-- the rest of the schema (the original spec used UUID; this project uses VARCHAR
-- everywhere). event_id is the RAW MoneyTransferred eventId and is the idempotency
-- guard for this table (the two derived wallet_transaction rows use suffixed ids).
CREATE TABLE IF NOT EXISTS wallet_transfer (
    transfer_id      VARCHAR(255)  PRIMARY KEY,
    source_wallet_id VARCHAR(255)  NOT NULL,
    target_wallet_id VARCHAR(255)  NOT NULL,
    amount           NUMERIC(19,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    occurred_at      TIMESTAMPTZ   NOT NULL,
    event_id         VARCHAR(255)  NOT NULL UNIQUE
);
