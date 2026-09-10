# Joint CP Lower Owner / Higher Reviewer API Requirements

Date: 10 Sep 2026

## Authoritative rule

For every Joint CP pair:

1. The participant with the lower effective IAM template numeric level is the
   `outcome_owner` and handles arrival OTP, proof and outcome.
2. The participant with the higher effective IAM template numeric level is the
   `reviewer` and handles outcome review/edit, mandatory remarks and final
   completion.

The API must derive these roles. Mobile sends staff IDs only and must never be
trusted to send role, template or level authority.

Do not use staff order, names, designation names, hardcoded designations, or a
fixed `jointCpWorkflowRole` stored on a template. A fixed role cannot work when
one template can be lower in one pair and higher in another pair.

## Effective template resolution

Resolve each participant inside the create transaction:

1. Load the active staff row.
2. Use its staff-level `iamTemplateId` when configured.
3. Otherwise use the staff designation's linked template ID.
4. Load the referenced IAM template.
5. Require the template's numeric `level`.

Reject before creating any row when a staff member, template, or numeric level
is missing; both staff IDs are the same; both effective template IDs are the
same; or both numeric levels are equal.

## Existing endpoints to update

No new endpoint is required.

### Staff picker reads

```http
GET /api/hr/staff?status=active
GET /api/hr/staff/active
GET /api/hr/staff/search?query=<query>&lite=1
Authorization: Bearer <session-token>
```

Return effective metadata for display only:

```json
{
  "_id": "staff-low",
  "name": "Lower staff",
  "iamTemplateId": "template-3",
  "iamTemplateName": "Sales & Marketing - 3",
  "iamTemplateLevel": 3
}
```

### Joint CP creation

```http
POST /api/marketing/clientPlaceVisits/create
Authorization: Bearer <session-token>
Content-Type: application/json
```

Mobile sends `assignedStaffId` and exactly one different ID in
`jointStaffIds`. The API resolves and snapshots:

```json
{
  "outcomeOwnerStaffId": "staff-low",
  "outcomeOwnerTemplateId": "template-3",
  "outcomeOwnerTemplateLevel": 3,
  "reviewerStaffId": "staff-high",
  "reviewerTemplateId": "template-4",
  "reviewerTemplateLevel": 4
}
```

The snapshot must remain stable even if IAM assignments change after creation.

### Workflow read

```http
GET /api/marketing/clientPlaceVisits/joint-workflow?id=<cpVisitId>
Authorization: Bearer <session-token>
```

Compute `actorRole` from the authenticated bearer and the saved participant
snapshot. Return the role-specific actions and current revision:

```json
{
  "success": true,
  "workflow": {
    "state": "awaiting_owner_outcome",
    "actorRole": "outcome_owner",
    "outcomeOwnerStaffId": "staff-low",
    "reviewerStaffId": "staff-high",
    "outcomeRevision": 1,
    "canRequestOtp": true,
    "canSubmitOutcome": true,
    "canReview": false,
    "canCompleteReview": false
  }
}
```

Partner proximity, readiness, location age and GPS accuracy must not control
these action flags.

### Trip start

```http
POST /api/geotrack/visit/start
Authorization: Bearer <session-token>
```

Each participant starts their own field-visit leg. The API must bind the bearer
to that participant's saved leg and must not let one participant start the
other participant's leg.

### Owner OTP and outcome

```http
POST /api/geotrack/visit/arrival-otp/request
POST /api/geotrack/visit/arrival-otp/verify
POST /api/marketing/clientPlaceVisits/setOutcome
POST /api/marketing/clientPlaceVisits/joint-submit-review
Authorization: Bearer <session-token>
Idempotency-Key: <stable-request-id>
```

- OTP request and verify must allow only the saved `outcome_owner`.
- Outcome writes before review must allow only the owner and create/update a
  revisioned draft.
- Submit review must require the owner role, both trip legs started, verified
  OTP, required photo proof, a valid draft and matching revision.
- Submit review sets `pending_review` atomically and returns the refreshed
  workflow. It must not mark the parent CP complete.

### Reviewer remarks and completion

```http
POST /api/marketing/clientPlaceVisits/setOutcome
POST /api/marketing/clientPlaceVisits/joint-complete-review
Authorization: Bearer <session-token>
Idempotency-Key: <stable-request-id>
```

- Reviewer outcome edits are allowed only after `pending_review`/`reviewing`
  and increment `outcomeRevision`.
- Completion requires the saved reviewer, latest revision and non-empty
  `reviewerRemark`.
- One transaction marks the parent CP and both participant field visits
  complete and credits both staff exactly once.
- Return `state: "completed"`, both completed field-visit IDs and
  `creditedStaffIds` containing both participant IDs.

## Required errors

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

Every error must use `{ "success": false, "code": "...", "error": "..." }`.
A rejected create or completion must leave no partial rows or credits.

## Acceptance tests

1. Template levels 3 and 4 assign level 3 as owner and level 4 as reviewer,
   regardless of picker order.
2. Template levels 2 and 3 assign level 2 as owner and level 3 as reviewer;
   this proves roles are dynamic rather than fixed on template 3.
3. Same template and equal-level templates are rejected with no partial rows.
4. Reviewer cannot request/verify OTP or submit the owner's initial outcome.
5. Owner cannot perform final review completion.
6. After owner submission, reviewer can edit, add remarks and complete.
7. Final completion closes and credits both participants exactly once.
8. No endpoint blocks because the two participants are far apart or lack a
   partner-readiness sample.
