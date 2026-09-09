# Joint CP Participant Tracking Backend Gap

Date: 2026-09-08

## Problem

Both Joint CP staff can see the visit and press Start, but the deployed/start
contract is not participant-aware. The CP list currently exposes the parent
`fieldVisitId`, and `POST /api/geotrack/visit/start` creates or reuses the trip
and tracking session for `fieldVisits.staffId`. A second participant starting
the same ID can receive `alreadyStarted: true` while the trip remains attributed
to the first field-visit owner.

Attendance tracking for the second account is not proof that their location is
linked to their Joint CP participant leg.

## Required Existing Endpoint Change

No public self-service route is required. Enhance the existing authenticated
route:

```http
POST /api/geotrack/visit/start
Authorization: Bearer <staff-session-token>
Content-Type: application/json
```

```json
{
  "visitId": "<clientPlaceVisitId-or-participant-fieldVisitId>",
  "lat": 13.0001,
  "lng": 80.2001
}
```

For a Joint CP, the server must:

1. Resolve the acting staff only from the validated bearer token.
2. Verify that staff has a `clientPlaceVisitParticipants` row for the CP.
3. Idempotently create or reuse a separate `fieldVisits` row owned by that
   participant.
4. Idempotently create or reuse an active `geoTrips` row with the same staff ID
   and participant field-visit ID.
5. Open the tracking session with that staff ID and participant field-visit ID.
6. Patch only that participant row with `fieldVisitId`, `status: in_progress`,
   `startedAt`, and `updatedAt`.
7. Never return another participant's field visit or mark another participant
   started.

Required success response:

```json
{
  "success": true,
  "alreadyStarted": false,
  "clientPlaceVisitId": "<cp-id>",
  "participantStaffId": "<authenticated-staff-id>",
  "fieldVisitId": "<authenticated-participant-field-visit-id>",
  "tripId": "<authenticated-participant-trip-id>",
  "trackingSessionId": "<authenticated-participant-tracking-session-id>",
  "participantStatus": "in_progress"
}
```

An idempotent repeat must return the same ownership and IDs with
`alreadyStarted: true`.

## Required Read Contract

These authenticated reads must expose the requesting participant's leg:

- `GET /api/marketing/clientPlaceVisits/my`
- `GET /api/marketing/clientPlaceVisits/get?id={cpId}`
- `GET /api/geotrack/today-visits?date=YYYY-MM-DD`
- `GET /api/marketing/clientPlaceVisits/joint-workflow?id={cpId}`

The response may either set the top-level `fieldVisitId`/field-visit status to
the authenticated participant's values or include them in the matching
`joint.participants[]` entry. Android and iOS now prefer the matching
participant entry and retain the parent value only as a legacy fallback.

```json
{
  "joint": {
    "participants": [
      {
        "staffId": "<authenticated-staff-id>",
        "status": "in_progress",
        "startedAt": 1788877800000,
        "fieldVisitId": "<authenticated-participant-field-visit-id>"
      }
    ]
  }
}
```

## Completion Requirement

`POST /api/marketing/clientPlaceVisits/joint-complete-review` must atomically
close both participant field visits, geo trips, and tracking sessions, then
return both staff IDs in `creditedStaffIds`. A participant may not be credited
as completed while their own trip or tracking session remains active.

## Acceptance Test

Use two test staff with different designation levels and two devices:

1. Both are clocked in and open the same Joint CP.
2. Lower-level staff starts first; verify their unique field visit, trip, and
   tracking session are owned by them.
3. Higher-level staff starts second; verify different IDs owned by them.
4. Confirm both participant rows independently show `in_progress`.
5. Confirm location batches from each bearer/device update only that staff's
   tracking session.
6. Complete the OTP/outcome/reviewer flow within 50 metres.
7. Confirm both participant trips and sessions are closed and both staff receive
   the completion count on the actual completion date.

Do not certify dual Joint CP tracking until this two-account test passes against
the production-equivalent backend.
