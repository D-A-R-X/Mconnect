# Joint CP Required Backend Endpoints - Final

Date: 10 Sep 2026
Base URL: `https://api-mfpl.theairix.com`

## Final workflow rule

- Lower effective IAM template numeric level: `outcome_owner`.
- Higher effective IAM template numeric level: `reviewer`.
- Owner performs trip start, arrival OTP/proof, outcome and submit-for-review.
- Reviewer starts their own trip, reviews/edits the outcome, enters mandatory
  remarks and completes the Joint CP.
- Final completion closes and credits both participant legs exactly once.
- Picker order, staff/designation names, fixed template roles and participant
  proximity do not determine authority.

Mobile sends staff IDs. Every role and permission check below is resolved again
from the authenticated bearer and the saved server-side participant snapshot.

## 1. Staff picker

```http
GET /api/hr/staff?status=active
GET /api/hr/staff/active
GET /api/hr/staff/search?query=<query>&lite=1
Authorization: Bearer <session-token>
```

Each row must contain effective template metadata resolved from staff-level
`iamTemplateId`, otherwise the designation-linked template:

```json
{
  "_id": "staff-id",
  "name": "Staff Name",
  "status": "active",
  "designation": "Sales Staff",
  "department": "Sales & Marketing",
  "iamTemplateId": "template-id",
  "iamTemplateName": "Sales & Marketing - 3",
  "iamTemplateLevel": 3
}
```

Do not use designation numeric level as authority. The linked IAM template row
must contain a finite numeric `level`. Picker data is UX metadata only.

## 2. Create Joint CP

```http
POST /api/marketing/clientPlaceVisits/create
Authorization: Bearer <session-token>
Idempotency-Key: <stable-request-id>
Content-Type: application/json
```

```json
{
  "mobileNumber": "9876543210",
  "clientName": "Client Name",
  "projectId": "project-id",
  "assignedStaffId": "staff-a",
  "jointStaffIds": ["staff-b"],
  "cpType": "joint_cp",
  "jointCpCategory": "booking_cp",
  "scheduledDate": "2026-09-10",
  "scheduledTime": "11:30",
  "visitAddress": "Chennai, Tamil Nadu 600017"
}
```

Before inserting anything, the API must:

1. authenticate and authorize the creator;
2. require exactly two different active staff IDs;
3. resolve both effective IAM templates and their numeric levels;
4. reject the same template ID or equal levels;
5. assign the lower level as owner and higher level as reviewer;
6. set parent `assignedStaffId` to the resolved owner;
7. create one participant and one field-visit leg per staff; and
8. snapshot both template IDs, names, levels and workflow roles.

```json
{
  "success": true,
  "id": "cp-visit-id",
  "requestId": "stable-request-id",
  "alreadyCreated": false,
  "outcomeOwnerStaffId": "staff-low",
  "reviewerStaffId": "staff-high",
  "participantFieldVisitIds": {
    "staff-low": "field-owner",
    "staff-high": "field-reviewer"
  }
}
```

An idempotent replay returns the same IDs with `alreadyCreated: true`.

## 3. Read Joint CP workflow

```http
GET /api/marketing/clientPlaceVisits/joint-workflow?id=<cpVisitId>
Authorization: Bearer <session-token>
```

```json
{
  "success": true,
  "workflow": {
    "state": "awaiting_owner_outcome",
    "actorRole": "outcome_owner",
    "outcomeOwnerStaffId": "staff-low",
    "outcomeOwnerName": "Lower Staff",
    "outcomeOwnerTemplateName": "Sales & Marketing - 3",
    "outcomeOwnerTemplateLevel": 3,
    "reviewerStaffId": "staff-high",
    "reviewerName": "Higher Staff",
    "reviewerTemplateName": "Sales & Marketing - 4",
    "reviewerTemplateLevel": 4,
    "outcomeRevision": 1,
    "canRequestOtp": true,
    "canSubmitOutcome": true,
    "canReview": false,
    "canCompleteReview": false
  }
}
```

`actorRole` and action flags are bearer-specific. They depend only on the saved
role and workflow state, never partner location/readiness.

## 4. Start each participant leg

```http
POST /api/geotrack/visit/start
Authorization: Bearer <session-token>
Content-Type: application/json
```

```json
{
  "visitId": "participant-field-visit-id",
  "lat": 13.0827,
  "lng": 80.2707
}
```

The bearer may start only their own saved participant leg. Return the updated
leg status and start timestamp. Repeating an already-started request must return
the existing started state instead of creating another leg.

## 5. Owner arrival OTP request

```http
POST /api/geotrack/visit/arrival-otp/request
Authorization: Bearer <session-token>
Content-Type: application/json
```

```json
{
  "visitId": "field-owner",
  "lat": 13.0827,
  "lng": 80.2707
}
```

Only the saved owner may request the OTP. The server validates the owner's leg
and client arrival rules; it does not compare the owner's location with the
reviewer.

```json
{
  "success": true,
  "contactPhoneMasked": "98****3210",
  "otpRequestedAt": 1789020000000
}
```

## 6. Owner arrival OTP verify

```http
POST /api/geotrack/visit/arrival-otp/verify
Authorization: Bearer <session-token>
Content-Type: application/json
```

```json
{
  "visitId": "field-owner",
  "otp": "1234",
  "lat": 13.0827,
  "lng": 80.2707,
  "arrivalPhotoStorageId": "storage-id"
}
```

Only the saved owner may verify. Atomically link proof and return the accepted
arrival state. A replay after successful verification must return success or an
explicit `alreadyVerified: true` response, not restart the flow.

## 7. Save outcome draft

```http
POST /api/marketing/clientPlaceVisits/setOutcome
Authorization: Bearer <session-token>
Idempotency-Key: <stable-request-id>
Content-Type: application/json
```

The existing outcome-specific request body remains supported. For Joint CP:

- owner may create/edit the initial draft before review;
- reviewer may edit only during `pending_review`/`reviewing`;
- every accepted edit increments `outcomeRevision`;
- the endpoint must not complete the parent CP; and
- the response should include the updated workflow to avoid another blocking
  workflow GET from mobile.

```json
{
  "success": true,
  "outcome": "follow_up",
  "workflow": {
    "state": "awaiting_owner_outcome",
    "actorRole": "outcome_owner",
    "outcomeRevision": 2,
    "canSubmitOutcome": true
  }
}
```

During rollout, accept requests without `Idempotency-Key` for compatibility,
but return/support a stable key so Android and iOS can adopt it safely.

## 8. Owner submits for review

```http
POST /api/marketing/clientPlaceVisits/joint-submit-review
Authorization: Bearer <session-token>
Idempotency-Key: <stable-request-id>
Content-Type: application/json
```

```json
{
  "id": "cp-visit-id",
  "fieldVisitId": "field-owner",
  "expectedOutcomeRevision": 2,
  "lat": 13.0827,
  "lng": 80.2707,
  "arrivalPhotoStorageId": "storage-id"
}
```

`lat`, `lng`, `accuracyMeters` and `capturedAt` must be optional compatibility
evidence. Missing values must not block submission now that partner proximity
has been removed.

Validate owner role, both legs started, owner OTP/proof, draft existence and
revision. Atomically set `pending_review`, notify/invalidate reviewer reads and
return the refreshed workflow. Do not complete the parent yet.

## 9. Reviewer edits, remarks and completes

Reviewer edits use endpoint 7 while in `pending_review`/`reviewing`.

```http
POST /api/marketing/clientPlaceVisits/joint-complete-review
Authorization: Bearer <session-token>
Idempotency-Key: <stable-request-id>
Content-Type: application/json
```

```json
{
  "id": "cp-visit-id",
  "expectedOutcomeRevision": 3,
  "reviewerRemark": "Reviewed and approved"
}
```

Validate reviewer role, review state, latest revision and non-empty remarks.
One transaction must complete the parent and both legs and credit both staff
exactly once.

```json
{
  "success": true,
  "alreadyCompleted": false,
  "creditedStaffIds": ["staff-low", "staff-high"],
  "completedFieldVisitIds": ["field-owner", "field-reviewer"],
  "workflow": {
    "state": "completed",
    "actorRole": "reviewer",
    "outcomeRevision": 3,
    "reviewerRemark": "Reviewed and approved",
    "creditedStaffIds": ["staff-low", "staff-high"]
  }
}
```

Idempotent replay returns the same completion with `alreadyCompleted: true`.

## 10. Completed count and detail synchronization

```http
GET /api/marketing/clientPlaceVisits/get?id=<cpVisitId>
GET /api/marketing/clientPlaceVisits/completed-count?date=<startedDate>&staffId=<staffId>
Authorization: Bearer <session-token>
```

Detail must expose terminal parent/leg state, proof, outcome, reviewer remark and
both credits. Completed count must include the Joint CP once for each credited
participant under the date their own trip leg was started.

## Structured errors

Every rejection must return:

```json
{
  "success": false,
  "code": "SAME_TEMPLATE_NOT_ALLOWED",
  "error": "Use two different IAM templates"
}
```

Required codes:

```text
STAFF_PAIR_REQUIRED
STAFF_NOT_ACTIVE
TEMPLATE_REQUIRED
TEMPLATE_LEVEL_REQUIRED
SAME_TEMPLATE_NOT_ALLOWED
SAME_TEMPLATE_LEVEL_NOT_ALLOWED
OTP_OWNER_ONLY
OUTCOME_OWNER_ONLY
REVIEWER_ONLY
OUTCOME_NOT_READY
OUTCOME_REVISION_CONFLICT
REVIEW_REMARK_REQUIRED
```

Validation failure must occur before writes or roll back the entire mutation.

## Compatibility endpoints

These existing routes may remain for older clients, but must not be required by
the current Joint CP workflow and must never return a proximity rejection:

```http
POST /api/marketing/clientPlaceVisits/joint-arrival-preflight
POST /api/marketing/clientPlaceVisits/joint-participant-ready
```

Do not emit `PARTNER_LOCATION_STALE`, `LOCATION_ACCURACY_LOW`, or
`PARTNER_TOO_FAR` from any Joint CP workflow endpoint.

## Transport note from the reported screenshot

`Unable to resolve host api-mfpl.theairix.com` is an Android/device DNS failure;
the request did not reach an endpoint and therefore has no API response body.
At verification time the hostname resolved to `172.67.219.82` and
`104.21.94.42`, and HTTPS returned the expected authenticated `401` for a
synthetic invalid bearer. Keep the hostname, TLS certificate and DNS records
continuously available; mobile should retain the current action and allow retry
after transient DNS/network failure.
