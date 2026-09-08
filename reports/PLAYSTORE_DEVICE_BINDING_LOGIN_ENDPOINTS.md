# Play Store device-binding login endpoints

## Objective

After the corrected mobile release is installed, every active staff member must
be able to sign in once without an old device-binding conflict. That successful
login stores the device identity emitted by the installed Play/TestFlight app.
Afterwards, the normal one-account/one-device restriction is enforced again.

This is a one-time migration. Do not add a permanent public bypass, silently
move a binding to a different phone, or expose a self-service reset button.

Production API base URL:

```text
https://api-mfpl.theairix.com/
```

No additional public login endpoint is required. The existing three login
routes need the build metadata below, and the backend needs the existing
internal batch mutation for the one-time reset.

## Backward-compatible rollout modes

The backend deployment must default to `compatibility`. Deploying endpoint code
must not automatically reset bindings, deactivate sessions, or reject an APK
that does not yet send build metadata.

Use a backend configuration value with these three states:

| Mode | Existing APK users | Pilot users | Binding reset |
| --- | --- | --- | --- |
| `compatibility` | Continue current sessions and login behavior | No special handling | None |
| `pilot` | Continue current sessions and login behavior | Require the approved build and test new binding | Selected pilot accounts only |
| `enforced` | Builds below the platform minimum must update | Approved build continues | Fleet reset is run deliberately |

In `compatibility` and for non-pilot users in `pilot` mode:

- missing `appVersion`/`appBuild` means an older compatible APK;
- preserve its existing session and exact matching binding;
- do not migrate, clear, or replace its binding merely because metadata is
  absent;
- keep all added request fields optional;
- keep existing success and error response shapes backward compatible.

The batch reset must never run automatically during deployment or application
startup.

## Rollout build guard

Android version code 71 sends these fields on Employee-ID login, OTP request,
and OTP verification:

```json
{
  "appVersion": "1.0",
  "appBuild": 71
}
```

During `pilot`, apply Android minimum build 71 only to selected pilot staff.
During `enforced`, apply it to all Android staff. A request below the applicable
minimum must not create or refresh a device binding. Return:

```http
HTTP/1.1 426 Upgrade Required
```

```json
{
  "success": false,
  "code": "UPDATE_REQUIRED",
  "error": "Update Mconnect to continue signing in.",
  "minimumBuild": 71
}
```

This guard prevents an old APK from reclaiming a row after the reset but before
the staff member installs the Play update. Keep the minimum-build value in
backend configuration so future releases do not require endpoint code changes.
Store minimum builds separately by platform; Android build 71 must not be
compared with an unrelated iOS/TestFlight build number.

## Required rollout operation

### Internal: `staffDeviceBinding:resetActiveStaffDeviceBindingsBatch`

This is an internal backend mutation, not a public HTTP endpoint.

Input:

```json
{
  "dryRun": true,
  "batchSize": 100,
  "cursorEmployeeId": null
}
```

Expected result:

```json
{
  "dryRun": true,
  "activeStaffScanned": 100,
  "boundStaffMatched": 96,
  "bindingRecordsMatched": 96,
  "bindingsCleared": 0,
  "mobileSessionsSignedOut": 0,
  "nextCursorEmployeeId": "cursor-value-or-null",
  "isDone": false
}
```

Run every page with `dryRun=true` first. Review the totals, then repeat with
`dryRun=false` and the required deployment confirmation. Continue from the
returned cursor until `isDone=true`.

The apply operation must:

1. Target active staff only.
2. Delete every `staffDeviceBindings` row for each targeted staff member,
   including duplicates.
3. Deactivate that staff member's mobile sessions so stale sessions cannot
   restore an old identity.
4. Preserve web sessions, passwords, profiles, attendance, CP/SV/Joint CP,
   tracking history, IAM, uploads, and all business data.
5. Write an audit record containing actor, reason, counts, and timestamp.

After the reset, no active staff member should have a binding row. The first
successful verified mobile login creates exactly one new row using the current
Play/TestFlight identity.

## Mobile authentication routes

### `POST /api/auth/login-with-employee-id`

Request:

```json
{
  "employeeId": "22026",
  "password": "staff-password",
  "deviceType": "mobile",
  "deviceId": "opaque-platform-device-id",
  "devicePlatform": "android",
  "deviceModel": "Manufacturer Model",
  "batteryPct": 72,
  "appVersion": "1.0",
  "appBuild": 71
}
```

Successful response:

```json
{
  "success": true,
  "token": "mobile-session-token",
  "user": {
    "id": "staff-id",
    "name": "Staff Name",
    "phone": "9876543210"
  }
}
```

Rules after the one-time reset:

- Valid credentials plus no binding: atomically create the binding and session.
- Valid credentials plus exact device match: allow login and refresh telemetry.
- Invalid credentials: return structured HTTP 401 without binding information.
- Staff bound to a different usable ID: return HTTP 401 with
  `DEVICE_BOUND_TO_OTHER_DEVICE`.
- Incoming device owned by another staff member: return HTTP 409 with
  `DEVICE_BOUND_TO_ANOTHER_ACCOUNT`.
- Never return HTML or a success body whose `token` or `user` is missing.

Different-device response:

```json
{
  "success": false,
  "code": "DEVICE_BOUND_TO_OTHER_DEVICE",
  "error": "This account is linked to another device. Contact admin to change the registered device."
}
```

### `POST /api/auth/send-otp`

Request:

```json
{
  "phone": "9876543210",
  "deviceType": "mobile",
  "deviceId": "opaque-platform-device-id",
  "devicePlatform": "android",
  "deviceModel": "Manufacturer Model",
  "appVersion": "1.0",
  "appBuild": 71
}
```

Return HTTP 200 only after the OTP is accepted for delivery. If the incoming
device is already owned by another staff account, return HTTP 409 with
`DEVICE_BOUND_TO_ANOTHER_ACCOUNT` before sending the OTP.

### `POST /api/auth/verify-otp`

Request:

```json
{
  "phone": "9876543210",
  "otp": "123456",
  "deviceType": "mobile",
  "deviceId": "opaque-platform-device-id",
  "devicePlatform": "ios",
  "deviceModel": "iPhone",
  "batteryPct": 72,
  "appVersion": "1.0",
  "appBuild": 71
}
```

After a valid OTP, apply exactly the same atomic binding decisions and response
codes as Employee-ID login. An invalid or expired OTP must not create or modify
a binding.

### `POST /api/push/register`

Authorization: `Bearer <active mobile session token>`

```json
{
  "token": "push-token",
  "platform": "android",
  "provider": "fcm",
  "bundleId": "com.manjugroups.mconnect",
  "deviceId": "opaque-platform-device-id",
  "deviceModel": "Manufacturer Model"
}
```

This route must use the same device ID supplied during login. It may create a
missing binding or refresh an exact match. It must not restore an old cleared
binding from stale session data, claim a device owned by another staff member,
or invalidate a valid MMS session merely because push registration failed.

### `GET /api/auth/validate-session`

Authorization: `Bearer <mobile session token>`

Return HTTP 200 for an active authoritative MMS session and HTTP 401 only for
an actually invalid/expired MMS session. Secondary-service failures must not
cause this endpoint or the mobile client to clear the session.

### `POST /api/auth/logout`

Authorization: `Bearer <mobile session token>`

Deactivate the current mobile session and push registration. Normal logout must
not delete the device binding; the same account must be able to sign back in on
the same device.

## Admin support routes

### `GET /api/hr/staff/security?staffId=<staff-id>`

Authorization and IAM permission `staff.resetDeviceBinding` are required.
Return redacted binding state, platform/model, bound time, last-seen time, and
active mobile-session count. Never return the full device ID or session token.

### `POST /api/hr/staff/device-reset`

Authorization and IAM permission `staff.resetDeviceBinding` are required.

```json
{
  "staffId": "staff-id"
}
```

Required response:

```json
{
  "success": true,
  "cleared": 1,
  "mobileSessionsSignedOut": 1
}
```

The route must clear all binding rows for only the selected staff member and
deactivate only that staff member's mobile sessions. It is the controlled
fallback after the fleet migration, not a public login route.

## Persistence invariants

Use `staffDeviceBindings` with unique lookup paths for both directions:

```text
by_staffId(staffId)
by_deviceId(deviceId)
```

- Store the full opaque ID; never convert it to a short number.
- Never use MAC address, model name, FCM/APNs token, IP address, or GeoTrack UUID
  as the authentication binding ID.
- Reject empty and known placeholder values before writing.
- Binding creation and session creation must be one atomic transaction.
- A reset must remove duplicate rows and stale mobile sessions completely.

## Deployment and verification order

### Phase 1: compatibility deployment

1. Deploy the corrected backend in `compatibility` mode.
2. Do not run the fleet reset and do not enable a global minimum build.
3. Verify existing APK users remain logged in, can sync, and can log out/relogin
   when their current binding matches.
4. Confirm requests without `appBuild` remain backward compatible.

### Phase 2: Play pilot

1. Select a small set of disposable or approved pilot staff accounts.
2. Change only those accounts to `pilot` handling and require Android build 71.
3. Reset only the pilot accounts using the existing authorized per-staff reset.
4. Install the app from the real Play testing/production track, not a locally
   signed APK.
5. Verify first login, binding creation, logout/relogin on the same phone, app
   restart, authenticated API loading, and rejection from a second phone.
6. Existing non-pilot APK users continue normally throughout this phase.

### Phase 3: fleet cutover after pilot approval

1. Confirm the Play build passed the pilot and is available to all intended
   users.
2. Switch Android to `enforced` with minimum build 71.
3. Run all fleet reset pages with `dryRun=true` and approve the totals.
4. Run the confirmed apply pages until `isDone=true`.
5. Verify zero active-staff binding rows remain and affected mobile sessions are
   inactive.
6. Staff then update and complete the first login, which creates exactly one new
   Play-scoped binding.
7. Verify same-device relogin succeeds and a different device is rejected.
8. Run the mobile API checker against the final deployed admin handoff.

Safe contract checks that do not create a session, send an OTP, or modify a
binding:

```powershell
node scripts/check-mobile-api.mjs contracts
node scripts/check-mobile-api.mjs device-login-contracts
node scripts/check-mobile-api.mjs device-rollout-contracts --rollout-mode compatibility
node scripts/check-mobile-api.mjs auth-recovery-contracts
```

After pilot approval and global minimum-build activation, rerun:

```powershell
node scripts/check-mobile-api.mjs device-rollout-contracts --rollout-mode enforced
```

Successful end-to-end login and rebinding verification requires a disposable
test account and must be run only after the one-time reset. Never run that write
test against a staff member's production account.

## Release acceptance criteria

- First post-reset login succeeds from the Play/TestFlight-installed app.
- Builds below the configured minimum cannot recreate a cleared binding.
- Existing non-pilot APK users remain operational during compatibility and
  pilot verification.
- Backend deployment alone never resets a binding or deactivates a session.
- Same-device logout and relogin succeeds.
- A second physical device is blocked after the first device claims the account.
- There is no `Verify this device` or self-reset control in the mobile UI.
- Invalid credentials remain invalid and do not reveal binding information.
- No CP, SV, Joint CP, attendance, tracking, upload, IAM, or web-session data is
  changed by the migration.
