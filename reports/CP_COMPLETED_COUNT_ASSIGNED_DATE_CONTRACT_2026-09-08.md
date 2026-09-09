# CP Completed Count by Assigned Date

## Required behavior

A completed CP must count on the CP's assigned/scheduled date, regardless of
the calendar date on which the workflow was actually completed.

Example:

- `scheduledDate`: `2026-09-05`
- `completedAt`: `2026-09-08T12:07:00+05:30`
- completed-count date: `2026-09-05`

The completion timestamp remains unchanged and must still be available for
timeline, audit, SLA, and detail views. It must not determine dashboard count
grouping or the date under which the completed CP is listed.

## Existing endpoint to update

```http
GET /api/marketing/clientPlaceVisits/completed-count?date=YYYY-MM-DD&staffId=OPTIONAL
Authorization: Bearer <token>
```

No new mobile endpoint is required. The existing endpoint must interpret
`date` as the CP's assigned `scheduledDate`.

### Success response

```json
{
  "success": true,
  "date": "2026-09-05",
  "staffId": "staff-id",
  "completedCount": 1,
  "visitIds": ["client-place-visit-id"]
}
```

## Counting rules

1. Include only CPs whose authoritative/effective status is completed.
2. Match the requested date against `clientPlaceVisit.scheduledDate`, not
   `completedAt`, field-visit completion time, participant completion time, or
   a derived activity date.
3. Count each CP once per credited staff member. Repeated completion requests
   and retries must remain idempotent.
4. For Joint CP, count the completed parent CP once for each participant under
   the same parent CP `scheduledDate`.
5. Do not count a Joint CP until the shared workflow is authoritatively
   completed. A participant outcome alone is not final completion.
6. If an authorized reschedule changes `scheduledDate` before completion, use
   the final assigned `scheduledDate` stored on the CP when it completes.
7. Preserve `completedAt` and all proof, OTP, outcome, reviewer remark, photo,
   proximity, trip, and audit records exactly as captured.

## List/read contract

The CP list endpoints must return the original assigned date separately from
completion metadata:

```json
{
  "id": "client-place-visit-id",
  "scheduledDate": "2026-09-05",
  "effectiveStatus": "completed",
  "completedAt": 1788859020000,
  "activityDate": "2026-09-05"
}
```

For compatibility, `activityDate` should also equal `scheduledDate`. Android
and iOS now use `scheduledDate` for CP list/date-filter attribution and retain
`completedAt` only for detail and audit presentation.

## Historical repair

Provide an authenticated, admin-only, dry-run/apply repair that rebuilds CP
completion credits and count projections using the parent CP `scheduledDate`.
The repair must:

- include normal and Joint CP records;
- credit both Joint CP participants exactly once;
- move existing completion credit from a completion-date bucket to the
  assigned-date bucket when necessary;
- repair a completed field visit whose parent CP still carries a stale
  scheduled/in-progress status only when the recorded OTP/outcome/workflow
  evidence proves final completion;
- never rewrite `completedAt`, OTP, outcome, photo, remarks, location, or audit
  evidence;
- be idempotent and return scanned, repaired, skipped, and conflict counts.

## Acceptance cases

| Assigned date | Completion date | Counted date | Credits |
| --- | --- | --- | --- |
| 2026-09-05 | 2026-09-05 | 2026-09-05 | 1 per credited staff |
| 2026-09-05 | 2026-09-08 | 2026-09-05 | 1 per credited staff |
| 2026-09-08 | 2026-09-05 | 2026-09-08 | 1 per credited staff |

Joint CP acceptance must additionally prove that both participants receive one
completed credit on the assigned date and neither receives a duplicate after
retrying outcome, remarks, or completion requests.
