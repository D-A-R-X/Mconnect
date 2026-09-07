# Post-Outcome CP Referral API Handoff

## Scope

Android and iOS already ask the staff member whether a client referred another
person after completing a New Client CP. This is separate from the
`referralSourceType` captured when the original CP is created.

The deployed API currently returns `404 No matching routes found` for the
mobile route below. Both mobile clients require the same endpoint.

## Endpoint

```http
POST /api/marketing/clientPlaceVisits/referral
Authorization: Bearer <token>
Content-Type: application/json
Idempotency-Key: <uuid-v4>   # recommended
```

```json
{
  "id": "<completed-or-completing-clientPlaceVisitId>",
  "clientName": "Referred client name",
  "mobileNumber": "9876543210",
  "address": "Optional address"
}
```

## Required behavior

- Authorize the authenticated staff member against the source CP, including a
  Joint CP participant.
- Allow this operation only for a New Client CP source visit.
- Normalize and validate the mobile number using the same rules as CP/client
  creation.
- Create or link the referred client and persist attribution back to the
  source CP without changing the CP's selected completion outcome.
- Treat an identical retry as success and do not create duplicate clients or
  referral rows. Prefer the `Idempotency-Key`; a server-derived unique key of
  source CP plus normalized mobile is also acceptable.
- Do not partially create a client without its referral attribution.
- Referral persistence and CP completion must remain independently retryable;
  a transient referral failure must not corrupt the CP outcome.

## Success response

```json
{
  "success": true,
  "clientId": "<clientId>",
  "referralId": "<referralId-or-null>",
  "alreadyRecorded": false
}
```

An idempotent retry may return `alreadyRecorded: true`.

## Error response

```json
{
  "success": false,
  "code": "VALIDATION_ERROR | FORBIDDEN | NOT_FOUND | INVALID_STATE",
  "error": "Human-readable error"
}
```

## Acceptance checks

1. A valid referral is visible from the source CP and the referred client.
2. Retrying the same request creates no duplicate.
3. Joint CP participants can record a referral for their shared CP.
4. Invalid phone/name returns 400 without partial data.
5. Missing/unauthorized CP returns 404/403 without revealing unrelated data.
6. Recording a referral does not alter or reopen the CP outcome.
