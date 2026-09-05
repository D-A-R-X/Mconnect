# CP creation and completion: mobile verification handoff

Date: 2026-09-05

## Verification status

Android: full debug unit suite passed (151 tests, zero failures/errors/skips),
and debug APK assembly passed. These are local tests, not production API tests.
iOS: source audit only; Xcode and device testing are unavailable on this Windows host.
Website code was not changed. No authenticated production mutations were made.

Read-only probes against `https://api-mfpl.theairix.com`:

| Route | Result without a token |
| --- | --- |
| GET /api/marketing/clientPlaceVisits/my | HTTP 401, JSON success=false, Bearer authorization required |
| GET /api/marketing/clientPlaceVisits/get | HTTP 401, JSON success=false, Bearer authorization required |

These results prove reachability and authentication enforcement only. They do
not prove authenticated response fields, IAM visibility, creation, or web sync.

## Existing contracts to verify with the admin

No new route is established as necessary by this audit. Confirm these behaviors
on the deployed endpoints using a disposable test client and staff account.

### POST /api/marketing/clientPlaceVisits/create

Authorization: Bearer token; Content-Type: application/json;
Idempotency-Key: stable UUID for an unchanged request.

Both apps send clientName, mobileNumber, assignedStaffId, lmoStaffId,
scheduledDate, scheduledTime, visitAddress, visitLat, visitLng, projectId,
cpType, and optional googleMapsLink, notes and pincode. New-client requests
can include referralSourceType and referringClientId.

Joint CP continues to send cpType=`joint_cp`, its actual purpose in
jointCpCategory, and both unique participant IDs in jointStaffIds. The server
must validate effective IAM templates and participant access as before.

Required success shape used by mobile:

```json
{
  "success": true,
  "id": "created-cp-id",
  "requestId": "same-Idempotency-Key",
  "cpType": "joint_cp",
  "jointCpCategory": "old_client",
  "alreadyCreated": false
}
```

The existing `visitId` alias is also supported for `id`. Replay must return
the original row rather than create a duplicate. The same key with a changed
payload should be rejected, not silently overwrite the old record. Idempotent
replay must be resolved before rejecting the client created by the original
new-client request or rejecting that original CP as a same-day duplicate.

GET /api/marketing/clientPlaceVisits/my and
GET /api/marketing/clientPlaceVisits/get?id=... must retain requestId, cpType,
jointCpCategory, participant assignments, and current status. Exact requestId
lets the preflight duplicate check distinguish retry from a second visit.
Absent/mismatched request IDs remain an integration limitation, not success.

### POST /api/bookings

CP booking payload carries cpVisitId and sourceClientPlaceVisitId.
SV booking payload carries siteVisitId and sourceSiteVisitId.
Submitted booking status is `pending_confirmation`; draft status is `draft`.

Confirm that saving a draft does NOT mark the source CP/SV completed, stop a
trip, or perform Joint CP review completion. Mobile no longer sends the parent
completion callback for a draft, but it cannot undo an incorrect server-side
transition. Define/verify resumable draft behavior and protection against
duplicate bookings after an interrupted response.

Submitted source-linked booking must update the appropriate source records
atomically according to approval and Joint CP review rules. Do not require a
second terminal setOutcome call after the booking mutation already converted
the source. Preserve source IDs and selected CP type for web detail/list views.

### CP outcome and trip endpoints

| Existing endpoint | Contract to verify |
| --- | --- |
| POST /api/marketing/clientPlaceVisits/markClientMet | id, clientMet, optional clientNoShowReason; return success/error |
| POST /api/marketing/clientPlaceVisits/setOutcome | id, outcome, applicable notes, postponeReasons, followUpDate, arrivalPhotoStorageId; preserve type and linked state |
| POST /api/marketing/clientPlaceVisits/cancel | id, required trimmed reason; idempotent atomic closure of linked CP/SV/trip/task |
| POST /api/marketing/clientPlaceVisits/referral | idempotent referral linkage; no duplicates on repeated completion attempts |
| POST /api/geotrack/visit/arrival-otp/request | visitId, lat, lng; success=false must not be treated as OTP sent |
| POST /api/geotrack/visit/arrival-otp/verify | visitId, otp, optional coordinates/proof; enforce OTP without resetting unrelated sessions |
| POST /api/geotrack/visit/arrival-otp/cancel | visitId; explicit success/error |
| POST /api/geotrack/visit/complete | visitId, optional coordinates, remarks and arrivalPhotoStorageId; explicit success/error; enforce required proof and Joint CP review state |

Client-not-met completion requires proof photo, not an arrival OTP. Optional
remarks must remain optional. A HTTP 200 with success=false is still a failure;
mobile must not mark the trip completed even if error text is omitted.

## Acceptance checklist on test data

### Additional iOS client-identity regression checks

Code audit on 2026-09-05 found stale client autofill when switching phone
numbers and stale manual search responses. iOS-only corrections are local;
the affected production records have not been inspected or repaired.

- Enter client A's number and allow autofill; switch to client B. A's unchanged
  autofilled name, address, map coordinates, project and LMO must not carry over.
- Manually edit a field before changing the number: intentional edits remain.
  Phone formatting changes for the same normalized number must not clear data.
- Delay A's lookup, enter B, then deliver A's response. It must not change B's
  number, selected lead or details. Repeat with the manual search button.
- Return multiple exact-phone lead matches: show choices, do not silently pick
  the first. Return a wrong-phone match: do not bind it to this request.
- Return a lead without a name: never use its phone number or record ID as the
  submitted clientName. Require entry or a named client-master lookup result.
- Submit while a lookup/address parse is pending: no late response may change
  the outgoing request. A selected leadId must match the submitted phone.
- Compare the captured POST create payload with GET detail and web for the same
  created ID. If a correct payload becomes different server/web data, investigate
  backend source precedence, persisted snapshots and display logic separately.

### Full CP acceptance

1. Create each ordinary CP purpose and a valid Joint CP from both platforms;
   compare returned ID/type with detail and the web record, under proper IAM.
2. Drop the network after submission; retry unchanged input and verify one CP.
   Change notes/referral/participants and confirm the key is not reused.
3. Rapidly tap Create; only one submission may be in flight. Verify server
   idempotency independently with concurrent clients.
4. Save CP/SV booking draft; source visit remains uncompleted. Resume and
   submit for approval; confirm correct source linkage and final web state.
5. Complete ordinary CP outcomes, postponement, cancellation and client-not-met;
   check field-visit/task state and optional remarks/photo on web.
6. Test Joint CP OTP, proximity, Send Review and reviewer completion separately;
   a booking draft must not bypass that workflow.
7. Return success=false with and without error text, invalid JSON, HTTP 400,
   403, authoritative 401 and network timeout. Failed operations must not
   display completion or discard recoverable user input.
8. On a Mac, build iOS, test on-device, and run equivalent mocked API cases.
   This handoff does not certify iOS build/runtime or production sync.
