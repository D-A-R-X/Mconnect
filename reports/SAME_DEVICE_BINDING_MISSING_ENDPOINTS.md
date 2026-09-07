# Same-Device Binding: Missing API Contracts

Date: 2026-09-07

> Superseded on 2026-09-07: both recovery endpoints described below are now
> deployed on the production MMS host and integrated into Android and iOS.
> This file is retained as the original contract. Current status is tracked in
> `reports/endpoints.md`.

## Purpose

This document contains only the backend/API work still missing after reviewing
`SAME_DEVICE_BINDING_RECOVERY_API_HANDOFF.md` against Android, iOS, and the
checked-out backend implementation.

The existing authenticated route below is useful after login, but it cannot
recover a freshly reinstalled Android app that has already been blocked at the
login screen because uninstalling normally removes the app's bearer session.

## Existing Routes (Do Not Duplicate)

| Method and route | Current purpose | Limitation |
| --- | --- | --- |
| `POST /api/push/register` | Registers a push token and may repair a changed device ID when the caller already has a valid same-staff mobile bearer session. | Cannot run before login and therefore cannot recover a fresh install that has no bearer token. |
| `POST /api/hr/staff/device-reset` | Authorized admin manually clears a staff device binding. | Requires admin intervention and is not automatic user recovery. |

## Missing 1: Request Device Recovery

### Endpoint

`POST /api/auth/device-binding/recovery/request`

### Authentication

This is a pre-login endpoint. It must not accept an ordinary bearer token as
the only proof. It must verify the user's normal login credential before
creating a recovery challenge.

### Request

```json
{
  "employeeId": "<employee-id>",
  "password": "<user-entered-password>",
  "deviceId": "<current-login-device-id>",
  "devicePlatform": "android",
  "deviceModel": "<manufacturer-and-model>",
  "attestationToken": "<optional-platform-attestation>"
}
```

For phone/OTP-only accounts, the backend may accept `phone` instead of
`employeeId` and password, but it must reuse the verified account's registered
phone number. The caller must never be allowed to choose the recovery number.

### Success Response

Return the same generic shape whether or not the account exists to prevent
employee/account enumeration.

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

If credentials are wrong, the public response should remain generic. The
server must not send an OTP or create an active recovery challenge.

### Required Server Rules

- Rate-limit by staff/account, IP, and device fingerprint.
- Challenge expires within five minutes and is single use.
- Store only a hash of the OTP and recovery secret.
- Maximum five verification attempts, followed by cooldown.
- Send OTP only to the phone already registered for the resolved staff.
- Record requested device ID, platform, model, IP, user agent, and request time.
- A device model, IP address, push token, password, or platform attestation by
  itself must never authorize rebinding.
- Do not deactivate the current binding or active sessions at request time.

## Missing 2: Confirm Device Recovery

### Endpoint

`POST /api/auth/device-binding/recovery/confirm`

### Request

```json
{
  "challengeId": "<opaque-single-use-id>",
  "otp": "123456",
  "deviceId": "<same-id-used-in-request>",
  "devicePlatform": "android",
  "deviceModel": "<manufacturer-and-model>",
  "attestationToken": "<optional-platform-attestation>"
}
```

### Success Response

The confirmation should finish login so the new installation does not need to
repeat the password request and race the device-binding check again.

```json
{
  "success": true,
  "recovered": true,
  "bindingStatus": "rebound_after_verified_recovery",
  "token": "<new-mobile-session-token>",
  "user": {
    "_id": "<staff-id>",
    "employeeId": "<employee-id>",
    "name": "<staff-name>"
  }
}
```

### Required Atomic Transaction

After validating the challenge, OTP, device identity, and optional attestation,
perform the following atomically:

1. Mark the challenge consumed.
2. Deactivate prior mobile sessions for that staff.
3. Move the binding to the challenge's exact device ID.
4. Preserve the original `boundAt`; add `reboundAt` and increment
   `rebindCount` instead of overwriting binding history.
5. Create one new active, non-impersonated mobile session.
6. Write a security audit event containing old/new device IDs, models, IP,
   reason `verified_reinstall_recovery`, and actor staff ID.
7. Notify the registered phone that the device binding changed.

The operation must fail without partial writes if any step fails.

### Error Contract

```json
{
  "success": false,
  "error": "Recovery verification failed",
  "code": "DEVICE_RECOVERY_FAILED"
}
```

Use stable codes such as:

- `DEVICE_RECOVERY_FAILED`
- `DEVICE_RECOVERY_EXPIRED`
- `DEVICE_RECOVERY_LOCKED`
- `DEVICE_RECOVERY_ATTESTATION_FAILED`

Do not reveal whether the employee ID, password, OTP, binding, or device ID was
the specific failing field in public responses. Put exact diagnostics only in
the protected audit log.

## Missing 3: Expose Push-Route Binding Result

Keep `POST /api/push/register` backward compatible, but add an optional
`bindingStatus` field so mobile QA and support can tell whether silent recovery
actually occurred.

```json
{
  "success": true,
  "deviceTokenId": "<registered-row-id>",
  "bindingStatus": "authenticated_migration"
}
```

Allowed values:

- `bound`
- `ok`
- `authenticated_migration`
- `mismatch_ignored`
- `skipped`

The current backend discards this internal result and always returns only the
push registration result.

## Missing 4: Correct Binding History Contract

The supplied handoff says authenticated migration preserves `boundAt`, but the
current backend assigns `boundAt = now` during migration. Choose and enforce
one contract:

- Recommended: preserve the first `boundAt`, add `reboundAt`, `rebindCount`,
  and an immutable security audit row.
- If resetting `boundAt` is intentional, correct the handoff and rename the
  displayed field so it is not presented as the original binding date.

Add a regression test that captures the previous `boundAt` and asserts it is
unchanged after an authenticated migration or verified recovery.

## Mobile Flow

1. Android/iOS attempts normal Employee-ID or OTP login with the platform's
   existing login device ID.
2. If the backend returns `DEVICE_BOUND_TO_OTHER_DEVICE`, show **Recover this
   device**. Do not loop login automatically.
3. The app calls the recovery-request endpoint using the same device ID.
4. The user enters the OTP received at the registered phone.
5. The app calls recovery-confirm using the same challenge and device ID.
6. On success, store the returned mobile session using the normal secure
   session path and continue through the standard post-login bootstrap.
7. CP, SV, attendance, GeoTrack, Dialer, and offline queues must not be cleared
   by recovery. Only previous mobile authentication sessions are invalidated.

## Acceptance Tests

- Fresh reinstall with a changed app-scoped ID can recover after valid
  credential plus registered-phone OTP verification.
- Wrong password creates no usable challenge and sends no OTP.
- Wrong, expired, replayed, or over-attempt OTP cannot change the binding.
- A challenge created for staff A cannot recover staff B.
- A challenge created for device A cannot be confirmed from device B.
- Web and impersonated sessions cannot call authenticated migration.
- Normal matching-device login remains unchanged.
- A genuine second phone remains blocked unless verified recovery completes.
- Recovery preserves attendance, CP/SV, tracking, Dialer, permissions, and
  offline business records.
- Previous mobile sessions are invalidated only after successful confirmation.
- `boundAt` history and the new recovery audit entry are correct.
- Android and iOS parse both old push-register responses and the optional
  `bindingStatus` field.

## Deployment Order

1. Add schema fields/audit storage and recovery challenge storage.
2. Add backend tests for request, confirmation, replay, expiry, rate limits,
   cross-staff/device misuse, and transaction rollback.
3. Deploy the backend endpoints and optional push response field.
4. Release Android and iOS recovery UI/handling.
5. Verify with a disposable test staff account and a real reinstall.
6. Keep the authorized admin device-reset route as the support fallback.

## Completion Status

These recovery endpoints and response additions are **not present** in the
currently checked-out backend. The existing `/api/push/register` repair remains
valid only for an app that already possesses a valid same-staff mobile session.
