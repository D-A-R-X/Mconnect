# MConnect Mobile - Remaining MMS API Requirements

Date: 2026-09-03

Base URL: `https://api-mfpl.theairix.com`

## Already Supplied

The latest admin handoff confirms these routes are implemented. Do not rebuild
them:

```http
GET /api/sitevisits/my
GET /api/sitevisits/filter-options
GET /api/mobile/app-version
```

Direct GeoTrack writes are also supplied separately at
`https://api-geo.theairix.com` and are no longer part of the missing MMS list.

The public app-version route was verified in production on 2026-09-03. It
returns HTTP 200, the documented Android/iOS policy fields, and
`Cache-Control: public, max-age=300`. Android build 42 is required to update,
build 43 is accepted, and the iOS default remains non-blocking without an
approved distribution URL.

## Missing Endpoints

### 1. Authoritative Dialer call history

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

Requirements:

- Resolve staff from the bearer token.
- Scope calls to that staff member's active Modern Dialer mapping.
- Return the actual remote client/contact name, not the receiving staff name.
- Support stable newest-first cursor pagination.
- Include incoming, outgoing, missed, rejected, failed, and no-answer calls.
- Return HTTP 200 with an empty `calls` array when no history exists.

Current production result: HTTP 404.

## Existing Endpoints Needing Corrections

No new routes are required for this section.

### 1. Disable mobile attendance requests

```http
POST /api/hr/attendance/request
Authorization: Bearer <AIRIX_JWT>
```

For mobile requests with `type=remark` or `type=correction`, return:

```http
HTTP/1.1 410 Gone
```

```json
{
  "success": false,
  "code": "MOBILE_ATTENDANCE_REQUEST_DISABLED",
  "error": "Attendance remark and time correction requests are not available in the mobile app.",
  "retryable": false
}
```

Reject before creating attendance requests, audit rows, approval tasks, or
notifications. Existing request review and the separate web policy may remain.

### 2. Enforce the attendance tracking window

```http
GET  /api/hr/attendance/today?date=yyyy-MM-dd
GET  /api/hr/attendance/day-sessions?date=yyyy-MM-dd
GET  /api/tracking/bootstrap?deviceId=<DEVICE_ID>
POST /api/tracking/device/sync
```

Requirements:

- Before first punch and after final punch-out, bootstrap must return
  `shouldTrack=false` and `activeSession=null`.
- A tracking assignment alone must never start tracking.
- Direct GeoTrack must validate every original location, heartbeat, and tamper
  timestamp against the authenticated staff member's punch interval.
- Buffered in-window events may upload after reconnect; out-of-window events
  must be rejected or explicitly filtered.
- Repair already stored pre-punch/post-punch evidence and recompute derived
  distance, live status, battery, network, and tamper summaries.

### 3. Harden CP filters under concurrent load

```http
GET /api/marketing/clientPlaceVisits/filter-options
GET /api/marketing/clientPlaceVisits/my
Authorization: Bearer <AIRIX_JWT>
```

These routes work sequentially but have returned operation-limit HTTP 500
responses under concurrent status/outcome filtering.

Requirements:

- Apply IAM, scope, status, outcome, CP type, date, staff, and search filters
  before related-row enrichment.
- Page before hydrating clients, places, staff, field visits, and linked rows.
- Preserve direct-report-only access and Joint CP participant visibility.
- Return HTTP 200 with an empty list when no authorized records match.

### 4. Complete push registration forwarding

```http
POST /api/push/register
POST /api/push/unregister
Authorization: Bearer <AIRIX_JWT>
```

These routes exist. MMS must forward Android FCM and iOS APNs VoIP tokens to
the Dialer provider's device-token endpoint and deactivate them on logout or
invalid-token feedback. Never log or return complete device tokens.
