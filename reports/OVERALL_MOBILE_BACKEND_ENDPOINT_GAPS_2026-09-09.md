# Overall Mobile Backend Endpoint Gaps

Date: 2026-09-09

This is the current admin handoff for Android and iOS. It consolidates only
work that is still missing, incorrect, unhealthy, or not yet verified. It does
not replace older reports, and it intentionally excludes Modern Dialer because
that feature is deferred until the admin configuration is ready.

## Important status distinction

The current production route audit does **not** show a new missing CP, SV,
Joint CP, completed-count, repair, login, device-reset, or Staff Select All
route. Those paths are deployed and authentication-protected.

The remaining work is mainly on existing endpoint behavior:

| Priority | Area | Current status | Admin action |
| --- | --- | --- | --- |
| Resolved | Direct GeoTrack writes | All five writes now reject invalid bearers with structured 401; both reads expose fresh online rows | Monitor latency and complete one controlled valid-token start-to-stop test |
| P0 | Storage file reads | Existing resolver redirects known files to a legacy route that returns 404 | Resolve external mappings and return the original bytes |
| P0 | Joint CP workflow | Routes exist, but reported records show participant visibility, role, outcome and final-credit inconsistencies | Make one authoritative participant workflow and repair affected records |
| P0 | CP/SV finalization | Routes exist, but partial/idempotent outcome completion is inconsistent | Return authoritative state and make retries idempotent |
| P0 | MMS GeoTrack proxy | Several existing routes currently return 502 | Repair the upstream integration/configuration |
| P0 | API latency | Several protected routes exceeded a 20-second safe probe | Remove backend stalls and keep operational responses below mobile timeouts |
| P1 | Staff Select All | Now deployed and returns structured 401 for an invalid token | Perform an authorized success-path check only; no new endpoint needed |

## 1. Direct GeoTrack authentication - resolved on 2026-09-09

Host:

```text
https://api-geo.theairix.com
```

Affected routes:

```http
POST /api/geotrack/start
POST /api/tracking/location/batch
POST /api/tracking/heartbeat
POST /api/tracking/tamper-events
POST /api/geotrack/stop
Authorization: Bearer <AIRIX_SESSION_TOKEN>
Idempotency-Key: <stable-request-id>
```

Current production behavior with an invalid bearer:

```http
HTTP 401 Unauthorized
```

```json
{
  "success": false,
  "error": "invalid credentials"
}
```

Verified behavior:

1. All five routes reject an invalid bearer with structured HTTP 401.
2. Android and iOS send bearer authentication and do not send a caller-controlled `staffId`.
3. Both live-read aliases return HTTP 200 and the same fresh online projection.
4. The reusable seven-route check passes end to end without mutating production data.

Observed invalid-token response:

```http
HTTP 401 Unauthorized
Content-Type: application/json
```

```json
{
  "success": false,
  "error": "invalid credentials"
}
```

Current read evidence:

```http
GET /api/tracking/live
GET /api/geotrack/live-status
```

Both reads return HTTP 200. The verified projection reported 352 rows, 291
with `trackingActive=true`, 24 with `isOnline=true`, 24 seen within five
minutes, and newest `lastSeen` `2026-09-09T06:56:51.462Z`. A controlled
valid-token start/heartbeat/location/stop test is still recommended before
declaring the success mutation semantics fully certified.

## 2. MMS GeoTrack proxy/upstream failures - existing routes need correction

Host:

```text
https://api-mfpl.theairix.com
```

These non-mutating invalid-token probes returned HTTP 502 on 2026-09-09:

```http
POST /api/geotrack/route
POST /api/tracking/device/sync
POST /api/geotrack/tamper/report
GET  /api/geotrack/timeline?dayStart=0&dayEnd=1
```

Required behavior:

- authenticate before forwarding or processing the request;
- return structured HTTP 401 for an invalid bearer;
- return structured 502/503 only for a genuine upstream outage and include a
  stable retryable error code;
- never convert upstream authentication/configuration failures into generic
  502 responses;
- use the same staff/session identity and attendance-window rules as the
  direct GeoTrack service.

Expected invalid-token response:

```json
{
  "success": false,
  "code": "UNAUTHORIZED",
  "error": "Invalid or expired session",
  "retryable": false
}
```

## 3. Storage file resolver - existing route needs correction

Preferred mobile read endpoint:

```http
GET https://mg.theairix.com/api/storage/files/{storageId}
```

This route is used for profile photos, CP/SV arrival photos, attendance photos,
chat attachments, collection evidence, signatures and other mobile media.

Current production evidence:

- A known profile-photo storage ID returns HTTP 307 to
  `https://api-mfpl.theairix.com/api/storage/serve?storageId=...`.
- Following that redirect returns HTTP 404 `File not found` instead of JPEG
  bytes.
- An unknown ID is also redirected to the legacy route instead of being
  resolved and rejected by the preferred resolver.

Required behavior for a known file:

```http
HTTP 307 Temporary Redirect
Location: https://<short-lived-signed-object-url>
Cache-Control: private, max-age=60
```

Following `Location` must return the original bytes and stored MIME type:

```http
HTTP 200 OK
Content-Type: image/jpeg
```

Required behavior for an unknown file:

```http
HTTP 404 Not Found
Content-Type: application/json
```

```json
{
  "success": false,
  "code": "FILE_NOT_FOUND",
  "error": "File not found"
}
```

Backend requirements:

1. Resolve the new external-storage mapping before attempting a legacy Convex
   fallback.
2. Backfill compatibility-marker IDs whose external object mapping is missing.
3. Never return `application/vnd.airix.external-storage-marker+json` to an
   image loader.
4. Keep the existing authenticated upload create, complete and abort routes.
5. Never expose S3, MinIO, storage-service or signing credentials to mobile.

Existing upload routes are deployed and correctly protected:

```http
POST   /api/storage/uploads
POST   /api/storage/uploads/{uploadId}/complete
DELETE /api/storage/uploads/{uploadId}
```

No additional upload endpoint is requested.

## 4. Joint CP participant workflow - existing routes need authoritative behavior

Affected existing routes:

```http
POST /api/marketing/clientPlaceVisits/create
GET  /api/marketing/clientPlaceVisits/my
GET  /api/marketing/clientPlaceVisits/get?id={cpVisitId}
GET  /api/marketing/clientPlaceVisits/joint-workflow?id={cpVisitId}
POST /api/geotrack/visit/start
POST /api/marketing/clientPlaceVisits/joint-arrival-preflight
POST /api/marketing/clientPlaceVisits/joint-participant-ready
POST /api/geotrack/visit/arrival-otp/request
POST /api/geotrack/visit/arrival-otp/verify
POST /api/marketing/clientPlaceVisits/joint-submit-review
POST /api/marketing/clientPlaceVisits/joint-complete-review
GET  /api/marketing/clientPlaceVisits/completed-count?date=YYYY-MM-DD
```

Required rules:

1. Reject the same person or equal designation/IAM levels during Joint CP
   creation with a readable validation code.
2. Both assigned staff must see the parent CP in their own list and search.
3. Each participant must have their own `fieldVisitId`, status, start time and
   current location.
4. The lower designation is `outcome_owner`: OTP, arrival photo and outcome.
5. The higher designation is `reviewer`: readiness swipe, outcome review/edit,
   required remark and final completion.
6. Both participants must have fresh locations and separation strictly below
   50 metres for the readiness/final-completion gate.
7. Final review must atomically complete the parent CP, both participant rows,
   both field visits/trips/tracking sessions and both completion credits.
8. Every mutation retry must be idempotent and must not add duplicate counts.
9. Do not expose a raw database identifier such as `k2g...` as the user-facing
   error.

Minimum `GET /joint-workflow` success response:

```json
{
  "success": true,
  "workflow": {
    "state": "pending_review",
    "actorRole": "reviewer",
    "outcomeOwnerStaffId": "lower-staff-id",
    "outcomeOwnerName": "Lower Staff",
    "reviewerStaffId": "higher-staff-id",
    "reviewerName": "Higher Staff",
    "actorReady": true,
    "isWithinCompletionRadius": true,
    "separationMeters": 18.4,
    "requiredRadiusMeters": 50,
    "canRequestOtp": false,
    "canSubmitOutcome": false,
    "canReview": true,
    "canCompleteReview": true,
    "outcomeRevision": 1,
    "outcome": "interested",
    "outcomeDraft": {}
  },
  "visit": {
    "id": "cp-id",
    "status": "in_progress",
    "effectiveStatus": "in_progress",
    "joint": {
      "participants": [
        {
          "staffId": "lower-staff-id",
          "staffName": "Lower Staff",
          "workflowRole": "outcome_owner",
          "fieldVisitId": "lower-field-visit-id",
          "status": "completed",
          "startedAt": 1788912300000
        },
        {
          "staffId": "higher-staff-id",
          "staffName": "Higher Staff",
          "workflowRole": "reviewer",
          "fieldVisitId": "higher-field-visit-id",
          "status": "in_progress",
          "startedAt": 1788912400000
        }
      ]
    }
  }
}
```

Minimum final-review response:

```json
{
  "success": true,
  "visit": {
    "id": "cp-id",
    "status": "completed",
    "effectiveStatus": "completed",
    "outcome": "interested",
    "completedAt": 1788998700000
  },
  "workflow": {
    "state": "completed",
    "outcomeRevision": 1,
    "reviewerRemark": "Outcome checked with the client",
    "completedAt": 1788998700000
  },
  "creditedStaffIds": ["lower-staff-id", "higher-staff-id"]
}
```

## 5. CP completed count - existing route needs the start-date rule

Endpoint:

```http
GET /api/marketing/clientPlaceVisits/completed-count?date=YYYY-MM-DD
```

Required counting rule:

- Count a CP only after authoritative final completion.
- Credit it on the authenticated staff participant leg's `startedAt` date in
  `Asia/Kolkata`, not creation, assignment, scheduled or completion date.
- For Joint CP, credit each participant once using that participant's own
  `startedAt`; the two dates may differ.
- Deduplicate by `(clientPlaceVisitId, staffId)`.
- Use `scheduledDate` only for a legacy completed record with no trustworthy
  start timestamp and mark that audit result as
  `dateSource: "scheduled_legacy_fallback"`.

Expected response:

```json
{
  "success": true,
  "date": "2026-09-09",
  "staffId": "staff-id",
  "completedCount": 1,
  "visitIds": ["cp-id"]
}
```

The same participant credit set must feed mobile counts, staff statistics and
target progress. The company-wide visit total still counts one Joint CP as one
visit.

## 6. CP and SV outcome finalization - existing routes need idempotent read-back

Affected routes:

```http
POST /api/geotrack/visit/complete
POST /api/marketing/clientPlaceVisits/setOutcome
POST /api/marketing/siteVisits/scanQr
POST /api/marketing/siteVisits/markOnCounselling
POST /api/marketing/siteVisits/setOutcome
GET  /api/sitevisits/my
```

Required behavior:

1. QR scan/counselling start must make the authorized SV outcome form
   available immediately.
2. `setOutcome` must atomically persist the outcome and return the updated
   authoritative record.
3. A retry after a lost response must return the already-committed success,
   not `Invalid transition ... from status completed`.
4. A legacy/completed SV that has no stored outcome must still accept the
   missing outcome; a completed SV with a stored outcome remains terminal.
5. CP completion must not leave the field visit completed while the parent CP
   remains scheduled/in-progress.
6. List/detail reads must return `status`, `effectiveStatus`, outcome and
   authoritative timestamps so Android, iOS and web render the same state.

Expected CP outcome response:

```json
{
  "success": true,
  "visit": {
    "id": "cp-id",
    "status": "completed",
    "effectiveStatus": "completed",
    "outcome": "interested",
    "completedAt": 1788998700000
  },
  "revisit": null
}
```

Expected SV outcome response:

```json
{
  "success": true,
  "siteVisit": {
    "id": "sv-id",
    "status": "completed",
    "effectiveStatus": "completed",
    "outcome": "follow_up",
    "outcomeSavedAt": 1788998700000
  }
}
```

## 7. Historical consistency and repair - routes exist; run them safely

Existing admin-only routes:

```http
GET  /api/marketing/clientPlaceVisits/completion-health?fromDate=YYYY-MM-DD&toDate=YYYY-MM-DD
POST /api/marketing/clientPlaceVisits/completion-repair/preview
POST /api/marketing/clientPlaceVisits/completion-repair/apply
```

These routes are now deployed and protected. Admin still needs to verify their
authorized behavior against affected records.

The preview must detect:

- missing Joint CP participant rows or participant field-visit links;
- role inversion/missing outcome-owner or reviewer IDs;
- completed field visits whose parent remains nonterminal;
- completed parents whose participant legs remain startable;
- missing/duplicate staff completion credits;
- credits grouped by creation, scheduled or completion date instead of
  participant start date.

Preview/apply requirements:

- preview is read-only and returns evidence plus `repairable`, `blocked` and
  `alreadyConsistent` classifications;
- apply requires the signed preview token and revalidates every predicate;
- preserve real OTP, photo, GPS, outcome, remark and timestamps;
- never invent reviewer approval or completion evidence;
- write an admin audit record;
- a second preview must return zero repairable rows.

## 8. Production latency - existing routes must be bounded

During the safe 2026-09-09 route audit, these requests exceeded a 20-second
probe and were aborted:

```http
GET  /api/hr/attendance/today
GET  /api/hr/attendance/day-sessions
POST /api/hr/attendance/punch-in
POST /api/hr/attendance/punch-out
POST /api/marketing/siteVisits/markPickedUp
GET  /api/marketing/inventory-units/layout?projectId=...
POST /api/bookings
POST /api/bookings/draft/clear
```

Other protected reads took 8-17 seconds. These are not missing routes, but the
latency can surface as mobile network errors and duplicate retries.

Required backend action:

- authenticate before expensive database work;
- use indexed, paginated and bounded queries;
- remove unbounded enrichment/collection passes;
- return deterministic structured errors before the mobile timeout;
- add per-route latency metrics and request IDs;
- keep mutations idempotent so a client retry cannot duplicate attendance,
  booking, CP, SV or collection data.

## 9. Confirmed deployed - do not create duplicates

The following route groups are currently present and protected:

- Employee-ID login, OTP login, session validation and logout;
- device-binding recovery, security read, single reset and bulk reset;
- `GET /api/hr/staff/selectable-ids` for Staff Select All;
- CP create/list/detail/outcome, arrival OTP and approval routes;
- all five Joint CP workflow routes;
- completed-count, completion-health and completion-repair routes;
- SV create/list/QR/counselling/outcome/postpone/cancel/booking routes;
- booking, collection and storage upload route surfaces.

On 2026-09-09, `GET /api/hr/staff/selectable-ids?status=active` returned
structured HTTP 401 for an invalid bearer. It is no longer a missing endpoint.
An authorized admin success-path test must still verify filters, IAM scope,
the 2,000-ID cap and response shape:

```json
{
  "success": true,
  "total": 1658,
  "staffIds": ["staffId1", "staffId2"]
}
```

## 10. Acceptance commands after deployment

Safe route/authentication checks:

```powershell
node scripts/check-mobile-api.mjs cp-sv-contracts --timeout 60000
node scripts/check-mobile-api.mjs geotrack-direct-contracts
node scripts/check-mobile-api.mjs staff-security-contracts
$env:MCONNECT_STORAGE_READ_ID='<known-image-storage-id>'
node scripts/check-mobile-api.mjs storage-contracts --storage-base-url https://mg.theairix.com/
```

Required results:

1. CP/SV contracts pass without 502 responses or timeouts.
2. All five direct GeoTrack invalid-bearer writes return structured HTTP 401.
3. A valid punched-in test account becomes online after start, heartbeat and
   one location batch, then becomes inactive after stop.
4. A known storage ID resolves to its original bytes/MIME type; an unknown ID
   returns JSON HTTP 404 at the preferred resolver.
5. Staff Select All returns the complete IAM-authorized ID set for each filter.
6. Two disposable different-level accounts complete one Joint CP end to end;
   the lower staff owns OTP/photo/outcome, the higher staff owns remark/review,
   both are inside 50 metres, both receive one count on their own start date,
   and web/mobile show the same terminal state.
7. A disposable SV can save an outcome from counselling and retry the same
   request without an invalid-transition failure.

Safe unauthenticated/invalid-token probes only prove route presence and auth
ordering. Final certification requires disposable authenticated accounts and
records; production staff visits must not be mutated for testing.
