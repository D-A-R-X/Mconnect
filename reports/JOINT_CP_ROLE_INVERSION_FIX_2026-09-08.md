# Joint CP Role Inversion Fix - 2026-09-08

## Reported incident

A Senior Manager and a BDO were assigned to one Joint CP. Both phones showed
waiting states, while the Senior Manager incorrectly received OTP and outcome
controls.

This is not the intended flow. The correct assignment is:

| Staff | Joint CP responsibility |
| --- | --- |
| Lower numeric designation/IAM level, such as BDO | Client OTP, arrival photo, outcome, send for review |
| Higher numeric designation/IAM level, such as Senior Manager | Proximity swipe, wait for outcome, review/edit, remarks, complete |

## Confirmed root cause

The available backend source explicitly promoted the senior participant into
`clientPlaceVisits.assignedStaffId` and described the senior as the OTP/outcome
owner. Existing OTP and outcome code uses that compatibility field, so this
reversed the required role assignment.

The Android create form also sent the person selected in the first field as
`assignedStaffId`, even though its validation had already calculated the lower
and higher levels correctly. Picker order could therefore leak into older
backend behavior.

## Mobile correction

For a Joint CP, Android now sends:

```json
{
  "assignedStaffId": "<lower-level-outcome-owner-id>",
  "jointStaffIds": [
    "<lower-level-outcome-owner-id>",
    "<higher-level-reviewer-id>"
  ]
}
```

The normal CP request is unchanged.

The trip screen now verifies the workflow response against the logged-in staff
ID and the explicit `outcomeOwnerStaffId` / `reviewerStaffId`. A contradictory
response disables OTP, outcome, review and completion controls and displays an
out-of-sync message. It no longer allows a Senior Manager to act as outcome
owner because of an inverted `actorRole` string.

## Backend correction

Joint CP creation must load both active staff and the admin-maintained numeric
designation/IAM levels in the same transaction.

- Lower level becomes `outcomeOwnerStaffId` and compatibility
  `assignedStaffId`.
- Higher level becomes `reviewerStaffId`.
- Equal levels return `400 SAME_TEMPLATE_LEVEL_NOT_ALLOWED`.
- Missing levels return `400 TEMPLATE_LEVEL_REQUIRED`.
- Participant order, free-text title matching and mobile-supplied role strings
  must never decide authority.

The local backend resolver and tests have been corrected to this rule. That
source change still requires backend review and deployment.

## Existing affected Joint CPs

Deploying the create fix prevents new inverted visits, but it does not by
itself repair rows already created with the Senior Manager as owner.

The backend rollout must include an audited, idempotent repair for nonterminal
Joint CPs:

1. Load exactly two participant rows and their current numeric levels.
2. Skip completed/cancelled visits and rows without exactly two valid levels.
3. Set parent `assignedStaffId` to the lower-level participant.
4. Set the lower-level leg as outcome owner/primary and the higher-level leg as
   reviewer.
5. Preserve both trips, locations, OTP evidence, photos, draft outcome,
   revisions and timestamps.
6. Recompute workflow state without deleting already captured evidence.
7. Record before/after IDs and reason in an audit row.
8. Re-running the repair must make no additional changes.

The read and mutation routes must derive authorization from this repaired
mapping for every request, not from a cached client role.

## Required existing endpoint responses

### Create

```http
POST /api/marketing/clientPlaceVisits/create
```

Success must retain the lower-level owner and both participants. Failure must
not create a parent CP, participant leg, field visit or task.

### Workflow read for the BDO

```http
GET /api/marketing/clientPlaceVisits/joint-workflow?id=<cpVisitId>
Authorization: Bearer <bdo-token>
```

```json
{
  "success": true,
  "workflow": {
    "actorRole": "outcome_owner",
    "outcomeOwnerStaffId": "<bdo-id>",
    "reviewerStaffId": "<senior-manager-id>",
    "canRequestOtp": true,
    "canSubmitOutcome": false,
    "canReview": false,
    "canCompleteReview": false
  }
}
```

### Workflow read for the Senior Manager

```http
GET /api/marketing/clientPlaceVisits/joint-workflow?id=<cpVisitId>
Authorization: Bearer <senior-manager-token>
```

```json
{
  "success": true,
  "workflow": {
    "actorRole": "reviewer",
    "outcomeOwnerStaffId": "<bdo-id>",
    "reviewerStaffId": "<senior-manager-id>",
    "canRequestOtp": false,
    "canSubmitOutcome": false,
    "canReview": false,
    "canCompleteReview": false,
    "actorReady": false
  }
}
```

The Senior Manager must receive `403 OTP_OWNER_ONLY` from both arrival OTP
request and verify routes. The BDO must receive `403 REVIEWER_ONLY` from review
completion.

## Endpoint deployment check

Safe unauthenticated production probes performed on 2026-09-08:

| Endpoint | Observed response | Meaning |
| --- | --- | --- |
| `POST /api/marketing/clientPlaceVisits/create` | HTTP 401 JSON | Deployed and protected |
| `GET /api/marketing/clientPlaceVisits/joint-workflow` | HTTP 401 JSON | Deployed and protected |
| `POST /api/marketing/clientPlaceVisits/joint-arrival-preflight` | HTTP 401 JSON | Deployed and protected |
| `POST /api/marketing/clientPlaceVisits/joint-participant-ready` | HTTP 404 | Missing; must be deployed |
| `POST /api/marketing/clientPlaceVisits/joint-submit-review` | HTTP 401 JSON | Deployed and protected |
| `POST /api/marketing/clientPlaceVisits/joint-complete-review` | HTTP 401 JSON | Deployed and protected |

These probes verify route presence/auth boundaries only. A disposable Joint CP
with separate BDO and Senior Manager tokens is still required to certify the
authenticated success sequence and historical repair.

## Release acceptance

1. Create the pair in both picker orders; BDO is owner both times.
2. BDO sees OTP/photo/outcome only.
3. Senior Manager never sees OTP and must complete the separate `< 50 m`
   proximity swipe.
4. Senior Manager waits until BDO submits the outcome.
5. Senior Manager can review/edit, must add remarks, and completes once.
6. Both staff receive the completed CP count on the actual completion date.
7. Web and both phones show the same terminal status.
8. Run the repair twice; the second run changes zero rows.
