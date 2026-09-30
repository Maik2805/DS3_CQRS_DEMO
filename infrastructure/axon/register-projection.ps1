<#
.SYNOPSIS
  Register the wallet-read-projection persistent stream with Axon Server (wallet CQRS POC).

.DESCRIPTION
  Verified against Axon Server 2026.0.6 admin REST API v2:
    1. Waits for Axon Server to be healthy (dashboard port 8024).
    2. Ensures the integration ENDPOINT "wallet-read-service" (HTTP(S), Wrapped,
       application/json, baseUrl http://read-service:3000, healthUrl /health,
       eventUrl /internal/events/axon) exists, and captures its UUID.
    3. Ensures the EVENT HANDLER "wallet-read-projection" exists on that endpoint
       (creates the persistent stream): SequentialPerAggregatePolicy, segments 1,
       startPosition TAIL (full replay), batchSize 1.

  FILTERING: the context is a DCB context (STANDALONE_DCB). A classic
  `payloadType = "..."` filter is REJECTED by a DCB stream ("Invalid identifier:
  payloadType") and closes the connection, so NO server-side filter is set. The
  NestJS projection already ignores unrecognized event types, so delivering all
  events is correct and safe.

  IDEMPOTENT: re-running is safe. An existing endpoint/handler (AXONIQ-2311
  "context/name must be unique") is detected and reused rather than treated as an error.

  Registration TOOLING — does NOT run inside the read-service. Invoke once after
  `docker compose up` when the stack is healthy.

.PARAMETER AxonAdminUrl   Default http://localhost:8024
.PARAMETER AxonContext    Default "default" (the DCB context)
.PARAMETER ReadBaseUrl    Default http://read-service:3000
.PARAMETER WaitTimeout    Seconds to wait for health (default 120)
#>
param(
  [string]$AxonAdminUrl = $(if ($env:AXON_ADMIN_URL) { $env:AXON_ADMIN_URL } else { "http://localhost:8024" }),
  [string]$AxonContext  = $(if ($env:AXON_CONTEXT)   { $env:AXON_CONTEXT }   else { "default" }),
  [string]$ReadBaseUrl  = $(if ($env:READ_BASE_URL)  { $env:READ_BASE_URL }  else { "http://read-service:3000" }),
  [int]   $WaitTimeout  = $(if ($env:WAIT_TIMEOUT)   { [int]$env:WAIT_TIMEOUT } else { 120 })
)

$ErrorActionPreference = "Stop"
$AxonAdminUrl = $AxonAdminUrl.TrimEnd("/")
$EndpointName = "wallet-read-service"
$HandlerName  = "wallet-read-projection"
$HealthUrl    = "/health"
$EventUrl     = "/internal/events/axon"

function Log([string]$m) { Write-Host "[register-projection] $m" }
function Fail([string]$m) { Write-Error "[register-projection] ERROR: $m"; exit 1 }

function Wait-ForAxon {
  Log "Waiting up to ${WaitTimeout}s for Axon Server at $AxonAdminUrl ..."
  $deadline = (Get-Date).AddSeconds($WaitTimeout)
  while ((Get-Date) -lt $deadline) {
    try {
      $r = Invoke-WebRequest -Uri "$AxonAdminUrl/actuator/health" -UseBasicParsing -TimeoutSec 5
      if ($r.StatusCode -eq 200) { Log "Axon Server healthy."; return }
    } catch {}
    try {
      $r = Invoke-WebRequest -Uri "$AxonAdminUrl/" -UseBasicParsing -TimeoutSec 5
      if ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400) { Log "Axon Server reachable ($($r.StatusCode))."; return }
    } catch {}
    Start-Sleep -Seconds 3
  }
  Fail "Timed out waiting for Axon Server at $AxonAdminUrl."
}

function Get-Endpoint {
  # Returns the existing endpoint object for $EndpointName, or $null.
  try {
    $all = Invoke-RestMethod -Uri "$AxonAdminUrl/v2/endpoints?context=$AxonContext" -Method Get
    return ($all | Where-Object { $_.name -eq $EndpointName } | Select-Object -First 1)
  } catch { return $null }
}

function Ensure-Endpoint {
  $payload = @{ name=$EndpointName; type="HTTP(S)"; baseUrl=$ReadBaseUrl; eventUrl=$EventUrl; healthUrl=$HealthUrl; wrappingType="Wrapped"; contentType="application/json" } | ConvertTo-Json -Compress
  try {
    $resp = Invoke-RestMethod -Uri "$AxonAdminUrl/v2/endpoints?context=$AxonContext" -Method Post -ContentType "application/json" -Body $payload
    Log "Endpoint '$EndpointName' registered."
    if ($resp.id) { return $resp.id }
  } catch {
    Log "Endpoint POST did not create a new endpoint (it may already exist) — looking it up."
  }
  $existing = Get-Endpoint
  if ($existing -and $existing.id) { Log "Reusing existing endpoint '$EndpointName' ($($existing.id))."; return $existing.id }
  Fail "Could not create or find endpoint '$EndpointName'."
}

function Ensure-EventHandler([string]$EndpointId) {
  # Skip if a handler with our name already exists on the endpoint.
  $existing = Get-Endpoint
  if ($existing -and $existing.eventHandlers) {
    $h = $existing.eventHandlers | Where-Object { $_.name -eq $HandlerName } | Select-Object -First 1
    if ($h) { Log "Event handler '$HandlerName' already registered ($($h.id)) — OK."; return }
  }
  # No server-side filter (DCB rejects payloadType); SequentialPerAggregatePolicy; TAIL = full replay.
  $payload = @{ name=$HandlerName; sequencingPolicy="SequentialPerAggregatePolicy"; segments=1; startPosition="TAIL"; batchSize=1; eventUrl=$EventUrl } | ConvertTo-Json -Compress
  try {
    Invoke-RestMethod -Uri "$AxonAdminUrl/v2/endpoints/$EndpointId/eventHandlers?context=$AxonContext" -Method Post -ContentType "application/json" -Body $payload | Out-Null
    Log "Event handler '$HandlerName' registered — persistent stream created from TAIL."
  } catch {
    Fail "Failed registering event handler '$HandlerName': $($_.Exception.Message)"
  }
}

Log "Axon admin URL : $AxonAdminUrl"
Log "Context        : $AxonContext (DCB)"
Log "Read base URL  : $ReadBaseUrl"
Wait-ForAxon
$endpointId = Ensure-Endpoint
Log "Endpoint UUID  : $endpointId"
Ensure-EventHandler -EndpointId $endpointId
Log "Done. wallet-read-projection persistent stream is registered from TAIL."
Log "Verify in the Axon Server dashboard at $AxonAdminUrl (:8024)."