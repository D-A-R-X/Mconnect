# MCONNECT - JOINT CP TOGGLE API HANDOFF

## Scope

Joint CP is now a separate UI control above the normal CP Type field:

- Android and iOS use a toggle.
- Web uses a checkbox.
- `Joint CP` is no longer shown as a selectable CP type.
- When Joint CP is enabled, the user selects one normal CP purpose and one
  additional staff member.

## Endpoint

No new endpoint is required.

```http
POST /api/marketing/clientPlaceVisits/create
Authorization: Bearer <token>
Idempotency-Key: <stable-request-id>
Content-Type: application/json
```

### Normal CP

```json
{
  "cpType": "booking_cp",
  "assignedStaffId": "<primary-staff-id>"
}
```

### Joint CP

The clients preserve the existing backend representation so current joint
assignment, trip, OTP, completion, follow-up, visibility, and reporting logic
continues to work without a migration.

```json
{
  "cpType": "joint_cp",
  "jointCpCategory": "booking_cp",
  "assignedStaffId": "<first-staff-id>",
  "jointStaffIds": ["<second-staff-id>"]
}
```

`jointCpCategory` is the single normal CP type chosen in the form. The backend
continues to validate two distinct active staff and promotes the senior staff
as the outcome owner.

## Supported Joint Purposes

- `sv_cum_cp`
- `booking_cp`
- `collection_cp`
- `old_client`
- `gift_distribution`
- `other_cp`

`new_client_cp` remains unavailable while Joint CP is enabled because the
current backend validator and referral outcome flow do not support it as a
joint category.

## Acceptance Checks

1. Joint CP never appears inside the CP Type dropdown.
2. Enabling Joint CP reveals the second-staff selector.
3. Disabling Joint CP clears any selected second staff.
4. Joint submit sends `cpType=joint_cp`, the selected purpose in
   `jointCpCategory`, and exactly one additional staff ID.
5. Normal submit sends the selected purpose as `cpType` and omits all joint
   fields.
6. Existing Joint CP records continue to display and complete unchanged.


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
