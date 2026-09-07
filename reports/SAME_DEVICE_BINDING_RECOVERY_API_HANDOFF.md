# Same-Device Binding Recovery API Handoff

## Decision

No new public endpoint is required.

The mobile apps already call the authenticated endpoint below on app launch:

```http
POST /api/push/register
Authorization: Bearer <current-mobile-session-token>
Content-Type: application/json
```

That request already provides the server with both pieces needed for a safe
same-device repair:

1. An active server-issued mobile session for the staff account.
2. The device identifier currently reported by the installed app.

Creating an unauthenticated "recover device" endpoint would weaken the
single-device restriction. Recovery must remain inside the existing
authenticated push-registration flow.

## Existing Request

Representative Android payload:

```json
{
  "token": "<fcm-push-token>",
  "platform": "android",
  "provider": "fcm",
  "bundleId": "com.manjugroups.mconnect",
  "appId": "com.manjugroups.mconnect",
  "appName": "M-connect",
  "deviceId": "8f0ca9c1750b7943",
  "deviceModel": "OPPO CPH2603"
}
```

Representative iOS payload uses `platform: "ios"`, provider `apns`, and the
Keychain-persisted Mconnect device identifier.

The bearer token is the recovery proof. It must never be accepted from a body
field or query parameter.

## Required Backend Behavior

After normal bearer authentication and push-token registration, call the
existing internal device-binding capture with:

```ts
{
  staffId: authenticatedUser._id,
  sessionToken: authenticatedBearerToken,
  deviceId: body.deviceId,
  platform: body.platform,
  deviceModel: body.deviceModel,
  ip: requestIp
}
```

Apply the following decision table:

| Existing binding | Incoming device | Session proof | Result |
| --- | --- | --- | --- |
| None | Valid ID | Valid authenticated request | Create binding |
| Same ID | Valid ID | Valid authenticated request | Refresh telemetry |
| Legacy placeholder | Valid ID | Any identified login/capture | Repair legacy binding |
| Different ID | Valid ID | Active mobile session for the same staff | Migrate binding |
| Different ID | Valid ID | Missing/expired/inactive/web token | Leave binding unchanged |
| Different ID | Valid ID | Token belongs to another staff | Leave binding unchanged |
| Different ID | Valid ID | Impersonated session | Leave binding unchanged |

An authenticated migration updates only:

- `deviceId`
- `platform`
- `deviceModel`
- `ip`
- `boundAt`
- `lastSeenAt`

It must not:

- deactivate the valid mobile session;
- clear any attendance, CP, SV, tracking, dialer, or offline-sync state;
- change staff permissions or profile data;
- trust device model, IP address, battery level, or push token by themselves;
- allow a public login request to replace a nonmatching binding.

## Session Validation

Before migration, load the bearer token from the server's `sessions` table and
require every condition below:

```text
session exists
session.active == true
session.expiresAt >= current server time
session.deviceType == "mobile"
session.staffId == authenticated staff id
session.impersonatedStaffId is absent
```

If any check fails, return the existing capture result
`mismatch_ignored`. Do not mutate the binding and do not terminate a session.

## Response Contract

The public response remains unchanged:

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "success": true,
  "deviceTokenId": "<registered-push-device-row-id>"
}
```

The internal capture result may be `authenticated_migration` for audit and
tests. Mobile does not need to parse that internal value, so Android and iOS
remain backward compatible.

## Login Endpoints

No request or response change is needed for either login endpoint:

```http
POST /api/auth/verify-otp
POST /api/auth/login-with-employee-id
```

They must retain strict binding enforcement. A login carrying a device ID that
does not match a valid stored binding is still rejected unless the binding was
previously repaired through the authenticated launch flow or reset by an
authorized administrator.

## Staff Already At The Login Screen

If the app no longer holds an active session token, the backend cannot safely
prove that a changed identifier belongs to the same physical phone. Do not
auto-rebind based only on matching model or IP.

Use the existing admin-only endpoint:

```http
POST /api/hr/staff/device-reset
Authorization: Bearer <admin-token-with-staff.resetDeviceBinding>
Content-Type: application/json

{
  "staffId": "<staff-id>"
}
```

After reset, the next successful mobile login binds the current device ID.

## Required Tests

1. A valid active same-staff mobile session migrates a changed ID.
2. The existing session remains active after migration.
3. Missing session proof cannot migrate the ID.
4. An inactive session cannot migrate the ID.
5. An expired session cannot migrate the ID.
6. A web session cannot migrate the ID.
7. Another staff member's session cannot migrate the ID.
8. An impersonated session cannot migrate the ID.
9. A matching ID still refreshes telemetry normally.
10. A public login from a genuine different device remains blocked.

## Deployment And Verification

1. Deploy the backend binding-helper change.
2. Keep one affected staff signed in on the currently registered phone.
3. Launch or foreground Mconnect so `/api/push/register` runs.
4. Confirm the push request returns HTTP 201.
5. Confirm the binding row now holds the incoming device ID and the same mobile
   session remains active.
6. Reauthenticate by OTP and Employee ID on that phone; both must succeed.
7. Attempt login from a separate test phone; it must still receive the device
   lock rejection.
8. Verify attendance, CP/SV, GeoTrack, dialer, and offline queues were not
   modified during recovery.

## Current Validation

The local backend implementation passed:

```text
convex/staffDeviceBindingPlaceholder.test.ts: 16 tests passed
convex/auth.test.ts: 10 tests passed
TypeScript: npx tsc --noEmit passed
ESLint: 0 errors (one pre-existing type-only helper warning)
```

Production route probes currently return structured validation responses for
both login routes. The recovery behavior cannot take effect in production until
the backend change is reviewed and deployed.
