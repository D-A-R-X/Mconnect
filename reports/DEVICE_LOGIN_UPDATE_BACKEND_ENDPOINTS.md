# Device login update: backend endpoint handoff

> **Incident rollout update (2026-09-08):** For the current fleet-wide
> Play-signing identity mismatch, follow
> `PLAYSTORE_DEVICE_BINDING_LOGIN_ENDPOINTS.md`. Its audited, one-time
> active-staff reset supersedes this document's earlier gradual-migration-only
> rollout. The strict one-account/one-device rule resumes as soon as each staff
> member completes the first post-reset login.

## Goal

After the Android and iOS update, every legitimate staff device must be able to
continue its current session or sign in again without false "bound to another
device" errors. The security rule remains one staff account per active mobile
device identity and one device identity per staff account.

This rollout must not clear all bindings, invalidate valid sessions, or allow
one phone to claim two staff accounts.

The mobile login screen must never show a `Verify this device`, `Reset device`,
or self-rebind action. Device changes are handled by an administrator.

## Device identity supplied by the apps

- Android sends its app/signing/user-scoped `ANDROID_ID`. It is stored outside
  session preferences, survives logout, and is fetched again after reinstall.
- iOS sends a UUID kept in a `ThisDeviceOnly` Keychain item. It survives normal
  logout and normally survives reinstall on the same device.
- Neither platform uses a MAC address. Modern Android and iOS do not expose a
  stable hardware MAC to ordinary apps.
- The value is opaque. The backend must compare the full exact string and must
  not convert it to a short numeric code.

## 1. Pre-OTP device ownership check

### `POST /api/auth/send-otp`

Request from Android/iOS:

```json
{
  "phone": "9876543210",
  "deviceType": "mobile",
  "deviceId": "opaque-platform-device-id",
  "devicePlatform": "android",
  "deviceModel": "Manufacturer Model"
}
```

Allowed response:

```json
{
  "success": true,
  "message": "OTP sent to your phone"
}
```

If the same usable `deviceId` is already bound to another staff account, return
this before creating or delivering an OTP:

```http
HTTP/1.1 409 Conflict
```

```json
{
  "success": false,
  "code": "DEVICE_BOUND_TO_ANOTHER_ACCOUNT",
  "error": "This device is already linked to SARA.R. Sign in with that account or contact admin.",
  "boundAccountName": "SARA.R"
}
```

The mobile UI uses the stable `code`; it must not infer this case from text.
Do not return phone numbers, employee IDs, tokens, binding IDs, or device IDs.
Apply phone, device, and IP rate limits because this is a public route.

## 2. Authoritative OTP verification and first binding

### `POST /api/auth/verify-otp`

```json
{
  "phone": "9876543210",
  "otp": "123456",
  "deviceType": "mobile",
  "deviceId": "opaque-platform-device-id",
  "devicePlatform": "ios",
  "deviceModel": "iPhone15,2",
  "batteryPct": 72
}
```

After validating the OTP, apply this transactionally:

1. No binding for the staff and no other owner for the device: create binding.
2. Staff binding exactly matches: allow login and refresh `lastSeenAt`.
3. Existing binding is an unusable legacy placeholder: replace it with the
   incoming ID and allow login.
4. Incoming ID belongs to another staff: return HTTP 409 with
   `DEVICE_BOUND_TO_ANOTHER_ACCOUNT`.
5. Staff is bound to another valid ID: return HTTP 401 with
   `DEVICE_BOUND_TO_OTHER_DEVICE` and the existing verified-OTP recovery token.

Successful response remains the existing session contract:

```json
{
  "success": true,
  "token": "session-token",
  "user": {}
}
```

## 3. Employee ID login

### `POST /api/auth/login-with-employee-id`

```json
{
  "employeeId": "22026",
  "password": "staff-password",
  "deviceType": "mobile",
  "deviceId": "opaque-platform-device-id",
  "devicePlatform": "android",
  "deviceModel": "Manufacturer Model",
  "batteryPct": 72
}
```

Use the same five binding decisions and stable error codes as OTP verification.
Validate credentials before revealing any account-to-device binding result.
Never let this route bypass the OTP route's one-device rule.

### Required database comparison and UI response

After credentials are valid, compare the incoming `deviceId` with the binding
stored for the resolved staff ID:

| Database state | Backend result | Mobile behavior |
| --- | --- | --- |
| No staff binding and device ID is unowned | Bind it and return success | Continue login |
| Stored staff device ID exactly matches incoming ID | Return success | Continue login |
| Stored row is a recognized unusable legacy ID | Repair it and return success | Continue login |
| Staff has a different usable device ID | HTTP 401 and `DEVICE_BOUND_TO_OTHER_DEVICE` | Show linked-to-another-device message only |
| Incoming ID belongs to another staff account | HTTP 409 and `DEVICE_BOUND_TO_ANOTHER_ACCOUNT` | Show linked-to-another-account message only |

Required different-device response:

```json
{
  "success": false,
  "code": "DEVICE_BOUND_TO_OTHER_DEVICE",
  "error": "This account is linked to another device. Contact admin to change the registered device."
}
```

Android and iOS must remain on the login screen. They must not request a
recovery OTP, show a verification button, call a recovery confirmation route,
or replace the binding.

## 4. Safe migration for already logged-in updated apps

### `POST /api/push/register`

Authorization: `Bearer <active-mobile-session-token>`

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

This existing authenticated app-start request is the migration mechanism:

- missing binding: create it;
- exact match: refresh telemetry;
- unusable legacy binding: repair it;
- changed ID plus a valid, active, non-impersonated mobile session for the same
  staff: repair it without ending the session;
- incoming ID owned by another staff: leave both bindings and the active session
  unchanged; record a redacted audit event;
- missing, expired, web, inactive, other-staff, or impersonated session proof:
  do not move the binding.

This route must never clear a session or return a session-expired response just
because device migration was skipped.

## 5. Legitimate device-change recovery

Keep the implemented recovery routes:

- `POST /api/auth/device-binding/recovery/request`
- `POST /api/auth/device-binding/recovery/confirm`
- `POST /api/auth/device-binding/recovery/confirm-verified-otp`

These routes are retained only for a controlled admin/support workflow and are
not exposed as actions in the Android or iOS login UI. The ordinary mobile flow
must stop on `DEVICE_BOUND_TO_OTHER_DEVICE` and instruct the staff member to
contact admin.

Any controlled recovery must require valid authorization and audit the actor.
On success it may replace only the selected staff binding. It must not allow a
device identity currently owned by another staff account to be claimed.

## Database requirements

Use a `staffDeviceBindings` record containing at least:

```text
staffId, deviceId, platform, deviceModel, boundAt, lastSeenAt
```

Required indexes:

- `by_staffId(staffId)`
- `by_deviceId(deviceId)`

Enforce both uniqueness directions in mutation logic. Existing duplicate rows
must be audited and resolved by an administrator; do not choose an owner merely
from phone model or IP address.

Treat these IDs as unusable and never bind them:

```text
empty, unknown-device, unknown, null, undefined, 9774d56d682e549c
```

The known legacy lowercase random UUID stored by the removed Android tracking
capture path may be repaired on the next authenticated mobile request.

## Deployment order

1. Deploy the backend additions first. All request fields are optional, so old
   mobile builds and web clients keep their current behavior.
2. Verify the three auth routes and recovery routes in staging.
3. Release Android and iOS updates.
4. Let valid active sessions self-migrate through `/api/push/register`.
5. Monitor conflicts and repair only confirmed legacy records.

Do not run `clearAllStaffDeviceBindings` or a batch reset during rollout. Those
operations remove valid protection and can force staff to authenticate again.

## Acceptance matrix

1. Existing logged-in Android/iOS app updates: remains logged in and registers
   the same or safely migrated ID.
2. Same account, same device, after logout: login succeeds.
3. Same account, same device, after reinstall: stable platform identity is
   fetched and login succeeds when the OS identity is unchanged.
4. Unbound account: first verified mobile login binds and succeeds.
5. Legacy placeholder/stale row: self-repairs and succeeds.
6. Same account on a genuinely different phone: login is blocked with
   `DEVICE_BOUND_TO_OTHER_DEVICE`; no Verify button is shown.
7. Different account on an already owned phone: blocked before OTP and again at
   authoritative credential verification.
8. Web login behavior is unchanged.
9. No valid active mobile session is invalidated by app-start migration.
10. CP, SV, Joint CP, attendance, tracking, uploads, and session invalidation
    behavior are unchanged by these auth-only endpoints.
11. Neither Android nor iOS exposes a self-service device verification or
    binding-reset action.

## Current implementation status

The Android and iOS request/response contracts and the Convex endpoint logic
are implemented in local source. Backend deployment and production verification
are still required before the pre-OTP warning or migration behavior is live.
