# MConnect Mobile - Remaining API Endpoints After Admin Handoff

Date: 2026-09-03

## Confirmed Supplied

The latest admin documents close these endpoint requests. Do not rebuild them:

- MMS Site Visit list and filters:
  - `GET /api/sitevisits/my`
  - `GET /api/sitevisits/filter-options`
- Direct GeoTrack at `https://api-geo.theairix.com`:
  - `POST /api/geotrack/start`
  - `POST /api/geotrack/stop`
  - `POST /api/tracking/location/batch`
  - `POST /api/tracking/heartbeat`
  - `POST /api/tracking/tamper-events`

Android and iOS are wired to the supplied direct GeoTrack write contract. The
remaining authenticated end-to-end acceptance test must use a real staff token.

## Missing MMS Endpoints

Base URL: `https://api-mfpl.theairix.com`

### 1. Mandatory mobile version policy

```http
GET /api/mobile/app-version
  ?platform=android|ios
  &currentVersion=<DISPLAY_VERSION>
  &buildNumber=<NUMERIC_BUILD>
```

This route must work before login.

```json
{
  "success": true,
  "platform": "android",
  "latestVersion": "1.1.0",
  "latestBuildNumber": 43,
  "minimumSupportedVersion": "1.1.0",
  "minimumSupportedBuildNumber": 43,
  "updateRequired": true,
  "updateUrl": "https://play.google.com/store/apps/details?id=com.manjugroups.mconnect",
  "publishedAt": "2026-09-03T12:00:00.000Z"
}
```

Current production result: HTTP 404.

### 2. Authoritative mobile Dialer history

```http
GET /api/mobile/dialer/history?limit=20&cursor=<OPTIONAL_CURSOR>
Authorization: Bearer <AIRIX_JWT>
```

```json
{
  "success": true,
  "calls": [
    {
      "callId": "call-123",
      "direction": "incoming",
      "status": "completed",
      "fromNumber": "919876543210",
      "toNumber": "919123456789",
      "displayName": "Actual client name",
      "startedAt": "2026-09-03T10:00:00.000Z",
      "answeredAt": "2026-09-03T10:00:05.000Z",
      "endedAt": "2026-09-03T10:00:45.000Z",
      "durationSeconds": 45,
      "talkTimeSeconds": 40
    }
  ],
  "nextCursor": null
}
```

Scope results to the authenticated staff member's Modern Dialer mapping.
Current production result: HTTP 404.

## Missing Dialer Provider Endpoints

Host required: one resolvable provider API host under `*.theairix.com`. The
configured `dialer-api.theairix.com` does not currently resolve, while the
following routes return HTTP 404 on `https://dialer.theairix.com`.

These are server-to-server routes. MMS sends `X-Service-Secret`; the secret must
never be returned to or embedded in Android/iOS.

```http
POST /api/mobile/device-tokens
GET  /api/mobile/calls/current
POST /api/mobile/calls/{callId}/action
POST /api/mobile/calls/{callId}/media/restart
GET  /api/mobile/calls/{callId}/media
```

Required behavior:

- `device-tokens`: upsert/deactivate FCM and APNs VoIP tokens by staff, device,
  platform, provider, bundle ID, and extension.
- `calls/current`: return the authoritative active call or `call: null`, with
  actual remote client name, expiry, stage, direction, and pickup requirement.
- `calls/{id}/action`: idempotently apply `pickup`, `reject`, or `hangup` even
  when the mobile WebView/process is not running.
- `calls/{id}/media/restart`: perform provider-side ICE restart/re-offer.
- `calls/{id}/media`: return sanitized ICE state, selected candidate type, RTP
  counters, and last-RTP timestamp; never return credentials or secrets.

## Existing Routes Needing Behavior Fixes

These endpoints already exist. No new route is needed.

### Attendance request shutdown

```http
POST /api/hr/attendance/request
```

For mobile `remark` and `correction` submissions, return HTTP 410 with code
`MOBILE_ATTENDANCE_REQUEST_DISABLED` before creating any record. This blocks old
APK/IPA builds after the controls have been removed from current mobile code.

### Attendance-bounded tracking

```http
GET  /api/hr/attendance/today
GET  /api/hr/attendance/day-sessions
GET  /api/tracking/bootstrap
POST /api/tracking/device/sync
```

Together with the supplied direct GeoTrack write routes, these must reject or
filter every location, heartbeat, and tamper timestamp outside the canonical
first-punch-to-final-punch-out window. A historical repair is still needed for
already stored pre-punch events.

### CP filter load hardening

```http
GET /api/marketing/clientPlaceVisits/filter-options
GET /api/marketing/clientPlaceVisits/my
```

These work sequentially but have produced operation-limit HTTP 500 responses
under concurrent status/outcome requests. Apply IAM and filters before related
row enrichment and paginate before hydration.

### Push delivery wiring

```http
POST /api/push/register
POST /api/push/unregister
```

Routes exist, but MMS/provider infrastructure must still forward FCM data-only
incoming/ended call events and APNs VoIP pushes. This is configuration and
delivery work, not a new mobile endpoint.

## Non-Endpoint Blocker

The two-way Dialer audio failure cannot be repaired by another mobile API alone.
The provider/Asterisk side still needs a reachable ICE/RTP candidate, working
TURN UDP/TCP/TLS fallback, trickle ICE in both directions, ICE restart support,
and a media timeout longer than the current approximately eight-second failure.
