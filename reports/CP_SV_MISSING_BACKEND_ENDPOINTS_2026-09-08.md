# CP/SV Missing Backend Contracts - 2026-09-08

This is a new standalone backend handoff. It does not replace or modify earlier
endpoint documents.

## 1. Protect the mobile dashboard before returning data

### Existing route

`GET /api/mobile/dashboard?date=YYYY-MM-DD`

### Current production response

An unauthenticated request returns HTTP `200`, `success: true`, and company-level
staff totals. Every other CP/SV business read checked in this audit requires auth.

### Required response

Authenticate before reading or aggregating dashboard data:

```json
{
  "success": false,
  "error": "Unauthorized",
  "code": "UNAUTHORIZED"
}
```

Return HTTP `401`. An authenticated request may continue returning the current
dashboard payload.

## 2. Authenticate booking detail before parsing the ID

### Existing route

`GET /api/bookings/{id}`

### Current production response

An unauthenticated malformed probe returns HTTP `500` because the path value is
validated as a database ID before auth. `PATCH`, approve, and reject correctly
return structured `401` first.

### Required behavior

1. Authenticate and authorize the request.
2. Return structured HTTP `401` when unauthenticated.
3. Only then validate `{id}`.
4. Return structured HTTP `400` with `code: "INVALID_BOOKING_ID"` for an invalid
   authenticated ID; never return HTTP `500` for client input.

## 3. Add/fix uploaded-file readback

### Required route

`GET /api/storage/files/{storageId}` on `https://mg.theairix.com/`

### Current production response

Raw HTTP `404` before authentication.

### Required behavior

- Authenticate and authorize the caller or issue a short-lived signed read URL.
- Resolve both new storage IDs and legacy external-storage markers.
- Return/redirect to the real bytes with the stored `Content-Type`.
- Never return marker JSON when a mobile/web image component expects image bytes.
- Return structured `401`, `403`, or `404` errors as appropriate.

Example metadata response is also acceptable if all clients use it consistently:

```json
{
  "success": true,
  "storageId": "...",
  "contentType": "image/jpeg",
  "downloadUrl": "https://short-lived-signed-url"
}
```

## 4. Preserve CP/SV outcome fields in final visit completion

### Existing route

`POST /api/geotrack/visit/complete`

### Problem found in tracked backend source

The handler forwards only `id`, location, remarks, and
`arrivalPhotoStorageId` to `completeVisit`. Android also sends:

- `clientMet`
- `outcome`
- `cpOutcomeNotes`
- `postponeReasons`
- `followUpDate`
- `followUpTime`

These fields are required by the combined CP/SV completion flow. Ignoring them
can complete the field trip while leaving CP/SV outcome or confirmation state
pending, which then produces different mobile/web statuses.

### Required request contract

```json
{
  "visitId": "fieldVisitId",
  "lat": 13.0,
  "lng": 80.0,
  "remarks": "optional",
  "arrivalPhotoStorageId": "optional",
  "clientMet": true,
  "outcome": "converted_to_site_visit",
  "cpOutcomeNotes": "optional",
  "postponeReasons": [],
  "followUpDate": "2026-09-10",
  "followUpTime": "10:30"
}
```

### Required atomic response

```json
{
  "success": true,
  "visitId": "...",
  "status": "completed",
  "cpVisitStatus": "completed",
  "siteVisitStatus": "confirmed",
  "outcome": "converted_to_site_visit"
}
```

The field visit, linked CP/SV state, outcome, and list/count projections must
commit atomically or the whole request must fail without a partial completion.
Repeated requests with the same idempotency key must return the committed result.

`POST /api/marketing/clientPlaceVisits/setOutcome` must return the updated parent
row, not only `success: true`:

```json
{
  "success": true,
  "visit": {
    "_id": "cpVisitId",
    "status": "completed",
    "outcome": "interested",
    "completedAt": 1788878700000
  },
  "revisit": null
}
```

Mobile now verifies this response and re-reads the CP when the row is absent. A
non-Joint outcome is accepted only when the returned outcome matches the request
and status is terminal (`completed`, `postponed`, `pending_gm_approval`, or
cancelled). Do not return `success: true` while leaving the parent `scheduled`.

## 5. Publish the Joint CP implementation in the tracked backend source

Production exposes four protected workflow routes that were not found in the
available tracked backend `convex/http.ts` source. The fifth reviewer-readiness
route below is newly required and currently returns HTTP `404`:

- `GET /api/marketing/clientPlaceVisits/joint-workflow`
- `POST /api/marketing/clientPlaceVisits/joint-arrival-preflight`
- `POST /api/marketing/clientPlaceVisits/joint-participant-ready`
- `POST /api/marketing/clientPlaceVisits/joint-submit-review`
- `POST /api/marketing/clientPlaceVisits/joint-complete-review`

Reconcile the deployed implementation with source control so it can be reviewed,
tested, and redeployed safely. `joint-workflow` must return at least:

```json
{
  "success": true,
  "workflow": {
    "state": "pending_review",
    "actorRole": "reviewer",
    "outcomeOwnerStaffId": "...",
    "reviewerStaffId": "...",
    "canRequestOtp": false,
    "canSubmitOutcome": false,
    "canReview": true,
    "canCompleteReview": true,
    "separationMeters": 12.5,
    "isWithinCompletionRadius": true,
    "requiredRadiusMeters": 50,
    "outcomeRevision": 7,
    "outcome": "interested",
    "outcomeSummary": "Interested",
    "outcomeDraft": {}
  }
}
```

The lower IAM/template level owns OTP, photo, and outcome. The higher level owns
review remarks and final completion. Equal template/level pairs must be rejected.
The final mutation must validate the latest `expectedOutcomeRevision`, enforce
distance below 50 m, and complete both participant trips plus both completed
counts in one transaction.

### Higher-level participant swipe

`POST /api/marketing/clientPlaceVisits/joint-participant-ready` is required for
the higher-level participant's explicit no-OTP swipe:

```json
{
  "id": "jointCpVisitId",
  "fieldVisitId": "reviewerFieldVisitId",
  "lat": 13.0831,
  "lng": 80.1754,
  "accuracyMeters": 12.5,
  "capturedAt": 1788503400000
}
```

The backend must authenticate that this bearer is the assigned reviewer, bind
the field visit to that participant, accept only fresh/accurate points, and
require strict separation `< 50.0` metres from the outcome owner. It must not
send/request an OTP, publish an outcome, complete the parent CP, or increment a
count. On success it records reviewer readiness and returns:

```json
{
  "success": true,
  "workflow": {
    "state": "awaiting_owner_outcome",
    "actorRole": "reviewer",
    "actorReady": true,
    "isWithinCompletionRadius": true,
    "separationMeters": 18.4,
    "requiredRadiusMeters": 50,
    "canRequestOtp": false,
    "canSubmitOutcome": false,
    "canReview": false,
    "canCompleteReview": false
  }
}
```

Return `409 PARTNER_LOCATION_STALE`, `409 LOCATION_ACCURACY_LOW`, or
`409 PARTNER_TOO_FAR` without setting `actorReady` when the gate fails. The
workflow read must return `actorReady` for the current bearer so reopening the
screen does not ask the reviewer to swipe twice.

## 6. Fix participant-aware completed counts

### Required route

`GET /api/marketing/clientPlaceVisits/completed-count?date=YYYY-MM-DD`

Optional manager/admin query: `&staffId={staffId}`. A normal staff session must
always be restricted to itself. Team/admin reads require the existing IAM scope.

### Counting rule

- Count a normal CP once for `assignedStaffId`.
- Count a Joint CP once for each of its two rows in
  `clientPlaceVisitParticipants`.
- Do not count the telecaller merely because they created/fixed the CP.
- Use the IST calendar date derived from authoritative `completedAt`, never
  `scheduledDate` and never the repair execution time.
- Deduplicate by `(clientPlaceVisitId, staffId)`.

Example response for either participant:

```json
{
  "success": true,
  "date": "2026-09-05",
  "staffId": "staffId",
  "completedCount": 3,
  "visitIds": ["cp1", "jointCp1", "cp3"]
}
```

The same participant-aware staff set must be used by employment-target progress
and per-staff CP statistics. The global company total still counts one Joint CP
as one visit; only staff attribution credits both participants.

Every mutation that changes the parent CP to `completed` must invoke the same
projection updater with its before/after rows. This explicitly includes:

- `POST /api/marketing/clientPlaceVisits/setOutcome`
- `POST /api/marketing/clientPlaceVisits/convertToSiteVisit`
- `POST /api/bookings` when `sourceType=cp_visit`
- `POST /api/marketing/clientPlaceVisits/joint-complete-review`
- CP completion approval and the repair apply route

The currently available backend source patches the parent during CP-to-SV and
CP-to-booking conversion but does not consistently update CP stats rollups. That
leaves a correctly completed parent missing from counters even without a mobile
display problem.

## 7. Make Joint CP review completion atomic

### Existing route

`POST /api/marketing/clientPlaceVisits/joint-complete-review`

### Required request

```json
{
  "id": "jointCpVisitId",
  "expectedOutcomeRevision": 7,
  "reviewerRemark": "Outcome checked with the client"
}
```

### Required transaction

1. Authenticate the higher-level reviewer and verify the latest revision.
2. Require non-empty reviewer remarks.
3. Re-check both fresh participant locations and separation below 50 metres.
4. Set the parent CP to `completed` with one authoritative `completedAt`.
5. Set both participant rows to `completed` with that same `completedAt`.
6. Close both linked field visits, geo trips, and active tracking sessions.
7. Credit the completion to both participant staff IDs for that IST date.
8. Update parent/global and both per-staff projections in the same mutation.

```json
{
  "success": true,
  "visit": {
    "_id": "jointCpVisitId",
    "status": "completed",
    "outcome": "interested",
    "completedAt": 1788878700000
  },
  "workflow": {
    "state": "completed",
    "outcomeRevision": 7,
    "reviewerRemark": "Outcome checked with the client",
    "completedAt": 1788878700000
  },
  "creditedStaffIds": ["lowerStaffId", "higherStaffId"]
}
```

If any write fails, roll back every write. A retry with the same idempotency key
must return the same completion rather than incrementing counts twice.

## 8. Repair CPs already affected by partial completion

### Preview route

`POST /api/marketing/clientPlaceVisits/completion-repair/preview`

```json
{
  "fromDate": "2026-09-01",
  "toDate": "2026-09-08",
  "cpVisitIds": ["optional-specific-id"]
}
```

This route is read-only and admin-only. It must classify each row as
`repairable`, `already_consistent`, or `blocked`, with evidence and a reason.

A normal CP is repairable only when all are true:

- parent status is nonterminal;
- linked field visit is `completed` and has `completedAt`;
- outcome is populated;
- required photo exists;
- OTP is verified, except an explicitly stored `clientMet=false` flow.

A Joint CP is repairable only when the reviewer already completed the workflow:
the final review revision, non-empty reviewer remark, reviewer identity, and
review completion timestamp must exist. An `outcome_submitted` or
`pending_review` Joint CP must remain blocked and return to the reviewer; repair
must never invent approval.

Example preview response:

```json
{
  "success": true,
  "summary": {
    "repairable": 12,
    "alreadyConsistent": 40,
    "blocked": 3
  },
  "items": [
    {
      "cpVisitId": "cpVisitId",
      "classification": "repairable",
      "derivedCompletedAt": 1788878700000,
      "creditedStaffIds": ["staffA", "staffB"],
      "reason": "Field visit and final Joint CP review are complete"
    }
  ]
}
```

### Apply route

`POST /api/marketing/clientPlaceVisits/completion-repair/apply`

```json
{
  "repairToken": "signed-token-returned-by-preview",
  "cpVisitIds": ["cpVisitId"],
  "reason": "Repair mobile partial completions reported on 2026-09-08"
}
```

The apply route must revalidate every predicate inside the mutation, write an
audit row, and be idempotent. Set `completedAt` from the historical final proof:
Joint workflow `completedAt`/`reviewedAt` first, otherwise the linked field
visit's `completedAt`. Never use `Date.now()` for an old completion. Rebuild the
affected global/per-staff rollups and target projections without adding duplicate
counts.

## 9. Add completion consistency health read

### Required route

`GET /api/marketing/clientPlaceVisits/completion-health?fromDate=YYYY-MM-DD&toDate=YYYY-MM-DD`

Admin-only response:

```json
{
  "success": true,
  "counts": {
    "fieldCompletedParentNonterminal": 0,
    "parentCompletedParticipantNonterminal": 0,
    "completedWithoutCompletedAt": 0,
    "jointCompletedMissingStaffCredit": 0
  },
  "items": []
}
```

This is the post-deploy and post-repair gate. All four counts must be zero.

Also extend `GET /api/marketing/clientPlaceVisits/my` and detail responses so
`fieldVisit.completedAt`, parent `completedAt`, server-derived `effectiveStatus`,
and `activityDate` are always returned. Web and mobile must render the same
server-derived status while historical repair is running.

## Current safe production verification

The non-mutating unauthenticated route audit was rerun after updating this
handoff. Result: `104/112` passed.

Existing completion routes are reachable and protected with structured HTTP
`401` responses:

- `POST /api/marketing/clientPlaceVisits/setOutcome`
- `POST /api/marketing/clientPlaceVisits/convertToSiteVisit`
- `POST /api/bookings`
- `GET /api/marketing/clientPlaceVisits/joint-workflow`
- `POST /api/marketing/clientPlaceVisits/joint-arrival-preflight`
- `POST /api/marketing/clientPlaceVisits/joint-submit-review`
- `POST /api/marketing/clientPlaceVisits/joint-complete-review`
- `POST /api/geotrack/visit/complete`

The five routes introduced by this completion/count repair currently return
HTTP `404` and must be deployed before the flow can be certified:

- `POST /api/marketing/clientPlaceVisits/joint-participant-ready`
- `GET /api/marketing/clientPlaceVisits/completed-count`
- `GET /api/marketing/clientPlaceVisits/completion-health`
- `POST /api/marketing/clientPlaceVisits/completion-repair/preview`
- `POST /api/marketing/clientPlaceVisits/completion-repair/apply`

The remaining three failures are the earlier dashboard-auth, malformed booking
detail, and storage-file readback gaps described in sections 1-3.

## Acceptance checks after deployment

```powershell
node scripts/check-mobile-api.mjs cp-sv-contracts --timeout 60000
```

Expected non-mutating result after these routes are deployed: `112/112`
endpoint contracts pass.

Then use disposable staging fixtures to execute the authenticated success cases.
No endpoint-only unauthenticated probe can prove SMS delivery, uploaded-byte
integrity, transaction atomicity, or mobile/web data synchronization.
