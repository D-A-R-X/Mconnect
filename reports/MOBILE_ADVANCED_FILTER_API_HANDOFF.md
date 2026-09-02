# MCONNECT MOBILE - ADVANCED LIST FILTER API HANDOFF

## Scope

Android and iOS now share the same full-screen filter behavior for CP Visits,
Site Visits, Attendance, Bookings, Leaves, and Permissions. Both clients
currently apply newly added dimensions to the records already returned by the
existing APIs. Date/status requests that were already supported remain
server-backed.

The additions below are required before mobile can guarantee complete results
for large or paginated datasets. They are additive. Do not reject unknown or
omitted optional query parameters, and do not change existing defaults.

Until these contracts are deployed, neither mobile client must send the new query keys;
strict query validation could otherwise cause HTTP 400.

## Common rules

- All filters combine with AND. Search fields use OR across their documented
  searchable columns.
- IDs, not display names, are the filter values.
- `fromDate` and `toDate` are inclusive local calendar dates in `yyyy-MM-dd`.
- If both dates are supplied and reversed, return HTTP 400 with a readable
  `error` and `code: "INVALID_DATE_RANGE"`.
- Cursor paging must keep a stable newest-first order and must not repeat or
  skip rows when a filter is active.
- IAM and reporting scope are enforced before computing rows or filter options.
- Return `success: true`, `records/visits/bookings`, `total`, `nextCursor`, and
  `hasMore`. `total` is the count after all filters, before paging.
- Empty matches return HTTP 200 with an empty list, never HTTP 404.

## 1. CP Visits

### List

`GET /api/marketing/clientPlaceVisits/my`

Existing parameters remain: `scope`, `fromDate`, `toDate`, `search`, `limit`.

Add:

| Query | Values |
|---|---|
| `assignedStaffId` | Staff document ID |
| `telecallerStaffId` | Staff document ID |
| `status` | `scheduled`, `postponed`, `in_progress`, `completed`, `cancelled` |
| `outcome` | Stored CP outcome value |
| `cpType` | Stored CP type value, including `joint_cp` and `sv_cum_cp` |
| `cursor` | Opaque paging cursor |
| `pageSize` | 20-100 |

Search must cover client name, client mobile, place/address, assigned staff,
telecaller, and CP reference/id.

`scope=mine` returns only the viewer's own/participant rows. `scope=direct`
returns only immediate reports, not grandchildren. Preserve the existing
response scope echo and `directReportIds` fail-closed contract.

Each visit must include:

```json
{
  "assignedStaffId": "staff-id",
  "assignedStaff": { "_id": "staff-id", "name": "Field Staff" },
  "telecallerStaffId": "staff-id",
  "telecaller": { "_id": "staff-id", "name": "Telecaller" },
  "status": "completed",
  "outcome": "booking",
  "cpType": "joint_cp",
  "scheduledDate": "2026-09-02"
}
```

### Options

Preferred additive endpoint:

`GET /api/marketing/clientPlaceVisits/filter-options?scope=mine|direct&fromDate=yyyy-MM-dd&toDate=yyyy-MM-dd`

```json
{
  "success": true,
  "fieldStaff": [{ "id": "staff-id", "name": "Name", "count": 12 }],
  "telecallers": [{ "id": "staff-id", "name": "Name", "count": 8 }],
  "statuses": [{ "value": "completed", "label": "Completed", "count": 30 }],
  "outcomes": [{ "value": "booking", "label": "Booking", "count": 9 }],
  "cpTypes": [{ "value": "joint_cp", "label": "Joint CP", "count": 4 }]
}
```

Options must cover the full authorized result set, not only page one.

## 2. Site Visits

### List

`GET /api/sitevisits/my`

Keep `fromDate` and `toDate`; add:

| Query | Values |
|---|---|
| `projectId` | Project document ID |
| `telecallerStaffId` | LMO/telecaller staff ID |
| `assignedStaffId` | Assigned BDO/field staff ID |
| `status` | `fixed`, `scheduled`, `enroute`, `onsite`, `returning_home`, `completed`, `cancelled`, `postponed` |
| `search` | Client/mobile/project/LMO/field staff text |
| `cursor` | Opaque cursor |
| `pageSize` | 20-100 |

The server owns lifecycle grouping. For example, `enroute` includes
`client_started` and `picked_up`; `onsite` includes `on_site` and
`on_counselling`; `returning_home` includes `picked_from_site` and `dropped`.

Every row must add these optional mobile fields while keeping all current ones:

```json
{
  "projectId": "project-id",
  "projectName": "Project Name",
  "lmoStaffId": "staff-id",
  "lmoName": "LMO Name",
  "bdoStaffId": "staff-id",
  "bdoName": "Field Staff Name"
}
```

### Options

`GET /api/sitevisits/filter-options?fromDate=yyyy-MM-dd&toDate=yyyy-MM-dd`

Return authorized `projects`, `lmos`, `fieldStaff`, and grouped `statuses`, each
with stable ID/value, label, and count.

## 3. Attendance

No replacement routes are needed. Extend the existing routes:

- `GET /api/hr/attendance/my`
- `GET /api/hr/attendance/team-attendance`
- `GET /api/hr/attendance/pending-approvals`
- `GET /api/hr/attendance/all`
- `GET /api/hr/attendance/hr-review`

Add where applicable:

| Query | Values |
|---|---|
| `status` | `present`, `half-day`, `absent`, `weekoff`, `holiday`, or approval state |
| `staffId` | One authorized staff ID; omit on the personal endpoint |
| `department` | Exact department value |
| `search` | Name, employee ID, phone, source |
| `cursor` | Opaque cursor |
| `pageSize` | 20-100 |

`team-attendance` must remain immediate-report scoped unless the UI explicitly
requests a separately authorized wider scope. A manager must never see a
grandchild report merely because their direct report manages that person.

Options endpoint:

`GET /api/hr/attendance/filter-options?view=my|team|approval|all|hr_review&fromDate=yyyy-MM-dd&toDate=yyyy-MM-dd`

Return `staff`, `departments`, and statuses available inside that authorized
view. Never expose names/counts outside the caller's IAM scope.

## 4. Bookings

### List

`GET /api/marketing/bookings/my`

Keep `status`; add:

| Query | Values |
|---|---|
| `projectId` | Project ID |
| `plotId` | Plot/unit ID |
| `fromDate` | Inclusive booking date |
| `toDate` | Inclusive booking date |
| `search` | Client, mobile, booking reference, project, plot |
| `cursor` | Opaque cursor |
| `pageSize` | 20-100 |

Return `projectId`, `projectName`, `plotId`, `plotNo/plotNumber`, `bookingDate`,
and `status` on every list row.

Options endpoint:

`GET /api/marketing/bookings/filter-options?fromDate=yyyy-MM-dd&toDate=yyyy-MM-dd`

Return authorized `projects`, project-linked `plots`, and booking statuses with
counts. Plot options should support `projectId` to avoid showing plots from an
unselected project.

## 5. Leaves

Extend both existing routes; no replacement route is required:

- `GET /api/hr/leaves/my`
- `GET /api/hr/leaves/pending-approvals`

Add optional `fromDate`, `toDate`, `status`, `leaveType`, `staffId`,
`department`, `search`, `cursor`, and `pageSize`. Date matching is overlap
matching: a leave is included when `leave.toDate >= fromDate` and
`leave.fromDate <= toDate`. Search covers applicant name and employee ID.
`staffId`/department are ignored or rejected on `/my`; cross-staff routes must
enforce the existing direct/all scope before filtering.

Each cross-staff row should include `staffId`, `staffName`, `employeeId`, and
`department`. Add
`GET /api/hr/leaves/filter-options?scope=my|direct|all&fromDate=...&toDate=...`
for full-scope staff, department, leave-type, and status options.

## 6. Permissions

Extend:

- `GET /api/hr/permissions`
- `GET /api/hr/permissions/pending-approvals`

Add optional `fromDate`, `toDate`, `status`, `staffId`, `department`, `search`,
`cursor`, and `pageSize`. Search covers applicant name and employee ID. Return
`staffId`, `staffName`, `employeeId`, and `department` on cross-staff rows.

Add
`GET /api/hr/permissions/filter-options?scope=my|direct|all&fromDate=...&toDate=...`
for complete authorized staff, department, and status options.

## Existing Mobile Filters That Need No New API

- Land inspections and land queries: current date/status filters operate on
  the complete response returned by their existing mobile endpoints.
- Tasks, notifications, App Library, and inventory: their category/status
  controls are local navigation or bounded local filtering, not web report
  queries. Converting these to server filters would add no correctness benefit.

If any of those endpoints becomes cursor-paginated later, add server-side
filter parameters and full-scope option metadata before removing its local
filtering.

## Acceptance checks

1. Combining any two filters returns their intersection and an accurate total.
2. Clearing all returns the endpoint's exact pre-change default result.
3. A selected staff ID never matches another staff member with the same name.
4. Direct-team views exclude reports-of-reports.
5. Search finds a matching row beyond page one.
6. Filter options and counts represent the full authorized result set.
7. Invalid dates return a readable 400; valid empty filters return 200/empty.
8. Existing clients that send none of the new parameters behave unchanged.
