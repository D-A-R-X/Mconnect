# Mconnect Mobile - Joint CP Template Review Workflow

Status: Backend handoff required by the Android and iOS clients. No website
source was changed by this mobile implementation.

## 1. Rules

1. Joint CP creation is available to a staff member whose effective IAM allows
   CP creation, including a BDO.
2. Authority comes only from the admin-assigned IAM template. Never infer it
   from free-text `designation`, department, staff name, participant order, or
   a hardcoded GM/SM/BDO string.
3. The two participants must have different numeric `iamTemplateLevel` values.
   Template IDs may be the same or different; the level is the authority.
4. The lower numeric IAM level is the `outcome_owner`; the higher numeric IAM
   level is the `reviewer`. Derive these roles server-side and do not require a
   preconfigured `jointCpWorkflowRole` string.
5. The outcome owner alone requests and verifies client OTP, uploads arrival
   proof, fills the outcome, and sends it for review.
6. The reviewer sees `Waiting for {BDO name} outcome` until submission. Once
   submitted, the reviewer may preview and edit the full outcome, then complete.
7. Both participants must have fresh, accurate locations and be strictly less
   than 50 metres apart when the outcome owner enters the completion flow and
   again when submitting for review. Exactly 50 metres is blocked.
8. Sending for review completes both participant trip legs atomically but leaves
   the CP in `pending_review`. Reviewer completion makes the CP terminal.
9. Completed detail returns both independently recorded paths. Recommended
   stable colors are owner `#1565C0` (blue) and reviewer `#EF6C00` (orange).

## 2. Staff directory fields

Extend every staff object returned by `GET /api/hr/staff` and equivalent iOS
staff-list endpoints:

```json
{
  "_id": "staff_123",
  "name": "Person name",
  "iamTemplateId": "template_sales_bdo",
  "iamTemplateName": "Sales & Marketing - BDO",
  "iamTemplateLevel": 40,
  "jointCpWorkflowRole": "outcome_owner"
}
```

`jointCpWorkflowRole` may be returned as derived display metadata, but it must
not be required on the admin template. Template level and workflow role must be
resolved from effective server-side IAM, never accepted from mobile.

## 3. Create validation

Existing route:

```http
POST /api/marketing/clientPlaceVisits/create
Authorization: Bearer <token>
Idempotency-Key: <uuid>
Content-Type: application/json
```

Existing Joint CP body remains compatible:

```json
{
  "cpType": "joint_cp",
  "jointCpCategory": "old_client",
  "assignedStaffId": "staff_bdo",
  "jointStaffIds": ["staff_reviewer"]
}
```

The server must load both active staff and their effective templates in the
same transaction. Reject before creating any CP, field visit, or task when:

- either effective template level is missing or both levels are equal;
- the requester lacks CP-create IAM; or
- either staff is inactive/inaccessible.

Return `400 TEMPLATE_LEVEL_REQUIRED` or
`400 SAME_TEMPLATE_LEVEL_NOT_ALLOWED` with a human-readable `error`. Equal
levels must be rejected even when template IDs or designation labels differ.

## 4. Read workflow

```http
GET /api/marketing/clientPlaceVisits/joint-workflow?id=<cpVisitId>
Authorization: Bearer <token>
```

Only a participant or IAM-authorized administrator may read it.

```json
{
  "success": true,
  "workflow": {
    "state": "awaiting_owner_arrival",
    "actorRole": "outcome_owner",
    "outcomeOwnerStaffId": "staff_bdo",
    "outcomeOwnerName": "BDO name",
    "reviewerStaffId": "staff_gm",
    "reviewerName": "Reviewer name",
    "reviewerTemplateName": "Sales & Marketing - GM",
    "canRequestOtp": true,
    "canSubmitOutcome": false,
    "canReview": false,
    "canCompleteReview": false,
    "separationMeters": 32.4,
    "isWithinCompletionRadius": true,
    "requiredRadiusMeters": 50,
    "outcomeRevision": 3,
    "outcome": null,
    "outcomeSummary": null,
    "reviewedByName": null,
    "reviewedByTemplateName": null,
    "completedAt": null
  }
}
```

`actorRole` and every `can*` field are computed for the bearer. They are never
stored or supplied by mobile. The endpoint must also be included as
`visit.joint.workflow` in CP detail/list responses where practical.

Canonical states:

- `awaiting_both_trips`
- `awaiting_owner_arrival`
- `awaiting_owner_outcome`
- `pending_review`
- `completed`
- `cancelled`

## 5. Arrival and distance preflight

```http
POST /api/marketing/clientPlaceVisits/joint-arrival-preflight
Authorization: Bearer <token>
Content-Type: application/json

{
  "id": "cp_123",
  "fieldVisitId": "field_visit_bdo",
  "lat": 13.0831,
  "lng": 80.1754,
  "accuracyMeters": 12.5,
  "capturedAt": 1788503400000
}
```

Required behavior:

- require bearer to be the outcome owner;
- bind `fieldVisitId` to that participant server-side;
- save the supplied point to that participant's path using normal GeoTrack
  validation;
- use the latest accepted point for each participant;
- require both points to be no older than 60 seconds and accuracy no worse than
  30 metres;
- calculate distance server-side with Haversine/geodesic distance;
- allow only when distance is `< 50.0` metres; and
- return the complete workflow response.

Use `409 PARTNER_LOCATION_STALE`, `409 LOCATION_ACCURACY_LOW`,
`409 PARTNER_TOO_FAR`, or `403 OTP_OWNER_ONLY`. A failed preflight must not send
an OTP or mutate workflow state.

The existing arrival OTP request and verify routes must also enforce
`outcome_owner` for a Joint CP. A reviewer request returns `403 OTP_OWNER_ONLY`.

## 6. Outcome draft behavior

Existing CP outcome routes remain the form-specific persistence surface. For a
Joint CP in a nonterminal state, calls by the outcome owner or reviewer must
write a revisioned draft rather than terminally closing the CP. This includes
booking/SV conversion payloads: dependent rows remain draft/pending and are not
published as completed business records until reviewer completion.

Each successful edit increments `outcomeRevision` and the detail/workflow read
must return enough normalized data to repopulate every mobile form field. Return
`outcome`, `outcomeSummary`, and structured `outcomeDraft`; summary alone is for
display and is not sufficient for editing.

## 7. Send review and finish both trips

```http
POST /api/marketing/clientPlaceVisits/joint-submit-review
Authorization: Bearer <token>
Idempotency-Key: <uuid>
Content-Type: application/json

{
  "id": "cp_123",
  "fieldVisitId": "field_visit_bdo",
  "lat": 13.0831,
  "lng": 80.1754,
  "arrivalPhotoStorageId": "storage_123",
  "expectedOutcomeRevision": 3
}
```

In one transaction:

1. Revalidate bearer is outcome owner, OTP verified, proof linked, and an
   outcome draft exists.
2. Revalidate both fresh locations and strict `< 50m` separation.
3. Reject stale revision with `409 OUTCOME_REVISION_CONFLICT`.
4. Complete both participants' field-visit/trip legs with their own timestamps,
   paths, distances, and device evidence.
5. Set CP state to `pending_review`; do not terminally publish the outcome.
6. Notify/invalidate the reviewer's mobile session immediately.
7. Return the workflow response with `actorRole=outcome_owner` and all mutation
   actions disabled.

Repeated requests with the same idempotency key return the same success result.

## 8. Reviewer edit and complete

Reviewer edits use the same revisioned outcome draft routes. Then:

```http
POST /api/marketing/clientPlaceVisits/joint-complete-review
Authorization: Bearer <token>
Idempotency-Key: <uuid>
Content-Type: application/json

{
  "id": "cp_123",
  "expectedOutcomeRevision": 4,
  "reviewerRemark": "Reviewed the outcome with the client"
}
```

In one transaction:

- require bearer to be the assigned reviewer;
- require `state=pending_review` and the latest revision;
- trim and require a non-empty `reviewerRemark`;
- publish the reviewed booking/SV/outcome and all dependent records;
- mark the CP `completed` exactly once;
- store reviewer staff ID, template ID/name, review timestamp, original owner
  revision, final reviewed revision, and reviewer remark; and
- close/remove active tasks without changing either route.

Success returns `state=completed`, `reviewedByName`,
`reviewedByTemplateName`, final `outcome`, and final `outcomeSummary`.

## 9. Live synchronization

Mobile currently polls the workflow read every five seconds only while Trip
Details is visible. Also send a push/WebSocket invalidation when the owner sends
review so the reviewer screen opens promptly. The read endpoint must be cheap,
side-effect free, cache-disabled for the bearer, and safe to poll.

## 10. Dual route response

Completed CP detail must return immutable participant route ownership:

```json
{
  "joint": {
    "participants": [
      {
        "staffId": "staff_bdo",
        "workflowRole": "outcome_owner",
        "fieldVisitId": "fv_bdo",
        "routeColor": "#1565C0",
        "templateId": "template_bdo",
        "templateName": "Sales & Marketing - BDO"
      },
      {
        "staffId": "staff_gm",
        "workflowRole": "reviewer",
        "fieldVisitId": "fv_gm",
        "routeColor": "#EF6C00",
        "templateId": "template_gm",
        "templateName": "Sales & Marketing - GM"
      }
    ]
  },
  "routes": [
    { "staffId": "staff_bdo", "color": "#1565C0", "points": [] },
    { "staffId": "staff_gm", "color": "#EF6C00", "points": [] }
  ]
}
```

Never merge both paths into one line. Each point must retain staff ID,
fieldVisit ID, accepted timestamp, coordinates, accuracy, and source device.

## 11. Concurrency and failure requirements

- Every mutation is idempotent and transactionally updates all linked rows.
- Template and role are snapshotted on creation; later IAM edits do not silently
  swap owner/reviewer on an active CP. Administrative reassignment requires a
  separate audited operation.
- Reviewer edits use optimistic revision checks; stale devices receive 409 and
  reload instead of overwriting newer data.
- App process death, rotation, relaunch, network loss, or switching devices must
  recover entirely from the workflow read.
- A failed upload, OTP, distance check, draft save, or review must not complete
  either the CP or only one participant trip.
- Cancellation must atomically close the CP, both field visits, both tasks, and
  any unpublished dependent draft.

## 12. Acceptance checks

1. BDO-template plus GM-template may create regardless of which user starts it.
2. Same-template or same-template-level staff are hidden by current mobile
   clients and rejected by API.
3. Missing-template and two-reviewer/two-owner pairs are rejected with no rows.
4. Reviewer cannot request or verify OTP.
5. Owner is blocked at 50m and allowed only below 50m with two fresh locations.
6. Owner supplies OTP/photo/outcome and sees `Send Review`, never `Complete`.
7. Reviewer sees waiting until submission, then gets live review availability.
8. Reviewer sees the submitted values, may edit all fields, and completes once.
9. Both trip legs complete together on send-review; CP stays pending review.
10. Final detail says `Outcome reviewed by {reviewer template/name}`.
11. Completed detail returns two distinct staff paths with blue/orange colors.
12. Retry, offline recovery, app restart, and concurrent edits create no partial
    state or duplicate booking/SV/follow-up records.
# MCONNECT MOBILE - CP TYPE PERSISTENCE API HANDOFF

## Problem

Some CP visits created by older mobile builds or backend-generated flows are
visible on web with a blank CP Type. Current Android and iOS builds send a
canonical type and now verify the persisted row before reporting clean create
success. The backend remains responsible for rejecting untyped writes and for
repairing historical rows.

## Existing create endpoint

`POST /api/marketing/clientPlaceVisits/create`

Required headers:

```http
Authorization: Bearer <token>
Idempotency-Key: <stable UUID for this form submission>
Content-Type: application/json
```

Normal CP request fields:

```json
{
  "cpType": "old_client"
}
```

Joint CP request fields:

```json
{
  "cpType": "joint_cp",
  "jointCpCategory": "old_client",
  "jointStaffIds": ["<secondStaffId>"]
}
```

Accepted purpose values:

- `sv_cum_cp`
- `new_client_cp`
- `booking_cp`
- `collection_cp`
- `old_client`
- `gift_distribution`
- `other_cp`

`joint_cp` is a mode, not a purpose. When selected, the actual purpose must be
stored in `jointCpCategory`.

## Required server validation

1. Reject a missing, blank, unknown, or display-label `cpType` with HTTP 400.
2. Require a canonical `jointCpCategory` when `cpType=joint_cp`.
3. Reject `jointCpCategory` for non-joint visits or ignore it consistently.
4. Persist the type fields in the same atomic transaction as the CP row.
5. Idempotent repeats must return the original row and must not erase or alter
   its type.
6. Every backend-created CP path, including Same Area SV and follow-up flows,
   must apply the same validation rather than inserting a blank type.

Recommended success response:

```json
{
  "success": true,
  "id": "<clientPlaceVisitId>",
  "requestId": "<same idempotency key>",
  "cpType": "joint_cp",
  "jointCpCategory": "old_client",
  "alreadyCreated": false
}
```

Mobile accepts either `id` or the legacy `visitId`, but `id` is preferred.

## Existing read endpoint

`GET /api/marketing/clientPlaceVisits/get?id=<clientPlaceVisitId>`

The returned `visit` must include the persisted `cpType` and, for Joint CP,
`jointCpCategory`. Android and iOS use this read after creation to verify what
web will receive.

The same fields must be returned unchanged by:

`GET /api/marketing/clientPlaceVisits/my`

This is the source used by mobile CP lists and completion routing. The web list
query must expose the same stored fields; it must not derive type from outcome.

## Completion invariant

Existing completion calls such as `markClientMet`, `setOutcome`, CP cancellation,
and linked field-visit completion must never clear or rewrite `cpType` or
`jointCpCategory`. Outcome/status describe what happened; type describes why the
visit was created. Those are separate fields.

For a Joint CP, completion must update the shared original CP row rather than
creating an untyped replacement row. Any follow-up or rescheduled CP created by
the server must copy the original purpose fields atomically.

## Historical repair required

Mobile cannot safely infer or patch types for already-created records. Run a
server-side audit for rows where `cpType` is null, blank, or outside the enum.
Backfill only from authoritative evidence such as the creation request,
idempotency audit, linked workflow, or explicit administrator selection. Do
not guess a type from completion status alone.

After repair, verify that CP list/detail/filter responses return the canonical
type and that web renders the expected label. No new mobile mutation endpoint
is required for this backfill.

## Acceptance checks

1. Create each normal CP purpose and confirm the create response and detail
   response contain the same canonical `cpType`.
2. Create a Joint CP for each purpose and confirm `cpType=joint_cp` plus the
   selected `jointCpCategory`.
3. Retry each request with the same idempotency key and confirm the same typed
   row is returned without duplication.
4. Confirm each new row immediately shows the correct type in web list/detail.
5. Confirm type-specific completion continues to produce the expected outcome.
6. Confirm a missing or invalid type returns HTTP 400 and creates no row.
7. Complete, cancel, postpone, and reschedule visits and confirm their original
   type remains present in both mobile and web detail/list responses.
