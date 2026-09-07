# MCONNECT MOBILE - ALL FILTER API HANDOFF

## Scope

This is the single backend handoff for Android and iOS advanced filters across
CP Visits, Site Visits, Attendance, Bookings, Leaves, and Permissions.

Production was checked with an authenticated account against
`https://api-mfpl.theairix.com` on 2026-09-03. All checks were read-only.

## Action required

Only Site Visits have a consistently unusable production API. CP is deployed
and works in normal sequential mobile use, but showed a concurrency-sensitive
performance failure during stress checks. The other module contracts returned
HTTP 200.

### 1. CP list filtering is deployed but needs load hardening

The metadata endpoint works:

```http
GET /api/marketing/clientPlaceVisits/filter-options?scope=mine|direct&fromDate=yyyy-MM-dd&toDate=yyyy-MM-dd
Authorization: Bearer <token>
```

Concurrent probes of these list requests returned HTTP 500 with
`Your request timed out performing too many system operations`:

```http
GET /api/marketing/clientPlaceVisits/my?scope=mine&pageSize=20&status=scheduled
GET /api/marketing/clientPlaceVisits/my?scope=mine&pageSize=20&outcome=interested
```

The same list endpoint returned HTTP 200 when filtering by `cpType`,
`assignedStaffId`, or `telecallerStaffId`. Subsequent sequential Android-device
requests for both `status=scheduled` and `outcome=interested` also returned HTTP
200 in about 1-5 seconds. Therefore these parameters are not missing; the
failure is load-sensitive. Mobile includes a 5xx compatibility fallback that
re-fetches the same authorized slice without status/outcome and filters the
bounded result locally.

Recommended hardening:

- Apply `status` and `outcome` through selective indexed queries before row
  enrichment.
- Keep `scope=mine|direct` IAM filtering fail-closed.
- Page before hydrating clients, places, staff, field visits, and linked rows.
- Return HTTP 200 with an empty `visits` array when nothing matches.
- Preserve `success`, `visits`, `total`, `scope`, `directReportIds`,
  `nextCursor`, and `hasMore`.

Full list contract:

```http
GET /api/marketing/clientPlaceVisits/my
  ?scope=mine|direct
  &fromDate=yyyy-MM-dd
  &toDate=yyyy-MM-dd
  &search=<text>
  &assignedStaffId=<staff-id>
  &telecallerStaffId=<staff-id>
  &status=scheduled|postponed|in_progress|completed|cancelled|pending_gm_approval
  &outcome=<stored-outcome>
  &cpType=<stored-cp-type>
  &pageSize=20
  &cursor=<opaque-cursor>
```

### 2. Site Visit options and filtered list exceed backend limits

Required metadata endpoint:

```http
GET /api/sitevisits/filter-options?fromDate=yyyy-MM-dd&toDate=yyyy-MM-dd
Authorization: Bearer <token>
```

Expected response:

```json
{
  "success": true,
  "projects": [{ "id": "project-id", "name": "Project", "count": 2 }],
  "lmos": [{ "id": "staff-id", "name": "LMO", "count": 2 }],
  "fieldStaff": [{ "id": "staff-id", "name": "Field Staff", "count": 1 }],
  "statuses": [{ "value": "scheduled", "label": "Scheduled", "count": 1 }]
}
```

The supplied `sitevisits-filter-options-handoff.md` describes a bounded facet
implementation, but that fix is not active on the tested production API:

- `/api/sitevisits/filter-options` did not respond within 60 seconds.
- `/api/sitevisits/my?status=scheduled&pageSize=20` returned HTTP 500 because
  one Convex execution read more than 16 MiB at
  `marketing/siteVisits.ts:3722`.

Required fix/deployment:

- Deploy the lean paginated facet readers described in the supplied handoff.
- Apply list filters and IAM scope before enrichment in `/api/sitevisits/my`.
- Bound every Convex query page below the read limit; do not call `.collect()`
  over the full authorized company dataset.
- Return stable grouped status values and HTTP 200 for empty results.

Full list contract:

```http
GET /api/sitevisits/my
  ?fromDate=yyyy-MM-dd
  &toDate=yyyy-MM-dd
  &projectId=<project-id>
  &telecallerStaffId=<staff-id>
  &assignedStaffId=<staff-id>
  &status=fixed|scheduled|enroute|onsite|returning_home|completed|cancelled|postponed
  &search=<text>
  &pageSize=20
  &cursor=<opaque-cursor>
```

## Confirmed working endpoints

### Attendance

```http
GET /api/hr/attendance/filter-options?view=my|team|approval|all|hr_review&fromDate=...&toDate=...
```

Production result: HTTP 200. A filtered `/api/hr/attendance/my` request also
returned HTTP 200. Empty `staff`, `departments`, or `statuses` arrays are valid
for a view/date range with no attendance rows.

### Bookings

```http
GET /api/marketing/bookings/filter-options?fromDate=...&toDate=...&projectId=...
```

Production result: HTTP 200 with 17 projects, 175 plots, and 3 statuses.
Filtered list requests by status and project both returned HTTP 200.

### Leaves

```http
GET /api/hr/leaves/filter-options?scope=my|direct|all&fromDate=...&toDate=...
```

Production result: HTTP 200. A filtered `/api/hr/leaves/my` request also
returned HTTP 200.

### Permissions

```http
GET /api/hr/permissions/filter-options?scope=my|direct|all&fromDate=...&toDate=...
```

Production result: HTTP 200. A filtered `/api/hr/permissions` request also
returned HTTP 200.

## Common response and search rules

- Filter values are IDs or stored enum values, never display names.
- All selected dimensions combine with AND.
- Option arrays cover the full authorized result set, not only the current
  list page.
- Staff options should optionally include `employeeId`, `designation`, and
  `department`; mobile searches these subtitles as well as the staff name.
- Searchable list endpoints must search the same fields as web.
- Date ranges are inclusive `yyyy-MM-dd` values.
- Empty matches return HTTP 200 with an empty array, never 404 or 500.
- `scope=direct` means immediate reports only, never reports-of-reports.
- Cursor paging must use a stable order without duplicate or skipped rows.

## CP option vocabulary fallback

The live CP options endpoint returned zero outcomes because the authorized CP
rows currently have no stored outcome. This is valid data, not an error. Mobile
therefore keeps the complete valid outcome and historical CP-type vocabulary
locally, and merges server counts/labels when present. The server should still
return only values present in the authorized result set.
