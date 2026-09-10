# Joint CP Proximity Removal - Backend Endpoint Update

Date: 2026-09-10

## Required behavior

Joint CP must no longer require the two assigned staff members to be near each
other. Partner distance, GPS accuracy, missing partner location, and location
age must never block OTP, outcome submission, review, remarks, or final
completion.

The designation workflow remains unchanged:

- Lower-level participant: arrival OTP, photo, and outcome.
- Higher-level participant: review/edit outcome, enter remarks, and complete.
- Final completion atomically marks the parent CP and both participant field
  visits completed and credits both staff.

No new endpoint is required. Update the existing endpoint behavior below.

## 1. Read workflow

```http
GET /api/marketing/clientPlaceVisits/joint-workflow?id=<CP_VISIT_ID>
Authorization: Bearer <SESSION_TOKEN>
```

`canRequestOtp`, `canSubmitOutcome`, `canReview`, and `canCompleteReview` must be
derived only from authenticated role and workflow state. They must not depend
on `actorReady`, partner readiness, distance, GPS accuracy, or location age.

Example reviewer response after the owner submits an outcome:

```json
{
  "success": true,
  "workflow": {
    "state": "pending_review",
    "actorRole": "reviewer",
    "canRequestOtp": false,
    "canSubmitOutcome": false,
    "canReview": true,
    "canCompleteReview": true,
    "outcomeRevision": 3
  }
}
```

## 2. Arrival OTP

```http
POST /api/geotrack/visit/arrival-otp/request
POST /api/geotrack/visit/arrival-otp/verify
Authorization: Bearer <SESSION_TOKEN>
```

For a Joint CP, allow the authenticated outcome owner to request and verify the
OTP without requiring a readiness record from the reviewer. The staff member's
own arrival location may still be stored as visit evidence, but it must not be
compared with the partner's location.

## 3. Submit outcome for review

```http
POST /api/marketing/clientPlaceVisits/joint-submit-review
Authorization: Bearer <SESSION_TOKEN>
Idempotency-Key: <STABLE_REQUEST_ID>
```

Accept the outcome owner's submission based on role, OTP/proof requirements,
field visit ownership, and expected revision. Do not reject for partner
location, readiness, accuracy, age, or separation.

Successful response:

```json
{
  "success": true,
  "workflow": {
    "state": "pending_review",
    "actorRole": "outcome_owner",
    "outcomeRevision": 3
  }
}
```

## 4. Complete reviewer action

```http
POST /api/marketing/clientPlaceVisits/joint-complete-review
Authorization: Bearer <SESSION_TOKEN>
Idempotency-Key: <STABLE_REQUEST_ID>
Content-Type: application/json

{
  "id": "<CP_VISIT_ID>",
  "expectedOutcomeRevision": 3,
  "reviewerRemark": "<REQUIRED_REMARK>"
}
```

Validate only the authenticated reviewer role, pending outcome revision,
required remark, and idempotency contract. Do not read or validate readiness or
proximity fields before completing.

Successful response:

```json
{
  "success": true,
  "alreadyCompleted": false,
  "creditedStaffIds": ["<OUTCOME_OWNER_ID>", "<REVIEWER_ID>"],
  "workflow": {
    "state": "completed",
    "outcomeRevision": 3,
    "reviewerRemark": "<REQUIRED_REMARK>",
    "creditedStaffIds": ["<OUTCOME_OWNER_ID>", "<REVIEWER_ID>"]
  }
}
```

## 5. Compatibility endpoints

Updated mobile clients no longer call these endpoints:

```http
POST /api/marketing/clientPlaceVisits/joint-arrival-preflight
POST /api/marketing/clientPlaceVisits/joint-participant-ready
```

Keep them temporarily for older app versions, but make them idempotent and do
not return `PARTNER_LOCATION_STALE`, `LOCATION_ACCURACY_LOW`, or
`PARTNER_TOO_FAR`. They should return the current workflow without creating a
completion dependency.

## Acceptance checks

1. Put the two Joint CP phones more than 100 metres apart.
2. Lower-level participant requests/verifies OTP and submits the outcome.
3. Higher-level participant receives the outcome, edits if needed, adds a
   remark, and completes.
4. Confirm the parent CP and both participant field visits are completed.
5. Confirm both staff receive one completed CP credit.
6. Repeat the final request with the same idempotency key and confirm no
   duplicate completion or count.
7. Confirm none of the three legacy proximity error codes is returned anywhere
   in this workflow.
