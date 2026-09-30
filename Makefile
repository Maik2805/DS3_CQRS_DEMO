# ============================================================================
# Wallet CQRS + Event Sourcing POC — convenience Makefile (task 15.3)
# ============================================================================
#
# This Makefile is a CONVENIENCE wrapper around the exact commands documented in
# README.md. It is NOT required to run the POC.
#
# Windows note:
#   `make` is not installed by default on Windows. To use this file, run it from
#   one of:
#     - WSL (Windows Subsystem for Linux)
#     - Git Bash (ships with Git for Windows)
#     - `choco install make` (Chocolatey), then run from PowerShell/cmd
#   The `register` and demo curl targets also need `curl` and `bash` on PATH
#   (all present in WSL / Git Bash).
#
#   The PRIMARY, always-works path on Windows is the README's PowerShell
#   (Invoke-RestMethod) and curl commands. This Makefile just saves typing.
#
# Requires on the host: make + docker (with the `docker compose` v2 subcommand)
# + curl. Demo/register targets additionally use bash.
# ============================================================================

# --- Overridable variables (override on the CLI, e.g. `make rebuild ADMIN_TOKEN=secret`)
COMPOSE      ?= docker compose
CURL         ?= curl
WRITE_URL    ?= http://localhost:8081
READ_URL     ?= http://localhost:8082
AXON_UI_URL  ?= http://localhost:8024
# Must match ADMIN_TOKEN in .env (default in .env.example is `change-me`).
ADMIN_TOKEN  ?= change-me
# Seconds to pause between demo commands so the async projection can catch up.
SLEEP        ?= 2

# Demo identifiers / amounts — kept in lockstep with README.md.
# Expected end state: wallet-001 = 25000, wallet-002 = 70000.
W1           ?= wallet-001
W2           ?= wallet-002
OWNER1       ?= owner-001
OWNER2       ?= owner-002
CCY          ?= COP
TRANSFER_ID  ?= transfer-001

.DEFAULT_GOAL := help

.PHONY: help env up down down-volumes reset logs ps register health \
        create-wallets deposit withdraw transfer demo \
        balances transactions rebuild psql

## help: List available targets with a short description (default target).
help:
	@echo "Wallet CQRS POC — make targets:"
	@echo ""
	@echo "  Stack lifecycle:"
	@echo "    env            Copy .env.example to .env (only if .env is missing)"
	@echo "    up             Build + start all services in the background (-d)"
	@echo "    down           Stop and remove containers (keeps volumes/data)"
	@echo "    down-volumes   Stop and remove containers AND volumes (WIPES data)"
	@echo "    reset          Alias for down-volumes (full clean reset)"
	@echo "    logs           Follow logs from all services"
	@echo "    ps             Show container status"
	@echo "    register       Register the wallet-read-projection persistent stream (one-time)"
	@echo "    health         Curl write + read health endpoints"
	@echo ""
	@echo "  Demo (matches README; ends at wallet-001=25000, wallet-002=70000):"
	@echo "    create-wallets Create $(W1) and $(W2) ($(CCY))"
	@echo "    deposit        Deposit 100000 -> $(W1), 20000 -> $(W2)"
	@echo "    withdraw       Withdraw 25000 from $(W1)"
	@echo "    transfer       Transfer 50000 $(W1) -> $(W2) (Idempotency-Key: $(TRANSFER_ID))"
	@echo "    demo           Run create-wallets + deposit + withdraw + transfer in order"
	@echo ""
	@echo "  Queries / ops:"
	@echo "    balances       Get both wallet balances"
	@echo "    transactions   Get $(W1) transaction history"
	@echo "    rebuild        POST /admin/projection/rebuild (uses ADMIN_TOKEN=$(ADMIN_TOKEN))"
	@echo "    psql           Open a psql shell against the read model"
	@echo ""
	@echo "  Override variables, e.g.:  make rebuild ADMIN_TOKEN=your-token"

## env: Copy .env.example to .env if .env does not already exist.
env:
	@if [ -f .env ]; then \
		echo ".env already exists — leaving it untouched."; \
	else \
		cp .env.example .env && echo "Created .env from .env.example."; \
	fi

## up: Build and start the full stack in the background.
up:
	$(COMPOSE) up --build -d
	@echo "Stack starting in the background. Follow logs with:  make logs"
	@echo "Write API: $(WRITE_URL)   Read API: $(READ_URL)   Axon UI: $(AXON_UI_URL)"
	@echo "Next: once healthy, run 'make register' once to create the persistent stream."

## down: Stop and remove containers (data volumes are preserved).
down:
	$(COMPOSE) down

## down-volumes: Stop and remove containers AND volumes — WIPES the event store + read model.
down-volumes:
	@echo "WARNING: this removes all volumes — the Axon event store AND the PostgreSQL read model."
	$(COMPOSE) down -v

## reset: Full reset of the demo (alias for down-volumes).
reset: down-volumes

## logs: Follow logs from all services.
logs:
	$(COMPOSE) logs -f

## ps: Show the status of all services.
ps:
	$(COMPOSE) ps

## register: One-time persistent-stream registration (Linux/macOS/WSL/Git Bash).
register:
	@echo "Registering the wallet-read-projection persistent stream ..."
	@echo "(Windows users can instead run: infrastructure/axon/register-projection.ps1)"
	bash infrastructure/axon/register-projection.sh

## health: Check write and read service health endpoints.
health:
	@echo "Write service health ($(WRITE_URL)/actuator/health):"
	$(CURL) -s $(WRITE_URL)/actuator/health || true
	@echo ""
	@echo "Read service health ($(READ_URL)/health):"
	$(CURL) -s $(READ_URL)/health || true
	@echo ""

## create-wallets: Create wallet-001 (owner-001) and wallet-002 (owner-002).
create-wallets:
	$(CURL) -sS -X POST $(WRITE_URL)/api/wallets \
		-H "Content-Type: application/json" \
		-d '{"walletId":"$(W1)","ownerId":"$(OWNER1)","currency":"$(CCY)"}'
	@echo ""
	$(CURL) -sS -X POST $(WRITE_URL)/api/wallets \
		-H "Content-Type: application/json" \
		-d '{"walletId":"$(W2)","ownerId":"$(OWNER2)","currency":"$(CCY)"}'
	@echo ""

## deposit: Deposit 100000 to wallet-001 and 20000 to wallet-002.
deposit:
	$(CURL) -sS -X POST $(WRITE_URL)/api/wallets/$(W1)/deposits \
		-H "Content-Type: application/json" \
		-d '{"amount":100000,"currency":"$(CCY)"}'
	@echo ""
	$(CURL) -sS -X POST $(WRITE_URL)/api/wallets/$(W2)/deposits \
		-H "Content-Type: application/json" \
		-d '{"amount":20000,"currency":"$(CCY)"}'
	@echo ""

## withdraw: Withdraw 25000 from wallet-001.
withdraw:
	$(CURL) -sS -X POST $(WRITE_URL)/api/wallets/$(W1)/withdrawals \
		-H "Content-Type: application/json" \
		-d '{"amount":25000,"currency":"$(CCY)"}'
	@echo ""

## transfer: Transfer 50000 wallet-001 -> wallet-002 (Idempotency-Key: transfer-001).
transfer:
	$(CURL) -sS -X POST $(WRITE_URL)/api/transfers \
		-H "Content-Type: application/json" \
		-H "Idempotency-Key: $(TRANSFER_ID)" \
		-d '{"transferId":"$(TRANSFER_ID)","sourceWalletId":"$(W1)","targetWalletId":"$(W2)","amount":50000,"currency":"$(CCY)"}'
	@echo ""

## demo: Run the full happy path (create -> deposit -> withdraw -> transfer).
demo: create-wallets
	@sleep $(SLEEP)
	@$(MAKE) --no-print-directory deposit
	@sleep $(SLEEP)
	@$(MAKE) --no-print-directory withdraw
	@sleep $(SLEEP)
	@$(MAKE) --no-print-directory transfer
	@echo ""
	@echo "Demo commands sent. After the projection catches up, expect:"
	@echo "  $(W1) = 25000, $(W2) = 70000   (check with: make balances)"

## balances: Get balances for both wallets from the read model.
balances:
	@echo "$(W1) balance:"
	$(CURL) -sS $(READ_URL)/api/wallets/$(W1)/balance || true
	@echo ""
	@echo "$(W2) balance:"
	$(CURL) -sS $(READ_URL)/api/wallets/$(W2)/balance || true
	@echo ""

## transactions: Get wallet-001 transaction history (newest first).
transactions:
	$(CURL) -sS "$(READ_URL)/api/wallets/$(W1)/transactions?limit=10&offset=0" || true
	@echo ""

## rebuild: Delete + replay the read model from the event log (X-Admin-Token).
rebuild:
	@echo "Rebuilding the read model from the event store (no new commands) ..."
	$(CURL) -sS -X POST $(READ_URL)/admin/projection/rebuild \
		-H "X-Admin-Token: $(ADMIN_TOKEN)" || true
	@echo ""
	@echo "After it catches up, 'make balances' should still show 25000 / 70000."

## psql: Open a psql shell against the PostgreSQL read model.
psql:
	$(COMPOSE) exec postgres-read psql -U wallet_read -d wallet_read
