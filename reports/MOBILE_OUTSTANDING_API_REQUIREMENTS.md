# MCONNECT MOBILE - OUTSTANDING API REQUIREMENTS

Date: 2026-09-03

## Scope

This is the single handoff for backend or infrastructure work still required by
the current Android and iOS features. It intentionally excludes routes that are
already deployed or have reached their expected authentication/validation
boundary.

Production base URL:

```text
https://api-mfpl.theairix.com
```

Direct GeoTrack transport URL (location traffic only):

```text
https://api-geo.theairix.com
```

All checks in this document were read-only. A `401` response without a bearer
token is used only to confirm that a route exists; it does not certify the full
authenticated business flow.

## Priority Summary

| Priority | Owner | Outstanding work | Current evidence |
| --- | --- | --- | --- |
| P0 | MMS/Site Visits backend | Make Site Visit filter options and filtered list reads bounded | Authenticated options request timed out; filtered list exceeded Convex 16 MiB read limit |
| P0 | Modern Dialer/Airix | Supply a resolvable provider API host and implement mobile provider routes | `dialer-api.theairix.com` did not resolve; corresponding routes on `dialer.theairix.com` returned 404 |
| P0 | Modern Dialer/Asterisk | Repair TURN/ICE/RTP media path | Calls answer but ICE fails with zero inbound/outbound RTP |
| P0 | MMS + Firebase/APNs | Complete real incoming-call push delivery | Android Firebase client config was absent; real FCM and iOS PushKit acceptance remain unverified |
| P0 | MMS Attendance/GeoTrack | Enforce punch-window tracking on bootstrap and ingestion | Timeline showed network/location events before the day's first punch |
| P0 | MMS Attendance backend | Reject mobile attendance remark/time-correction submissions | Current `/api/hr/attendance/request` still creates requests, so older mobile builds can bypass the removed UI |
| P0 | MMS Mobile platform | Publish a cross-store mobile version policy | Version-policy route returns 404 and the iOS bundle is not currently returned by Apple's India lookup |
| P1 | MMS Dialer backend | Add authoritative recent call history | `GET /api/mobile/dialer/history` returned 404 |
| P1 | MMS CP backend | Harden CP status/outcome filtering under concurrent load | Sequential calls return 200, but concurrent probes produced operation-limit 500s |

## 1. Site Visit Filters - P0

### 1.1 Filter options

Keep this existing route and response contract:

```http
GET /api/sitevisits/filter-options?fromDate=yyyy-MM-dd&toDate=yyyy-MM-dd
Authorization: Bearer <MMS_SESSION_TOKEN>
```

Expected response:

```json
{
  "success": true,
  "projects": [
    { "id": "<project-id>", "name": "Project", "count": 12 }
  ],
  "lmos": [
    { "id": "<staff-id>", "name": "LMO", "count": 8 }
  ],
  "fieldStaff": [
    { "id": "<staff-id>", "name": "Field Staff", "count": 5 }
  ],
  "statuses": [
    { "value": "scheduled", "label": "Scheduled", "count": 4 }
  ]
}
```

Required correction:

- Apply authentication and IAM scope before collecting facets.
- Read fresh `siteVisits` and legacy `fieldVisits` through indexed, paginated,
  bounded queries.
- Do not fully enrich every visit before counting option values.
- Deduplicate visits before counting.
- Hydrate labels only for unique IDs that survived authorization.
- Keep each Convex transaction comfortably below the 16 MiB read limit.
- Return HTTP 200 with empty arrays when no authorized options exist.

The supplied backend handoff describes a lean paginated implementation, but the
tested production deployment still did not answer within 60 seconds.

### 1.2 Filtered Site Visit list

```http
GET /api/sitevisits/my
  ?fromDate=yyyy-MM-dd
  &toDate=yyyy-MM-dd
  &projectId=<project-id>
  &telecallerStaffId=<staff-id>
  &assignedStaffId=<staff-id>
  &status=fixed|scheduled|enroute|onsite|returning_home|completed|cancelled|postponed
  &search=<text>
  &pageSize=20
  &cursor=<opaque-cursor>
Authorization: Bearer <MMS_SESSION_TOKEN>
```

Required correction:

- Apply IAM, date, search, and selected facets before related-row enrichment.
- Page before loading project, lead, client, staff, CP, and field-visit details.
- Avoid unbounded `.collect()` calls over the company Site Visit dataset.
- Preserve a stable newest-first order and opaque cursor.
- Return `success`, `visits`, `total`, `nextCursor`, and `hasMore`.
- Empty results must return HTTP 200, never 404 or 500.

Production evidence: an authenticated
`/api/sitevisits/my?status=scheduled&pageSize=20` request returned HTTP 500 after
the Convex execution read more than 16 MiB.

### Site Visit acceptance

1. Both routes return HTTP 200 for a production-equivalent high-volume user.
2. Every option count is limited to records the signed-in user can view.
3. Selecting any project, LMO, field staff, status, date, or search filter
   returns the same authorized records as web.
4. Empty combinations return an empty list promptly.
5. Query metrics remain below Convex read, memory, and operation limits.

## 2. Modern Dialer Provider API - P0

### 2.1 Resolve the provider API host

MMS reads the provider host from:

```text
telecaller.modernDialerApiUrl
```

The backend owner must provide one resolvable `*.theairix.com` API hostname.
Do not use any `aivida.in` domain.

Current checks:

- `dialer-api.theairix.com` did not resolve in DNS.
- `dialer.theairix.com` serves the embedded dialer, but the required
  `/api/mobile/*` server routes returned 404.

### 2.2 Device-token ingestion

```http
POST /api/mobile/device-tokens
Host: <MODERN_DIALER_API_HOST>
X-Service-Secret: <SERVER_ONLY_SECRET>
Content-Type: application/json
```

The payload must support both:

```json
{
  "token": "<TOKEN>",
  "platform": "android",
  "provider": "fcm",
  "bundleId": "com.manjugroups.mconnect",
  "deviceId": "<ANDROID_ID>",
  "extension": "1030",
  "externalId": "<STAFF_ID>",
  "active": true
}
```

```json
{
  "token": "<PUSHKIT_TOKEN>",
  "platform": "ios_voip",
  "provider": "apns_voip",
  "bundleId": "<IOS_BUNDLE_ID>",
  "deviceId": "<IOS_DEVICE_ID>",
  "extension": "1030",
  "externalId": "<STAFF_ID>",
  "active": true
}
```

Upsert by staff, platform, provider, bundle ID, and device ID. Deactivate on
logout and provider invalid-token feedback.

### 2.3 Current call

```http
GET /api/mobile/calls/current
  ?extension=<MAPPED_EXTENSION>
  &externalId=<STAFF_ID>
  &callId=<optional-call-id>
Host: <MODERN_DIALER_API_HOST>
X-Service-Secret: <SERVER_ONLY_SECRET>
```

```json
{
  "success": true,
  "call": {
    "callId": "<CALL_ID>",
    "direction": "incoming",
    "stage": "incoming",
    "fromNumber": "919876543210",
    "toNumber": "1030",
    "displayName": "Actual client name",
    "extension": "1030",
    "requiresPickup": true,
    "muted": false,
    "held": false,
    "startedAt": "<ISO-8601>",
    "expiresAt": "<ISO-8601>"
  }
}
```

Return `call: null` when idle. The provider must enforce extension/external-ID
ownership.

### 2.4 Idempotent call action

```http
POST /api/mobile/calls/{callId}/action
Host: <MODERN_DIALER_API_HOST>
X-Service-Secret: <SERVER_ONLY_SECRET>
Idempotency-Key: <UUID>
Content-Type: application/json
```

```json
{
  "action": "pickup|reject|hangup",
  "extension": "1030",
  "externalId": "<STAFF_ID>",
  "staffId": "<STAFF_ID>",
  "deviceId": "<DEVICE_ID>",
  "platform": "android|ios",
  "eventId": "<PUSH_EVENT_ID>",
  "source": "mms"
}
```

```json
{
  "success": true,
  "callId": "<CALL_ID>",
  "stage": "connecting|in_call|ended",
  "alreadyApplied": false
}
```

This server action is required for accept/reject from a terminated process;
mobile cannot depend on a hidden WebView already being registered.

### 2.5 Media restart

```http
POST /api/mobile/calls/{callId}/media/restart
Host: <MODERN_DIALER_API_HOST>
X-Service-Secret: <SERVER_ONLY_SECRET>
Idempotency-Key: <UUID>
Content-Type: application/json
```

```json
{
  "reason": "ice_failed",
  "extension": "1030",
  "externalId": "<STAFF_ID>",
  "staffId": "<STAFF_ID>",
  "deviceId": "<DEVICE_ID>",
  "platform": "android|ios",
  "source": "mms"
}
```

```json
{
  "success": true,
  "callId": "<CALL_ID>",
  "stage": "connecting",
  "iceRestarted": true
}
```

### 2.6 Sanitizable media diagnostics

```http
GET /api/mobile/calls/{callId}/media
  ?extension=<MAPPED_EXTENSION>
  &externalId=<STAFF_ID>
Host: <MODERN_DIALER_API_HOST>
X-Service-Secret: <SERVER_ONLY_SECRET>
```

```json
{
  "success": true,
  "callId": "<CALL_ID>",
  "media": {
    "iceConnectionState": "connected",
    "iceGatheringState": "complete",
    "connectionState": "connected",
    "signalingState": "stable",
    "selectedCandidatePair": {
      "localType": "relay",
      "remoteType": "relay",
      "transport": "udp"
    },
    "inboundAudioPackets": 120,
    "inboundAudioBytes": 42100,
    "outboundAudioPackets": 118,
    "outboundAudioBytes": 39900,
    "lastRtpAt": "<ISO-8601>"
  }
}
```

Never return TURN usernames/passwords, ICE ufrag/pwd, service secrets, tokens,
API keys, or provider credentials.

## 3. Modern Dialer Push Delivery - P0

### Existing MMS registration routes

These MMS routes exist, but real end-to-end delivery is not yet certified:

```http
POST /api/push/register
POST /api/push/unregister
Authorization: Bearer <MMS_SESSION_TOKEN>
```

MMS must accept and forward:

- Android: `platform=android`, `provider=fcm`.
- iOS VoIP: `platform=ios_voip`, `provider=apns_voip`.

### Android incoming payload

Send a high-priority, data-only FCM message with a 30-60 second TTL and collapse
key based on `callId`. Do not include an FCM `notification` block.

```json
{
  "type": "dialer-call-incoming",
  "eventId": "<UNIQUE_EVENT_ID>",
  "callId": "<CALL_ID>",
  "callUuid": "<OPTIONAL_UUID>",
  "fromNumber": "919876543210",
  "clientName": "Actual client name",
  "extension": "1030",
  "requiresPickup": "true",
  "expiresAt": "<ISO-8601_OR_EPOCH_MS>"
}
```

`clientName` must be the remote client/contact, never the receiving staff name.

### iOS incoming payload

Use APNs VoIP/PushKit:

```text
apns-push-type: voip
apns-topic: <IOS_BUNDLE_ID>.voip
apns-expiration: 0
apns-priority: 10
```

Send the same call identity fields as Android so CallKit can report the call
immediately while the app is terminated.

### Terminal payload

Send to every active device for the call:

```json
{
  "type": "dialer-call-ended",
  "eventId": "<UNIQUE_EVENT_ID>",
  "callId": "<CALL_ID>",
  "reason": "remote_hangup|answered_elsewhere|rejected|expired"
}
```

This must stop Android ringing/full-screen UI and end the iOS CallKit call
immediately.

### Infrastructure still required

- Supply valid Firebase Android client values for package
  `com.manjugroups.mconnect`: application ID, project ID, API key, sender ID,
  and storage bucket when used.
- Confirm MMS successfully registers the generated FCM token and forwards it
  to the provider.
- Configure the signed iOS build with PushKit/VoIP entitlement and matching APNs
  credentials/topic.
- Log provider message IDs and invalid-token feedback without logging tokens.

## 4. Modern Dialer Media Path - P0

No additional Android or iOS endpoint can repair the observed media failure.
The provider/Asterisk media path must:

- Advertise a public, reachable ICE/RTP candidate rather than only a private or
  unreachable UDP host candidate.
- Provide valid STUN/TURN credentials with UDP, TCP, and TLS 443 fallback.
- Forward trickled ICE candidates in both directions.
- Keep ICE gathering/signalling alive long enough to test relay candidates.
- Support provider-side ICE restart/re-offer.
- Validate NAT/firewall forwarding for the configured RTP port range.
- Validate DTLS fingerprint negotiation and packet flow.
- Do not end the call at the current roughly 8-16 second ICE failure boundary;
  use a defensible media timeout of about 30 seconds and retry ICE where safe.
- Do not report a usable answered media state until a candidate pair is selected
  and RTP can flow.

Observed evidence from a real Android call:

- Destination ringing and `call:answered` worked after Airtel GSM correction.
- Android microphone capture and remote audio element were active.
- Android gathered valid host, server-reflexive, and TURN relay candidates.
- The remote SDP exposed only an unreachable UDP host candidate.
- ICE remained `checking`, then failed with zero inbound and outbound RTP bytes.

### Dialer acceptance

1. Outbound and incoming calls reach ICE `connected` or `completed`.
2. Inbound and outbound RTP counters both become non-zero on Wi-Fi and mobile
   data.
3. Both parties can hear and speak for at least five minutes.
4. Answer, reject, and hangup work from foreground, background, locked, and
   terminated app states without duplicate actions.
5. Android receives a real FCM call while locked/asleep/closed.
6. iOS receives a real PushKit call while locked/asleep/terminated.
7. A terminal push clears every device immediately.

## 5. Attendance-Bounded GeoTrack - P0

No new route is required. The existing attendance and tracking routes must
share one authoritative rule: tracking is active only while the authenticated
staff member has an open attendance session for the current business date.

### Attendance authority

Mobile verifies both existing views because deployments may update one summary
before the other:

```http
GET /api/hr/attendance/today?date=yyyy-MM-dd
GET /api/hr/attendance/day-sessions?date=yyyy-MM-dd
Authorization: Bearer <MMS_SESSION_TOKEN>
```

At least one successful response must confirm either:

- `hasOpenSession: true`, or
- a canonical session row with non-empty `punchInTime` and empty/null
  `punchOutTime`.

Before the first punch, and after the final punch-out, both endpoints must
return `hasOpenSession: false` with no open session row. Mobile treats a failed
or ambiguous cold-start check as unknown and does not begin tracking.

### Bootstrap/device sync behavior

```http
GET  /api/tracking/bootstrap?deviceId=<DEVICE_ID>
POST /api/tracking/device/sync
Authorization: Bearer <MMS_SESSION_TOKEN>
```

Required response rule:

```json
{
  "success": true,
  "data": {
    "shouldTrack": false,
    "activeSession": null
  }
}
```

Return that state whenever no attendance session is currently open, even if:

- the user has an attendance/site-visit tracking assignment;
- yesterday's tracking session was not closed correctly;
- the app was updated or the device rebooted;
- a stale device record says tracking was previously active;
- a CP, SV, or field task is scheduled but its staff member has not punched in.

`assignment` describes eligibility; it must not independently make
`shouldTrack=true`. When an open attendance session exists, return one active
tracking session bounded to that attendance session.

### Tracking ingestion enforcement

Apply the same punch-window validation to:

```http
POST https://api-geo.theairix.com/api/tracking/location/batch
POST https://api-geo.theairix.com/api/tracking/heartbeat
POST https://api-geo.theairix.com/api/tracking/tamper-events
Authorization: Bearer <AIRIX_SESSION_TOKEN>
Idempotency-Key: <STABLE_REQUEST_ID>
```

Mobile also brackets the attendance tracking window through the direct control
routes, retrying the same command after a transient failure:

```http
POST https://api-geo.theairix.com/api/geotrack/start
POST https://api-geo.theairix.com/api/geotrack/stop
```

The direct service must derive `staffId` from the validated bearer token. No
mobile build contains or sends an `X-API-Key`. MMS-only lifecycle events not in
the direct service's supported tamper enum continue to use the existing MMS
route rather than being submitted as invalid GeoTrack events.

Required behavior:

- Resolve staff only from the bearer token and tracking session ownership.
- Validate every location `recordedAt`, heartbeat `recordedAt`, and tamper
  `detectedAt` against the linked attendance punch-in/punch-out interval.
- Never create timeline, live-status, distance, network, battery, permission,
  location-disabled, GPS, or tamper evidence before the first punch.
- Never accept evidence after punch-out, except buffered evidence whose original
  timestamp is inside the closed attendance interval.
- Do not rewrite an old buffered event to the upload time; preserve and validate
  its original occurrence time.
- Reject an unknown/foreign/stale tracking session without updating live status.
- Prefer an idempotent per-item result so one invalid point does not discard
  valid in-window points from the same offline batch.

Recommended outside-window response:

```http
HTTP 409 Conflict
```

```json
{
  "success": false,
  "code": "OUTSIDE_ATTENDANCE_WINDOW",
  "error": "Tracking is not active until the staff member clocks in",
  "retryable": false
}
```

For batch uploads, an HTTP 200 result with accepted/rejected item counts is also
valid and avoids retrying permanently invalid points:

```json
{
  "success": true,
  "accepted": 18,
  "rejected": 2,
  "rejections": [
    { "index": 0, "code": "OUTSIDE_ATTENDANCE_WINDOW" }
  ]
}
```

### GeoTrack acceptance

1. Fresh login, reboot, app update, and periodic worker runs before the first
   punch produce no GeoTrack timeline or live-status events.
2. The first mobile, biometric, manual, or approved import punch opens tracking.
3. A location-disabled or network-offline state before punch-in is not shown in
   the staff timeline.
4. Tracking continues through a temporary network outage after a confirmed
   punch and uploads only original in-window timestamps later.
5. Punch-out stops new points, heartbeat, network, permission, and tamper events.
6. A stale session from the previous day cannot reopen tracking.
7. CP/SV trip-start checks still require the attendance gate and cannot bypass
   it by supplying a tracking session ID.

### Historical repair

Run a bounded, auditable repair for already affected dates:

- For each staff/business date, resolve the first valid punch-in and final
  punch-out from canonical attendance sessions.
- Remove or quarantine GeoTrack timeline events and location points whose
  original timestamp is before the first punch or after the final punch-out.
- Recompute live status, route distance, stop/movement summaries, offline
  windows, battery history, and tamper counts from the retained in-window data.
- Do not alter attendance punches or valid CP/SV trip records.
- Record affected staff/date, removed counts, operator, and repair timestamp.
- Support dry-run output before mutation. For the reported case, dry-run Sara's
  affected date and confirm the 12:08 AM-1:32 AM events are outside her 9:36 AM
  first-punch window before removing them.

## 6. Disable Mobile Attendance Requests - P0

Android and iOS no longer expose or compile a staff submission flow for
attendance remarks or punch-time corrections. The existing authenticated HTTP
route still accepts those requests, however, which means an older installed
APK/IPA can continue creating them.

Keep the route so old clients receive an explicit terminal response, but stop
it before `attendanceRequests.submit` is called:

```http
POST /api/hr/attendance/request
Authorization: Bearer <MMS_SESSION_TOKEN>
Content-Type: application/json
```

For every mobile request with `type` equal to `remark` or `correction`, return:

```http
HTTP/1.1 410 Gone
Content-Type: application/json
```

```json
{
  "success": false,
  "code": "MOBILE_ATTENDANCE_REQUEST_DISABLED",
  "error": "Attendance remark and time correction requests are not available in the mobile app.",
  "retryable": false
}
```

Required behavior:

- Do not insert or update `attendanceRequests`, `staffAttendance`, audit,
  approval-task, or notification records.
- Enforce this regardless of staff role, permission, date, attendance row, or
  app version.
- Preserve read/review/approve/reject access for requests already present.
- Preserve the web workflow if it calls the Convex mutation directly; this
  guard applies only to the mobile HTTP route above.
- Do not return HTTP 200, because old clients must not mistake rejection for a
  successful request.

Acceptance checks:

1. A valid bearer token plus `type: "remark"` returns 410 and creates no row.
2. A valid bearer token plus `type: "correction"` returns 410 and creates no row.
3. The same checks pass for ordinary staff, managers, and admins.
4. Existing attendance request review continues to load and approve/reject.
5. Web-originated attendance requests continue to follow the web policy.

## 7. Mandatory Mobile Version Policy - P0

Google Play flexible updates are supported directly by Android. iOS also checks
Apple's public bundle lookup, but the production bundle
`com.manjugroups.mconnect` currently returns zero App Store results in India.
Both apps therefore need one public, read-only release-policy endpoint for
private distribution and store propagation delays.

```http
GET /api/mobile/app-version
  ?platform=android|ios
  &currentVersion=1.0
  &buildNumber=42
X-App-Version: 1.0
X-App-Build: 42
```

This route must be callable before login and must return no staff, tenant, or
environment secrets.

```json
{
  "success": true,
  "platform": "ios",
  "latestVersion": "1.1.0",
  "latestBuildNumber": 43,
  "minimumSupportedVersion": "1.1.0",
  "minimumSupportedBuildNumber": 43,
  "updateRequired": true,
  "updateUrl": "https://apps.apple.com/in/app/<app-slug>/id<apple-app-id>",
  "publishedAt": "2026-09-03T12:00:00.000Z"
}
```

Android `updateUrl` should be the Play listing URL. For private Android
distribution it may be the approved managed-distribution page, but never a raw
untrusted URL. iOS must use its App Store, TestFlight, or approved managed-app
distribution URL.

Required behavior:

- Compare numeric build numbers server-side; version strings are display data.
- Set `updateRequired: true` whenever the installed build is below
  `minimumSupportedBuildNumber`.
- Advance `latestBuildNumber` for every released build so both apps can require
  the newest published version under the requested policy.
- Keep the response stable and cacheable for at most five minutes.
- Never revoke a build before its replacement URL is downloadable.
- Roll out the replacement first, verify installation, then raise the minimum.
- Return HTTP 200 for both platforms even when no update is needed.

Mobile behavior already implemented:

- Update checks run periodically and again on foreground/login transitions.
- A detected policy is persisted, so losing network does not dismiss it.
- Active attendance, CP/SV/field tracking, on-duty trips, dialer calls, queued
  punches, and unsynced tracking data defer installation.
- Once operationally idle, the update gate has no Later/close/back action.
- Android prefers Play flexible background download and completes a downloaded
  update only when safely backgrounded. iOS opens the approved distribution URL;
  iOS does not allow an app to silently install its own update.

Acceptance checks:

1. Current Android/iOS builds receive HTTP 200 with the correct platform policy.
2. A below-minimum idle build cannot dismiss the update gate.
3. A below-minimum build in an active call/visit/shift continues syncing and is
   gated only after all active and queued work is closed.
4. Returning from the store without updating restores the gate.
5. Installing the required build clears the persisted gate on next launch.
6. A policy or store outage does not erase a previously detected requirement.

## 8. Dialer Recent History - P1

This MMS route is still absent:

```http
GET /api/mobile/dialer/history?limit=20&cursor=<optional>
Authorization: Bearer <MMS_SESSION_TOKEN>
```

Expected response:

```json
{
  "success": true,
  "calls": [
    {
      "callId": "<CALL_ID>",
      "direction": "incoming|outbound",
      "status": "completed|missed|rejected|failed|no_answer",
      "fromNumber": "919876543210",
      "toNumber": "919123456789",
      "displayName": "Actual client name",
      "startedAt": "<ISO-8601>",
      "answeredAt": "<ISO-8601_OR_NULL>",
      "endedAt": "<ISO-8601>",
      "durationSeconds": 45,
      "talkTimeSeconds": 31
    }
  ],
  "nextCursor": null
}
```

Scope history to the authenticated staff member's active Modern Dialer mapping.
Until deployed, both apps keep a limited local history that is not authoritative
across devices or reinstalls.

## 9. CP Filter Load Hardening - P1

The following routes are deployed and work during normal sequential mobile use:

```http
GET /api/marketing/clientPlaceVisits/filter-options
GET /api/marketing/clientPlaceVisits/my
Authorization: Bearer <MMS_SESSION_TOKEN>
```

However, concurrent authenticated requests using `status` or `outcome` produced
HTTP 500 operation-limit timeouts during stress checks. Sequential Android
requests subsequently returned HTTP 200 in about 1-5 seconds.

Required hardening:

- Apply `scope`, `status`, and `outcome` through indexed queries before row
  enrichment.
- Page before loading clients, places, staff, field visits, and linked rows.
- Preserve direct-report-only IAM and Joint CP participant visibility.
- Return HTTP 200 with an empty `visits` array when no rows match.
- Preserve `scope`, `directReportIds`, `total`, `nextCursor`, and `hasMore`.

Mobile currently retries a 5xx status/outcome request without only those two
facets and applies the same filters locally to the bounded authorized result.
This is a compatibility fallback, not a replacement for backend hardening.

## Confirmed Existing - Do Not Rebuild

The following routes now exist or have already passed authenticated read checks
or unauthenticated route-boundary checks. They are not outstanding API gaps:

```http
POST /api/marketing/siteVisits/create
GET  /api/clients/referral-candidates
GET  /api/clients/search-by-phone
POST /api/marketing/clientPlaceVisits/referral
POST /api/postsales/loans/upload-document
POST /api/postsales/loans/delete-document
GET  /api/storage/get-url
POST /api/marketing/clientPlaceVisits/cancel
POST /api/marketing/clientPlaceVisits/markClientMet
POST /api/marketing/clientPlaceVisits/setOutcome
POST /api/geotrack/visit/complete
GET  /api/marketing/clientPlaceVisits/filter-options
GET  /api/hr/attendance/filter-options
GET  /api/marketing/bookings/filter-options
GET  /api/hr/leaves/filter-options
GET  /api/hr/permissions/filter-options
GET  /api/hr/attendance/today
GET  /api/hr/attendance/day-sessions
GET  /api/tracking/bootstrap
POST /api/tracking/device/sync
POST https://api-geo.theairix.com/api/geotrack/start
POST https://api-geo.theairix.com/api/geotrack/stop
POST https://api-geo.theairix.com/api/tracking/location/batch
POST https://api-geo.theairix.com/api/tracking/heartbeat
POST https://api-geo.theairix.com/api/tracking/tamper-events
GET  /api/mobile/dialer/calls/current
POST /api/mobile/dialer/calls/{callId}/action
POST /api/mobile/dialer/calls/{callId}/media/restart
GET  /api/mobile/dialer/calls/{callId}/media
POST /api/push/register
POST /api/push/unregister
```

The four MMS dialer proxy routes above exist, but their provider-backed success
still depends on completing Sections 2-4.

## Final Release Gate

Do not mark the outstanding work complete until:

1. Site Visit options and filtered list return HTTP 200 under production-scale
   data.
2. The exact Modern Dialer API hostname resolves and every provider route passes
   authenticated server-to-server tests.
3. Android and iOS receive real incoming and terminal pushes on locked and
   terminated devices.
4. A real call carries non-zero two-way RTP without the 8-16 second disconnect.
5. Dialer history returns authoritative staff-scoped records.
6. A pre-punch reboot/app launch produces no tracking or timeline evidence, and
   ingestion rejects every timestamp outside the attendance window.
7. CP concurrent filter tests complete without operation-limit failures.
8. The mobile attendance request endpoint rejects both remark and correction
   submissions without creating any records.
9. The public mobile version-policy endpoint returns valid Android and iOS
   release URLs and enforces the configured minimum build after active work ends.
