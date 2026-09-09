# Staff Select All Endpoint Deployment Gap - 2026-09-08

This is a new gaps-only report. Existing endpoint documents were not modified.

## Mobile Contract

```http
GET /api/hr/staff/selectable-ids
Authorization: Bearer <session-token>
```

Supported optional query parameters:

- `status`
- `role`
- `designation`
- `department`
- `query`

Expected success response:

```json
{
  "success": true,
  "total": 1658,
  "staffIds": ["staffId1", "staffId2"]
}
```

The Android Security > Device Reset screen now uses this route to select staff
across unloaded pages while preserving the active designation, department and
search filters. It excludes the signed-in administrator and limits the client
selection to 2,000 unique non-blank IDs.

## Production Result

Checked against the Android API host:

```http
GET https://api-mfpl.theairix.com/api/hr/staff/selectable-ids?status=active&department=Sales
Authorization: Bearer contract-probe-invalid-token
```

Observed:

```http
HTTP/1.1 404 Not Found

No matching routes found
```

The endpoint is not currently deployed on `api-mfpl.theairix.com`. The Android
UI handles this without crashing and shows that Select All is not available on
the server yet. Individual staff selection and the existing bulk reset action
remain usable.

## Required Backend Action

Deploy the route on `https://api-mfpl.theairix.com/` and ensure authentication
runs before filter processing. An invalid bearer must return structured JSON
HTTP 401, not HTTP 404.

After deployment, run:

```powershell
node scripts/check-mobile-api.mjs staff-security-contracts
```

Then verify with an authorized disposable admin session that:

1. No filters returns every staff ID selectable by that administrator.
2. Search, designation and department combinations match the visible web set.
3. The signed-in administrator is not reset by the mobile bulk action.
4. Responses never contain more than 2,000 IDs.
5. `total` reports the full matched count when the 2,000-ID cap is reached.
