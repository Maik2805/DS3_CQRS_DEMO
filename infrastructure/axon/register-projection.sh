#!/usr/bin/env bash
#
# register-projection.sh — Register the wallet-read-projection persistent stream
# with Axon Server for the wallet CQRS POC.
#
# WHAT THIS DOES (verified against Axon Server 2026.0.6 admin REST API v2)
#   1. Waits for Axon Server to be healthy (polls the dashboard port 8024).
#   2. Registers the integration ENDPOINT `wallet-read-service`
#      (HTTP(S), Wrapped, application/json, baseUrl http://read-service:3000,
#       healthUrl /health, eventUrl /internal/events/axon) and captures its UUID.
#   3. Registers the EVENT HANDLER `wallet-read-projection` on that endpoint UUID
#      — this creates the persistent stream — sequenced per aggregate, segments=1,
#      startPosition=TAIL (full replay), small batchSize.
#
#   NOTE ON FILTERING: the read-service context is a DCB context (STANDALONE_DCB).
#   A classic `payloadType = "..."` filter is REJECTED by a DCB stream
#   ("Invalid identifier: payloadType") and silently closes the connection, so NO
#   server-side filter is set. The NestJS projection already ignores any event
#   type it does not recognize, so delivering all events is correct and safe.
#
#   This is registration TOOLING; it does NOT run inside the read-service app.
#   Invoke it once after `docker compose up` when the stack is healthy.
#
# IDEMPOTENCY
#   Re-runnable: an existing endpoint/handler (409 or "exists") is tolerated.
#
# CONFIGURATION (environment variables)
#   AXON_ADMIN_URL   Axon Server admin/dashboard base URL   (default: http://localhost:8024)
#   AXON_CONTEXT     Axon Server DCB context                (default: default)
#   READ_BASE_URL    Read Service base URL (compose name)   (default: http://read-service:3000)
#   WAIT_TIMEOUT     Seconds to wait for Axon health        (default: 120)

set -euo pipefail

AXON_ADMIN_URL="${AXON_ADMIN_URL:-http://localhost:8024}"
AXON_CONTEXT="${AXON_CONTEXT:-default}"
READ_BASE_URL="${READ_BASE_URL:-http://read-service:3000}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-120}"
AXON_ADMIN_URL="${AXON_ADMIN_URL%/}"

ENDPOINT_NAME="wallet-read-service"
HANDLER_NAME="wallet-read-projection"
HEALTH_URL="/health"
EVENT_URL="/internal/events/axon"

log() { printf '[register-projection] %s\n' "$*"; }
err() { printf '[register-projection] ERROR: %s\n' "$*" >&2; }

command -v curl >/dev/null 2>&1 || { err "curl is required but not found on PATH."; exit 1; }

wait_for_axon() {
  log "Waiting up to ${WAIT_TIMEOUT}s for Axon Server at ${AXON_ADMIN_URL} ..."
  local deadline=$(( $(date +%s) + WAIT_TIMEOUT )) code
  while [ "$(date +%s)" -lt "$deadline" ]; do
    code="$(curl -s -o /dev/null -w '%{http_code}' "${AXON_ADMIN_URL}/actuator/health" || echo 000)"
    [ "$code" = "200" ] && { log "Axon Server healthy."; return 0; }
    code="$(curl -s -o /dev/null -w '%{http_code}' "${AXON_ADMIN_URL}/" || echo 000)"
    { [ "$code" -ge 200 ] && [ "$code" -lt 400 ]; } && { log "Axon Server reachable (${code})."; return 0; }
    sleep 3
  done
  err "Timed out waiting for Axon Server at ${AXON_ADMIN_URL}."; exit 1
}

# Look up an existing endpoint UUID by name (empty if none). Uses GET + grep/sed.
lookup_endpoint_id() {
  local list
  list="$(curl -sS "${AXON_ADMIN_URL}/v2/endpoints?context=${AXON_CONTEXT}" 2>/dev/null || echo '')"
  # Isolate the object for our endpoint name, then extract the first "id".
  printf '%s' "$list" \
    | tr '}' '\n' \
    | grep "\"name\"[[:space:]]*:[[:space:]]*\"${ENDPOINT_NAME}\"" \
    | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    | head -n1
}

# True if the endpoint already has an event handler named $HANDLER_NAME.
handler_exists() {
  curl -sS "${AXON_ADMIN_URL}/v2/endpoints?context=${AXON_CONTEXT}" 2>/dev/null \
    | grep -q "\"name\"[[:space:]]*:[[:space:]]*\"${HANDLER_NAME}\""
}

# Ensure the endpoint exists; echo its UUID on stdout. Idempotent.
register_endpoint() {
  local url="${AXON_ADMIN_URL}/v2/endpoints?context=${AXON_CONTEXT}" tmp code body id
  local payload
  payload="$(printf '{"name":"%s","type":"HTTP(S)","baseUrl":"%s","eventUrl":"%s","healthUrl":"%s","wrappingType":"Wrapped","contentType":"application/json"}' \
      "$ENDPOINT_NAME" "$READ_BASE_URL" "$EVENT_URL" "$HEALTH_URL")"
  tmp="$(mktemp)"
  code="$(curl -sS -o "$tmp" -w '%{http_code}' -X POST "$url" -H 'Content-Type: application/json' --data "$payload" || echo 000)"
  body="$(cat "$tmp")"; rm -f "$tmp"
  if [ "$code" -ge 200 ] && [ "$code" -lt 300 ]; then
    log "Endpoint '${ENDPOINT_NAME}' registered." >&2
    printf '%s' "$body" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
    return 0
  fi
  # Non-2xx (e.g. AXONIQ-2311 "context/name must be unique") -> reuse existing.
  log "Endpoint POST returned ${code}; assuming it already exists and looking it up." >&2
  id="$(lookup_endpoint_id)"
  if [ -n "$id" ]; then log "Reusing existing endpoint '${ENDPOINT_NAME}' (${id})." >&2; printf '%s' "$id"; return 0; fi
  err "Could not create or find endpoint '${ENDPOINT_NAME}'. Last body: ${body}"; exit 1
}

# Ensure the event handler exists on the endpoint. Idempotent.
register_event_handler() {
  local endpoint_id="$1"
  if handler_exists; then
    log "Event handler '${HANDLER_NAME}' already registered — OK."
    return 0
  fi
  local url="${AXON_ADMIN_URL}/v2/endpoints/${endpoint_id}/eventHandlers?context=${AXON_CONTEXT}" tmp code body
  # No server-side filter (DCB rejects payloadType); SequentialPerAggregatePolicy; TAIL for full replay.
  local payload
  payload="$(printf '{"name":"%s","sequencingPolicy":"SequentialPerAggregatePolicy","segments":1,"startPosition":"TAIL","batchSize":1,"eventUrl":"%s"}' \
      "$HANDLER_NAME" "$EVENT_URL")"
  tmp="$(mktemp)"
  code="$(curl -sS -o "$tmp" -w '%{http_code}' -X POST "$url" -H 'Content-Type: application/json' --data "$payload" || echo 000)"
  body="$(cat "$tmp")"; rm -f "$tmp"
  if [ "$code" -ge 200 ] && [ "$code" -lt 300 ]; then
    log "Event handler '${HANDLER_NAME}' registered — persistent stream created from TAIL."
  elif handler_exists; then
    log "Event handler '${HANDLER_NAME}' already registered — OK."
  else
    err "Unexpected status ${code} registering event handler. Body: ${body}"; exit 1
  fi
}
main() {
  log "Axon admin URL : ${AXON_ADMIN_URL}"
  log "Context        : ${AXON_CONTEXT} (DCB)"
  log "Read base URL  : ${READ_BASE_URL}"
  wait_for_axon
  local endpoint_id
  endpoint_id="$(register_endpoint)"
  [ -n "$endpoint_id" ] || { err "Could not obtain endpoint UUID."; exit 1; }
  log "Endpoint UUID  : ${endpoint_id}"
  register_event_handler "$endpoint_id"
  log "Done. wallet-read-projection persistent stream is registered from TAIL."
  log "Verify in the Axon Server dashboard at ${AXON_ADMIN_URL} (:8024)."
}

main "$@"