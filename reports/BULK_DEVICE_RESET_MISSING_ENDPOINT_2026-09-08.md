# Bulk Device Reset: Missing Production Endpoint

Date checked: 2026-09-08

## Current result

The Android integration is prepared for:

```http
POST /api/hr/staff/device-reset/bulk
Authorization: Bearer <admin session token>
Content-Type: application/json

{
  "staffIds": ["<staff-id-1>", "<staff-id-2>"]
}
```

The production host `https://api-mfpl.theairix.com/` currently returns HTTP
`404` for this route. The existing single-staff route
`POST /api/hr/staff/device-reset` is reachable and returns the expected
structured HTTP `401` when probed with an invalid token.

## Backend action required

Deploy the bulk REST route on the same production host used by Android. It must:

- authenticate the bearer token before processing the body;
- require `staff.resetDeviceBinding`;
- reject an empty `staffIds` array with structured HTTP `400` JSON;
- deduplicate staff IDs and exclude unknown IDs safely;
- clear bindings only for the selected staff;
- deactivate only those staff members' mobile sessions;
- preserve all web sessions;
- process the whole request atomically or return an explicit partial-failure
  breakdown without reporting `success: true` for an unknown outcome;
- write an audit entry containing the administrator, selected IDs, counts and
  timestamp;
- return:

```json
{
  "success": true,
  "selectedStaffCount": 68,
  "staffWithBindings": 65,
  "bindingsCleared": 65,
  "mobileSessionsSignedOut": 67
}
```

## Safe verification

After deployment, run from the Android repository:

```powershell
node scripts/check-mobile-api.mjs device-rollout-contracts --rollout-mode compatibility --legacy-build 71 --target-build 72 --timeout 90000
```

The bulk route must return structured HTTP `401` for the invalid-token contract
probe, replacing the current HTTP `404`. A successful bulk reset must then be
tested only with selected disposable staff accounts because it signs out real
mobile sessions and clears real device bindings.
