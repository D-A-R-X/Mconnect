# Joint CP OTP, Outcome and Completion API Fix

Date: 10 Sep 2026

## Existing endpoints involved

1. `GET /api/marketing/clientPlaceVisits/joint-workflow?id=<cpVisitId>`
2. `POST /api/geotrack/visit/arrival-otp/request`
3. `POST /api/geotrack/visit/arrival-otp/verify`
4. Existing CP outcome draft endpoint selected by the outcome form, including `POST /api/marketing/clientPlaceVisits/setOutcome`
5. `POST /api/marketing/clientPlaceVisits/joint-submit-review`
6. `POST /api/marketing/clientPlaceVisits/joint-complete-review`

No extra endpoint is required. These endpoints must share the same authoritative Joint CP workflow resolver and avoid repeated full staff/visit scans.

## API validation requirements

Mobile display flags are never authority. Every write must resolve the bearer staff ID and the saved Joint CP owner/reviewer snapshot again.

- OTP request and verify: allow only the saved `outcome_owner`; validate active visit, OTP, and arrival proof server-side.
- Outcome draft: allow the saved outcome owner before review, and the saved reviewer only during the review phase; increment and return `outcomeRevision`.
- Submit review: allow only the outcome owner; require verified OTP, linked photo, saved outcome draft, and matching revision; set `pending_review` atomically.
- Complete review: allow only the reviewer; require `pending_review`/`reviewing`, the latest revision, and non-empty reviewer remarks; mark the parent CP and both participant trip legs complete and credit both staff atomically.
- Staff proximity, stale partner location, and partner GPS accuracy must not be required by any Joint CP endpoint.
- Repeated requests with the same `Idempotency-Key` must return the original successful result.

## Response contract

Each successful workflow mutation must return the refreshed workflow immediately so mobile does not need an extra blocking GET:

```json
{
  "success": true,
  "workflow": {
    "state": "pending_review",
    "actorRole": "outcome_owner",
    "outcomeOwnerStaffId": "lower-staff-id",
    "reviewerStaffId": "higher-staff-id",
    "outcomeRevision": 4,
    "canRequestOtp": false,
    "canSubmitOutcome": false,
    "canReview": false,
    "canCompleteReview": false
  }
}
```

Final completion must additionally return `state: "completed"` and `creditedStaffIds` containing both saved participant IDs.

## Performance acceptance

- Authentication and indexed visit lookup should happen before expensive enrichment.
- Do not load the full staff directory, all field visits, or full history for one workflow mutation.
- Use indexed lookup by CP ID and saved participant IDs.
- Target each OTP/workflow mutation response below 3 seconds under normal production load.
- Return the mutation result directly; do not require mobile to poll before enabling the next role.

## Client behavior already corrected

- The verified lower-level owner can reopen an accidentally dismissed outcome form even when a stale response says `canSubmitOutcome: false`.
- The higher-level reviewer action uses saved actor ID, `pending_review`/`reviewing`, and `outcomeRevision`, not stale convenience booleans.
- Owner submit-review no longer waits for a second GPS fix.
- Reviewer completion no longer performs a workflow GET before the completion POST.
- Readback GET is retained only after an ambiguous timeout to detect a committed idempotent mutation.
