# GeoTrack Zero Online: Direct Service Authentication Incident (Resolved)

Date: 2026-09-09

## Finding

The web dashboard and Android app point at the same direct GeoTrack service:

```text
https://api-geo.theairix.com
```

The dashboard could read the service, but the service initially rejected the secure mobile write contract before a heartbeat or location could refresh a staff member's online state. This was a GeoTrack backend authentication/configuration issue, not a dashboard counting issue and not an Android request-path mismatch.

## Resolution Verified

After the backend deployment, the complete seven-route check passed twice. All five invalid-bearer writes now return structured HTTP 401, both live-read aliases return HTTP 200, and both aliases reported the same fresh online projection: 352 rows, 291 tracking-active rows, 24 online rows, 24 seen within five minutes, and newest `lastSeen` `2026-09-09T06:56:51.462Z`.

No new mobile endpoint or caller-controlled `staffId` was added. Android and iOS continue to send their bearer session and derive identity on the server.

## Production Evidence

Read-only probes:

| Route | Result | Meaning |
| --- | --- | --- |
| `GET /api/tracking/live?limit=1` | HTTP 200, structured data | Preferred live read works |
| `GET /api/geotrack/live-status?limit=1` | HTTP 200, same structured data | Alias used by the web works |

The full alias response contained 352 live-status rows. It reported 291 rows with `trackingActive=true`, zero rows with `isOnline=true`, and a freshest `lastSeen` of `2026-09-08T10:28:04Z`. The API itself therefore reports zero online; the web is not losing or miscounting fresh online rows.

Invalid-bearer contract probes during the incident:

| Route | Actual result |
| --- | --- |
| `POST /api/tracking/location/batch` | HTTP 400 `staffId is required while authentication is disabled` |
| `POST /api/tracking/heartbeat` | HTTP 400 `staffId is required while authentication is disabled` |
| `POST /api/geotrack/start` | HTTP 400 `staffId is required while authentication is disabled` |

The required response for an invalid bearer is HTTP 401. Requiring `staffId` while authentication was disabled contradicted the mobile integration contract.

## Why The Dashboard Shows Zero Online

1. Android sends `Authorization: Bearer <Airix JWT>` to the direct service.
2. Android intentionally does not send a caller-controlled `staffId`.
3. The service reported that authentication was disabled and rejected the request because `staffId` was absent.
4. Start, heartbeat and location writes do not update `trackingActive`, `lastSeen` or the latest location.
5. The web successfully reads those stale rows and correctly classifies them as offline, producing an online count of zero.

Android keeps failed heartbeats/events and GPS points in its local queues, but replaying cannot succeed until the service accepts the bearer-token contract.

## Implemented Backend Fix

Configure every direct mobile write endpoint to:

1. Validate the Airix bearer JWT.
2. Derive the authoritative `staffId` from the validated token.
3. Reject an invalid or expired token with HTTP 401 and structured JSON.
4. Never trust an arbitrary mobile-supplied `staffId` for identity.
5. Apply staff/project authorization after token validation.
6. Keep idempotency behavior for retried start, heartbeat, location, tamper and stop requests.

No new endpoint is required. The existing routes must implement the supplied authentication contract.

## Routes To Recheck After Deployment

```text
POST /api/geotrack/start
POST /api/tracking/location/batch
POST /api/tracking/heartbeat
POST /api/tracking/tamper-events
POST /api/geotrack/stop
GET  /api/tracking/live
GET  /api/geotrack/live-status
```

Run from the Mconnect repository:

```powershell
node scripts/check-mobile-api.mjs geotrack-direct-contracts
```

The check must pass all seven requests. In particular, every invalid-bearer write must return HTTP 401, not HTTP 400.

## Authenticated Acceptance Test

After the contract check passes, use one disposable punched-in staff account:

1. Start tracking with its valid bearer token.
2. Send one heartbeat and one valid Chennai location point.
3. Confirm the same staff row appears with `trackingActive=true`, a fresh `lastSeen`, and `isOnline=true` on both live-read aliases.
4. Stop tracking.
5. Confirm `trackingActive=false` and that no false missed-heartbeat alert is generated after stop.

Do not enable a mobile fallback that sends `staffId`; that would allow identity spoofing and would only hide the service configuration defect.
