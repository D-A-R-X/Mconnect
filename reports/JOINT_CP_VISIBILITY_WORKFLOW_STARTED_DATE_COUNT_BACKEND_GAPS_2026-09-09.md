# Joint CP Visibility, Workflow, and Started-Date Count Contract

Date: 2026-09-09

This is a new contract. It supersedes the completed-count date rule in
`CP_COMPLETED_COUNT_ASSIGNED_DATE_CONTRACT_2026-09-08.md`. Do not edit or delete
the older report because it remains part of the change history.

## Required behavior

1. Both assigned Joint CP staff must see the same parent CP in their own mobile
   list and search results.
2. Each participant must receive and mutate only their own field-visit leg.
3. The lower-designation participant is the outcome owner and handles arrival
   OTP, arrival photo, and outcome submission.
4. The higher-designation participant is the reviewer and can correct the
   submitted outcome, add remarks, and complete the shared CP.
5. Equal designation levels must be rejected at creation with a readable
   validation error.
6. Both participants must provide fresh locations and be within 50 metres for
   the Joint CP completion workflow.
7. A completed CP is counted on the date each credited staff member actually
   started their own participant leg, using the `Asia/Kolkata` calendar date.
8. A cancelled CP must remain cancelled on every mobile read and must never be
   returned as startable, even when an old field-visit row or device cache says
   it was started.

## Existing endpoints to update

No new staff-facing endpoint is required. The following existing routes must
implement one authoritative Joint CP model:

```http
GET  /api/marketing/clientPlaceVisits/my
GET  /api/marketing/clientPlaceVisits/get?id={clientPlaceVisitId}
GET  /api/marketing/clientPlaceVisits/joint-workflow?id={clientPlaceVisitId}
GET  /api/geotrack/today-visits?date=YYYY-MM-DD
POST /api/geotrack/visit/start
POST /api/marketing/clientPlaceVisits/joint-arrival-preflight
POST /api/marketing/clientPlaceVisits/joint-participant-ready
POST /api/geotrack/visit/arrival-otp/request
POST /api/geotrack/visit/arrival-otp/verify
POST /api/marketing/clientPlaceVisits/joint-submit-review
POST /api/marketing/clientPlaceVisits/joint-complete-review
GET  /api/marketing/clientPlaceVisits/completed-count?date=YYYY-MM-DD&staffId={staffId}
Authorization: Bearer <session-token>
```

## My-list and search contract

`GET /api/marketing/clientPlaceVisits/my?scope=mine` must include the parent CP
when the authenticated staff appears in either the parent assignment or a
participant row. Server-side search and pagination must apply after this
membership rule, so a partner is not hidden merely because they are not the
legacy parent `assignedStaffId`.

Required response shape:

```json
{
  "success": true,
  "visits": [
    {
      "id": "cp-id",
      "status": "in_progress",
      "effectiveStatus": "in_progress",
      "joint": {
        "leadStaffId": "lower-staff-id",
        "leadStaffName": "Lower Staff",
        "companionStaffIds": ["higher-staff-id"],
        "companionNames": ["Higher Staff"],
        "participants": [
          {
            "staffId": "lower-staff-id",
            "staffName": "Lower Staff",
            "workflowRole": "outcome_owner",
            "fieldVisitId": "lower-field-visit-id",
            "status": "in_progress",
            "startedAt": 1788912300000
          },
          {
            "staffId": "higher-staff-id",
            "staffName": "Higher Staff",
            "workflowRole": "reviewer",
            "fieldVisitId": "higher-field-visit-id",
            "status": "in_progress",
            "startedAt": 1788999000000
          }
        ]
      }
    }
  ]
}
```

For a cancelled parent, both `status` and `effectiveStatus` must be
`"cancelled"`. A non-terminal participant/field-visit status must never
override a terminal parent status.

## Workflow response

`GET /joint-workflow` must return explicit role IDs and actor permissions. The
mobile app will use participant metadata only as a legacy fallback.

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
    "canRequestOtp": false,
    "canSubmitOutcome": false,
    "canReview": true,
    "canCompleteReview": true,
    "outcomeRevision": 1
  },
  "visit": {
    "id": "cp-id",
    "joint": {
      "participants": []
    }
  }
}
```

Do not return a database/Convex identifier such as `k2g...` as the public
`error`. Return a stable `code` plus readable `error`, for example:

```json
{
  "success": false,
  "code": "JOINT_PARTICIPANT_RECORD_MISSING",
  "error": "Your Joint CP assignment is incomplete. Ask an administrator to repair this visit."
}
```

## Started-date completed count

The requested `date` in `GET /completed-count` is the credited staff member's
participant-leg start date, not the CP creation date, scheduled date, or final
completion date.

```json
{
  "success": true,
  "date": "2026-09-09",
  "staffId": "lower-staff-id",
  "completedCount": 1,
  "visitIds": ["cp-id"]
}
```

Counting rules:

- A normal CP uses its authenticated field visit's `startedAt`.
- A Joint CP uses each participant row/participant field visit's own
  `startedAt`; two participants may therefore receive the same CP credit on
  different dates.
- Credit is created only after authoritative shared completion, not after the
  outcome owner submits for review.
- Credit each staff member once per parent CP. All retries are idempotent.
- Convert the timestamp to `Asia/Kolkata` before deriving `YYYY-MM-DD`.
- For a legacy completed record with no trustworthy start timestamp, use the
  final `scheduledDate` as a compatibility fallback and return
  `dateSource: "scheduled_legacy_fallback"` in repair/audit output.
- Never rewrite OTP, photo, outcome, reviewer changes, remarks, proximity,
  GPS, start, completion, or audit evidence to satisfy a count.

## Admin-only repair requirement

The currently reported production records require a safe repair because a
mobile release cannot recreate a participant row that is absent from the
database. Provide separate authenticated admin-only preview and apply actions
using the project's existing repair route pattern.

The repair must detect and, only when evidence is unambiguous, fix:

- a parent Joint CP whose second participant row is missing;
- participant rows not linked to their own field visits;
- role inversion or missing explicit outcome-owner/reviewer IDs;
- a terminal parent whose child leg remains startable;
- a fully evidenced completed workflow whose parent status/count projection is
  stale;
- missing or duplicate completion credits;
- completion credits grouped by creation, assignment, or completion date
  instead of participant start date.

Preview/apply must be idempotent and return `scanned`, `repairable`,
`repaired`, `skipped`, and `conflicts`, with per-record reasons. A second
preview after apply must report zero repairable records.

Do not automatically convert the screenshot's cancelled CP to completed. Its
web record shows `cancelled` with no arrival proof, so it may only be restored
when OTP/photo/outcome/reviewer/audit evidence proves that cancellation was a
stale projection rather than the real terminal action.

## Acceptance test

Use two disposable staff accounts with different designation levels:

1. Create one Joint CP and confirm it appears in both accounts and search.
2. Start from both accounts and verify distinct participant field-visit IDs,
   owner IDs, `startedAt` values, trips, and tracking sessions.
3. Confirm the reviewer cannot request OTP or submit the owner outcome.
4. Confirm the outcome owner receives OTP/photo/outcome controls.
5. Verify distance over 50 metres blocks progression for both actors.
6. Within 50 metres, submit outcome; confirm reviewer receives the same values,
   can edit them, adds remarks, and completes.
7. Confirm parent status, both participant legs, trips, and tracking sessions
   are completed and visible on web/mobile.
8. Query completed count on each participant's start date and confirm one
   credit each. Retry every mutation and confirm no duplicate count.
9. Cancel a separate Joint CP and confirm neither account sees Start/Outcome.
