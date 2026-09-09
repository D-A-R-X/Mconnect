# Joint CP readiness deadlock backend fix

Date: 09 Sep 2026

## Problem

A valid Joint CP between Sales & Marketing level 4 and level 3 staff is assigning the correct roles, but both users can become blocked:

- Lower-level staff is correctly shown as the OTP and outcome owner.
- Higher-level staff is correctly shown as the remarks/review/completion owner.
- Higher-level staff receives a workflow where `actorReady` is absent/null, so older mobile builds show only "Waiting for partner" and never offer the proximity action.
- Lower-level staff receives stale `canRequestOtp: false`, so older mobile builds do not start arrival preflight.

This is a workflow-readiness response problem. It is not an equal-designation problem and must not be fixed by swapping participant order.

## No new endpoint required

Correct the deployed behavior of these existing endpoints:

1. `GET /api/marketing/clientPlaceVisits/joint-workflow?id={cpVisitId}`
2. `POST /api/marketing/clientPlaceVisits/joint-participant-ready`
3. `POST /api/marketing/clientPlaceVisits/joint-arrival-preflight`

All requests require:

```http
Authorization: Bearer <AIRIX_SESSION_TOKEN>
Content-Type: application/json
```

## Authoritative role rule

Resolve both participants through the IAM designation template saved by admin:

- Different numeric template levels are required.
- The lower designation level in the company hierarchy owns arrival OTP, arrival photo and outcome submission.
- The higher designation level owns review, optional outcome corrections, remarks and final completion.
- Role assignment must use staff IDs and template levels, never participant array order, display names or lexical designation-name comparison.
- Equal or missing template levels must return a structured validation error during creation instead of creating an ambiguous Joint CP.

For the reported visit, every workflow response must consistently identify:

```json
{
  "outcomeOwnerStaffId": "<PRAVEEN_STAFF_ID>",
  "outcomeOwnerName": "PRAVEEN NATH.P",
  "reviewerStaffId": "<VIGNESH_STAFF_ID>",
  "reviewerName": "VIGNESH.P"
}
```

## Required workflow response

`GET /joint-workflow` must return explicit booleans. Do not omit `actorReady`, `canRequestOtp`, `canSubmitOutcome`, `canReview`, or `canCompleteReview`.

Before the reviewer confirms proximity:

```json
{
  "success": true,
  "workflow": {
    "state": "in_progress",
    "actorRole": "reviewer",
    "actorReady": false,
    "canRequestOtp": false,
    "canSubmitOutcome": false,
    "canReview": false,
    "canCompleteReview": false,
    "outcomeOwnerStaffId": "<LOWER_LEVEL_STAFF_ID>",
    "reviewerStaffId": "<HIGHER_LEVEL_STAFF_ID>",
    "requiredRadiusMeters": 100
  }
}
```

After successful reviewer readiness:

```json
{
  "success": true,
  "workflow": {
    "state": "in_progress",
    "actorRole": "reviewer",
    "actorReady": true,
    "isWithinCompletionRadius": true,
    "canReview": false,
    "canCompleteReview": false,
    "outcomeOwnerStaffId": "<LOWER_LEVEL_STAFF_ID>",
    "reviewerStaffId": "<HIGHER_LEVEL_STAFF_ID>",
    "requiredRadiusMeters": 100
  }
}
```

The lower-level owner's next workflow/preflight response must then contain:

```json
{
  "success": true,
  "workflow": {
    "state": "in_progress",
    "actorRole": "outcome_owner",
    "actorReady": true,
    "isWithinCompletionRadius": true,
    "canRequestOtp": true,
    "canSubmitOutcome": false,
    "canReview": false,
    "canCompleteReview": false,
    "outcomeOwnerStaffId": "<LOWER_LEVEL_STAFF_ID>",
    "reviewerStaffId": "<HIGHER_LEVEL_STAFF_ID>",
    "requiredRadiusMeters": 100
  }
}
```

## Mutation bodies

Reviewer readiness:

```json
{
  "id": "<CP_VISIT_ID>",
  "fieldVisitId": "<REVIEWER_FIELD_VISIT_ID>",
  "lat": 13.000001,
  "lng": 80.000001,
  "accuracyMeters": 12,
  "capturedAt": 1788942600000
}
```

Owner arrival preflight uses the same body shape with the owner's own `fieldVisitId` and fresh location.

## Transaction and refresh requirements

- `joint-participant-ready` must persist the reviewer readiness and fresh location before returning success.
- The success response must be built from the committed state, not from a pre-update snapshot.
- Once both fresh locations are accurate and within 100 metres, the owner's `canRequestOtp` must become `true` immediately.
- Reads must not require waiting for an eventually consistent cache. Invalidate or bypass any cached Joint CP workflow after either readiness write.
- Both mutations must be idempotent so a mobile retry cannot create duplicate readiness records or corrupt state.
- A location outside 100 metres, stale location or poor accuracy must return a stable `code` and readable `error`; never return an opaque database ID as the error message.

## Acceptance checks

1. Create a disposable Joint CP with level 4 and level 3 staff.
2. Confirm the lower-level bearer always gets `actorRole: "outcome_owner"`.
3. Confirm the higher-level bearer always gets `actorRole: "reviewer"` and explicit `actorReady: false` initially.
4. Start both participant field visits.
5. Submit reviewer readiness within 100 metres and confirm the response has `actorReady: true`.
6. Immediately call owner preflight and confirm `canRequestOtp: true` without waiting for polling.
7. Request and verify OTP only as the lower-level staff, upload/link the photo and submit the outcome.
8. Confirm only the higher-level staff receives `canReview: true`, can add remarks/edit the outcome and complete.
9. Confirm final state is `completed` and both staff IDs are credited.
10. Repeat readiness and completion requests with the same idempotency key and confirm no duplicate writes or count increments.

## Rollout note

Returning explicit `actorReady: false/true` and promptly refreshing `canRequestOtp` is the immediate compatibility fix for already-installed Android/iOS builds. The accompanying mobile change also avoids trusting stale `canRequestOtp`, but current field users should not have to wait for a store release to escape this deadlock.
