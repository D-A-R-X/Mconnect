# Mobile Login and Same-Device Recovery API Handoff

Date: 2026-09-07

## Purpose

This document contains only the web/backend API work required to restore
Android and iOS login when a staff member's real phone is rejected by the
single-device binding check.

It covers:

- Employee-ID/password recovery.
- Mobile-number/OTP recovery.
- Stable binding error responses.
- Repair of legacy binding rows created with a GeoTrack UUID.
- Admin visibility and manual reset.

It must not change attendance, CP, SV, Joint CP, tracking history, Dialer data,
IAM, staff profiles, passwords, or offline business records.

## Production Host

```text
https://api-mfpl.theairix.com/
```

All JSON responses must use `Content-Type: application/json`.

## Endpoint Status Summary

| Method and route | Status | Backend action |
| --- | --- | --- |
| `POST /api/auth/device-binding/recovery/request` | Reported deployed | Verify the exact contract below and retain it. |
| `POST /api/auth/device-binding/recovery/confirm` | Reported deployed | Verify the exact contract below and retain it. |
| `POST /api/auth/login-with-employee-id` | Update required | Return the stable device-binding code and diagnostics-safe message. |
| `POST /api/auth/verify-otp` | Update required | Return the same stable binding code after a valid OTP encounters a mismatch. |
| `POST /api/auth/device-binding/recovery/confirm-verified-otp` | New, required for OTP-only recovery | Complete an explicit rebind using a short-lived proof produced by successful OTP verification. |
| `POST /api/hr/staff/device-binding/repair-legacy` | New admin/internal operation | Dry-run and repair only provably unusable legacy binding rows. |
| `POST /api/push/register` | Reported updated | Keep optional `bindingStatus`; never log out a session because of a launch-time mismatch. |
| `GET /api/hr/staff/security` | Existing | Return binding status and audit-safe metadata to authorized admins. |
| `POST /api/hr/staff/device-reset` | Existing | Keep as the permission-gated manual fallback for one staff member. |

## 1. Employee Credential Recovery Request

### Route

```http
POST /api/auth/device-binding/recovery/request
Content-Type: application/json
```

This is a public pre-login route. It must not require or trust an existing
bearer session.

### Request

```json
{
  "employeeId": "<employee-id>",
  "password": "<password>",
  "deviceId": "<android-id-or-ios-keychain-id>",
  "devicePlatform": "android",
  "deviceModel": "<manufacturer-and-model>",
  "attestationToken": "<optional-platform-attestation>"
}
```

`devicePlatform` is `android` or `ios`. `attestationToken` is optional until
Play Integrity/App Attest verification is available.

### Accepted Response

```json
{
  "success": true,
  "challengeId": "<opaque-single-use-id>",
  "expiresInSeconds": 300,
  "delivery": {
    "channel": "sms",
    "maskedDestination": "******1234"
  }
}
```

The OTP must be sent only to the phone already registered on the staff record.
The caller must not choose the destination.

### Required Security

- Verify Employee ID and password before creating a real challenge.
- Do not reveal whether an Employee ID exists.
- Store only OTP/challenge hashes.
- Expire after five minutes.
- Rate-limit by staff, device, IP, and destination.
- Bind the challenge to staff, device ID, platform, and attestation hash when
  supplied.
- Wrong credentials must create no active challenge and send no OTP.

## 2. Employee Credential Recovery Confirmation

### Route

```http
POST /api/auth/device-binding/recovery/confirm
Content-Type: application/json
```

### Request

```json
{
  "challengeId": "<opaque-single-use-id>",
  "otp": "123456",
  "deviceId": "<same-id-used-for-request>",
  "devicePlatform": "android",
  "deviceModel": "<same-device-model>",
  "attestationToken": "<same-optional-attestation-context>"
}
```

### Success Response

```json
{
  "success": true,
  "recovered": true,
  "bindingStatus": "recovered",
  "token": "<new-mobile-session-token>",
  "mustChangePassword": false,
  "user": {
    "_id": "<staff-id>",
    "employeeId": "<employee-id>",
    "name": "<staff-name>",
    "phone": "<registered-phone>",
    "role": "<role>",
    "designation": "<designation>",
    "department": "<department>",
    "status": "active"
  }
}
```

The `user` object must preserve the fields already returned by the normal
mobile login response.

### Failure Response

```json
{
  "success": false,
  "recovered": false,
  "code": "DEVICE_RECOVERY_FAILED",
  "error": "Recovery verification failed"
}
```

Use the same public failure for wrong, expired, replayed, locked, or
device-mismatched challenges. Limit a challenge to five failed attempts.

### Atomic Success Transaction

In one database transaction:

1. Verify and consume the challenge.
2. Preserve the original binding `boundAt`.
3. Move the binding to the confirmed device ID.
4. Set `reboundAt` and increment `rebindCount`.
5. Deactivate prior mobile sessions for this staff only.
6. Create exactly one new mobile session.
7. Write an immutable security audit event.

Web sessions and all business/module data must remain untouched.

## 3. Stable Binding Error on Normal Login

Both normal mobile login routes must return the same machine-readable code
when valid authentication is blocked only by device binding.

Affected routes:

```text
POST /api/auth/login-with-employee-id
POST /api/auth/verify-otp
```

### Required Response

HTTP `409` is preferred. HTTP `401` is acceptable during backward-compatible
rollout, but the JSON `code` is mandatory.

```json
{
  "success": false,
  "code": "DEVICE_BOUND_TO_OTHER_DEVICE",
  "error": "This account is already locked to another device."
}
```

Do not use this code for invalid credentials, invalid OTP, expired OTP,
inactive staff, missing fields, network failures, or server errors.

The Android and iOS clients also recognize the older canonical binding message
during rollout, but the stable code must be the long-term contract.

## 4. OTP-Only Same-Device Recovery

OTP delivery currently proves that the registered phone is reachable, but a
valid OTP can still be rejected by a stale device binding. Staff who do not
have a working Employee-ID password need an explicit OTP recovery path.

### Update `POST /api/auth/verify-otp`

After the OTP is valid but before creating a session, if device binding fails,
return a short-lived recovery proof:

```json
{
  "success": false,
  "code": "DEVICE_BOUND_TO_OTHER_DEVICE",
  "error": "This account is already locked to another device.",
  "recoveryToken": "<opaque-single-use-token>",
  "recoveryExpiresInSeconds": 300
}
```

The token must be hashed at rest and bound to the verified staff, phone,
attempted device ID, platform, and optional attestation context. It is not a
session and cannot authorize any other endpoint.

### New Route

```http
POST /api/auth/device-binding/recovery/confirm-verified-otp
Content-Type: application/json
```

### Request

```json
{
  "recoveryToken": "<token-from-valid-otp-response>",
  "deviceId": "<same-device-id-used-for-otp-verification>",
  "devicePlatform": "android",
  "deviceModel": "<same-device-model>",
  "attestationToken": "<same-optional-attestation-context>"
}
```

### Success

Return the same success body and execute the same atomic transaction defined
for `/api/auth/device-binding/recovery/confirm`.

### Security

- Never issue `recoveryToken` until the submitted OTP is valid.
- Consume it once only.
- Reject device/platform/attestation mismatch.
- Do not request or send a second OTP.
- Do not silently rebind during ordinary OTP verification. The user must
  explicitly confirm recovery in the app.

## 5. Legacy Binding Repair Operation

This operation resolves the fleet-wide case where old code stored a GeoTrack
tracking UUID instead of the login device identity.

### Route

```http
POST /api/hr/staff/device-binding/repair-legacy
Authorization: Bearer <authorized-admin-session>
Content-Type: application/json
```

Required IAM permission:

```text
staff.resetDeviceBinding
```

This may instead be implemented as a protected internal migration if it must
not be exposed through HTTP.

### Request

```json
{
  "dryRun": true,
  "batchSize": 100,
  "cursor": null
}
```

### Response

```json
{
  "success": true,
  "dryRun": true,
  "scanned": 100,
  "legacyBindingsMatched": 12,
  "legacyBindingsCleared": 0,
  "validBindingsSkipped": 88,
  "nextCursor": "<opaque-cursor-or-null>"
}
```

### Hard Rules

- Run and review `dryRun: true` first.
- Match only provably unusable legacy identifiers, such as an Android binding
  containing the historical lowercase dashed GeoTrack UUID shape.
- Never clear a valid 16-character Android ID.
- Never clear a valid iOS Keychain device ID.
- Never infer same-device ownership from model, IP address, phone number, push
  token, or password alone.
- Do not deactivate web sessions or delete module data.
- Audit every cleared row with old identifier hash, staff ID, actor, reason,
  timestamp, and migration batch ID.
- After a bad row is cleared, the next successful verified mobile login binds
  the current login device ID normally.

## 6. Push Registration Contract

```http
POST /api/push/register
Authorization: Bearer <active-mobile-session>
Content-Type: application/json
```

The request already includes the push token, platform, provider, bundle ID,
app ID/name, login device ID, and model.

Keep this optional response field:

```json
{
  "success": true,
  "deviceTokenId": "<row-id>",
  "bindingStatus": "ok"
}
```

Allowed values:

```text
bound
ok
authenticated_migration
mismatch_ignored
skipped
```

A launch-time mismatch must never invalidate or clear a mobile session. Only
an active, unexpired, non-impersonated mobile bearer belonging to the same
staff may perform `authenticated_migration`.

## 7. Web Admin Requirements

The existing staff Security screen should show:

- Whether a binding exists.
- Platform and device model.
- Masked or truncated device ID, never the full identifier to ordinary users.
- `boundAt`, `lastSeenAt`, `reboundAt`, and `rebindCount`.
- Last failed-login attempted model and timestamp.
- A permission-gated **Reset mobile device** action for one staff member.
- Legacy-repair dry-run counts and audited batch execution for authorized
  administrators only, if the repair operation is exposed in web UI.

Do not add a normal staff-facing button that clears a binding without password
or registered-phone verification.

## 8. Error Codes

| Code | Meaning |
| --- | --- |
| `DEVICE_BOUND_TO_OTHER_DEVICE` | Authentication succeeded but device binding blocked session creation. |
| `DEVICE_RECOVERY_FAILED` | Generic recovery confirmation failure. |
| `DEVICE_RECOVERY_RATE_LIMITED` | Too many recovery requests or attempts. |
| `DEVICE_RECOVERY_EXPIRED` | Optional internal/diagnostic code; public response may remain generic. |
| `DEVICE_RECOVERY_UNAVAILABLE` | Account has no usable registered recovery destination. |
| `INVALID_CREDENTIALS` | Employee ID/password is wrong; must not expose recovery. |
| `INVALID_OTP` | OTP is wrong; must not expose a binding recovery proof. |

## 9. Acceptance Tests

1. Correct Employee ID/password and matching device logs in normally.
2. Correct OTP and matching device logs in normally.
3. Wrong password never creates a recovery challenge or sends OTP.
4. Wrong OTP never creates a recovery proof.
5. Valid credentials plus a mismatched valid binding return
   `DEVICE_BOUND_TO_OTHER_DEVICE` without creating a session.
6. Recovery request sends OTP only to the registered phone.
7. Recovery confirmation rejects wrong device, expiry, sixth failed attempt,
   and replay.
8. Successful recovery returns one usable mobile token and preserves web
   sessions and all business records.
9. A second phone remains blocked after the genuine phone logs in.
10. Legacy repair dry-run matches only known bad identifier shapes.
11. Legacy repair execution leaves valid Android and iOS bindings unchanged.
12. A repaired original phone can bind and log in on its next verified attempt.
13. `/api/push/register` mismatch never logs out an existing user.
14. Android and iOS receive equivalent response shapes and error codes.

## 10. Deployment Order

1. Add stable binding codes to both normal login routes.
2. Verify the two deployed Employee-ID recovery endpoints against this wire
   contract.
3. Deploy OTP-only recovery proof and confirmation.
4. Deploy the capture-only push binding behavior.
5. Run legacy repair in dry-run mode and review counts/samples.
6. Execute the audited legacy repair in bounded batches.
7. Test matching-device, second-device, Employee-ID recovery, and OTP recovery
   using dedicated QA accounts.
8. Release Android and iOS only after fresh production login passes on both.

## Explicitly Out of Scope

- Website UI unrelated to the staff Security screen.
- CP, SV, Joint CP, attendance, GeoTrack history, Dialer, IAM templates, or
  storage APIs.
- Automatic acceptance based only on matching device model or IP.
- Bulk deletion of every valid staff binding.
