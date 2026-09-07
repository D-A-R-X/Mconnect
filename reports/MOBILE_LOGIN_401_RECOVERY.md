# Mconnect Mobile Login HTTP 401 Recovery

## What mobile sends

```http
POST /api/auth/login-with-employee-id
Content-Type: application/json
X-App-Platform: android

{
  "employeeId": "22026",
  "password": "<staff password>",
  "deviceId": "<stable Android ID or iOS Keychain ID>",
  "devicePlatform": "android",
  "deviceModel": "<model>",
  "batteryPct": 80
}
```

The endpoint deliberately returns HTTP 401 for invalid credentials, an inactive
account, a missing password setup, or a mobile-device binding mismatch. Mobile
must display the server's `error` text and must not bypass the device lock.

## Diagnose an affected staff member

1. Confirm the Employee ID is active and has a password configured.
2. Check the failed-login audit record. For `reason: device_locked`, compare
   `attemptedDeviceId`/`attemptedModel` with `boundDeviceId`/`boundModel`.
3. Confirm the staff installed the official Play Store/TestFlight build. A
   differently signed Android APK can produce a different Android ID even on
   the same physical phone.
4. If the password is correct and the stored binding is stale, use the
   permission-gated device reset below for only that staff member.

## Staff-specific recovery API

```http
POST /api/hr/staff/device-reset
Authorization: Bearer <admin token with staff.resetDeviceBinding>
Content-Type: application/json

{
  "staffId": "<Convex staff document ID>"
}
```

Expected success:

```json
{
  "success": true,
  "cleared": 1,
  "signedOut": 1
}
```

This clears only the selected staff member's mobile-device binding and ends
that staff member's current mobile sessions. It does not delete or alter CP,
SV, Joint CP, attendance, tracking history, documents, passwords, staff
profiles, or web sessions. The next successful official-app login establishes
the new binding.

## Fleet-wide stale-binding recovery

The backend contains the internal mutation
`staffDeviceBinding:resetActiveStaffDeviceBindingsBatch`. An authorized backend
operator should run every batch first with `dryRun: true`, review the matched
count and audit evidence, and run the write mode only after explicit production
approval. This is an operational backend action, not a mobile endpoint. Do not
run a fleet reset merely because one user typed a wrong password.

## Verification

After a staff-specific reset:

1. Sign in from the official store build using the same Employee ID and password.
2. Confirm login returns HTTP 200 with `success`, `token`, and `user`.
3. Confirm the app opens without a false `Session expired` event.
4. Open CP and SV lists and confirm authenticated requests return data.
5. Use disposable test visits for CP/SV completion testing; never complete a
   real staff visit as an API probe.
