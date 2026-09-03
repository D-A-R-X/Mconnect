# Site Visit Filter Options - Backend Fix Required

## Endpoint

```http
GET /api/sitevisits/filter-options
Authorization: Bearer <token>
```

## Current Production Failure

The authenticated endpoint returns HTTP 500 because its Convex function reads
more than the 16 MiB execution limit. The production error points to
`marketing/siteVisits.ts:3484`.

## Required Fix

Keep the existing route and response contract. Build the authorized option
sets without collecting every Site Visit and related document in one query.
Use indexed, bounded reads or a maintained facet/rollup table for:

- `projects`
- `lmos`
- `fieldStaff`
- grouped `statuses`

Authorization must remain identical to `GET /api/sitevisits/my`; option counts
must never include rows the authenticated user cannot view.

## Mobile Compatibility

Android and iOS do not depend on this endpoint to render the Site Visit list.
They continue to derive available options from loaded authorized rows and send
selected filters to `GET /api/sitevisits/my`. Once this endpoint returns 200,
the apps can consume full-scope option counts without changing the list route.

## Acceptance Checks

1. The endpoint returns HTTP 200 for a high-volume production-equivalent user.
2. Response keys remain `projects`, `lmos`, `fieldStaff`, and `statuses`.
3. Each option has a stable document ID where applicable, a display label, and
   an authorized count.
4. Direct-report scope does not include reports-of-reports.
5. The Convex execution remains comfortably below byte-read and memory limits.
