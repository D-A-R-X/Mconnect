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
