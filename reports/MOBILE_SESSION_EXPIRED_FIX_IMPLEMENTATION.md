# MConnect Mobile Session Expiry Fix

Date: 2026-09-06
Platforms: Android and iOS
Status: Implemented locally, not yet pushed
Backend/API change: None required

## 1. Problem

Some staff were shown `Session expired` during login or immediately after a
successful login even though the newly issued mobile session was valid.

The mobile applications already prevented public login requests and most
secondary-service failures from invalidating the MMS session. One race still
remained:

1. The old session started one or more background requests.
2. The staff signed in and mobile saved a new token.
3. An old request completed late with HTTP 401.
4. The old request emitted a global session-expired event without identifying
   which token had failed.
5. Mobile cleared the newly saved token and returned the staff to login.

iOS also cleared its saved session when startup validation failed for any
reason, including temporary network, server or decoding errors.

## 2. Required Session Rule

A mobile session may be cleared automatically only when all of these are true:

1. The response is HTTP 401.
2. The failed request contained a non-empty Bearer token.
3. The request was sent to the authoritative MMS host:
   `https://api-mfpl.theairix.com`.
4. The token that received HTTP 401 is still the currently saved token.

If any condition is false, the operation may show its own error or retry, but
it must not clear the MMS login.

## 3. Public Login Behavior

These requests do not contain an authenticated session and must never publish
a global session-expired event:

```text
POST /api/auth/send-otp
POST /api/auth/verify-otp
POST /api/auth/login-with-employee-id
```

Their backend error must remain on the appropriate login screen. Examples:

```text
Invalid Employee ID or password
Invalid OTP. 2 attempts remaining.
This account is already locked to another device.
```

## 4. Android Implementation

Android HTTP watchdogs retain the existing checks for HTTP 401, a Bearer
header, the authoritative MMS host and the developer bypass token.

The global invalidation event now contains the exact token extracted from the
failed request. `MainActivity` compares that failed token with the current
`SessionManager.token` immediately before clearing the session.

Expected outcomes:

| Situation | Result |
|---|---|
| Current MMS token receives 401 | Clear once and return to login |
| Previous token receives a late 401 | Ignore; preserve new session |
| Login/public request receives 401 | Show login error only |
| Direct GeoTrack or another secondary host receives 401 | Operation failure only |
| Several current-token requests receive 401 together | First clears; later events are ignored |
| Missing, malformed or non-Bearer Authorization header | Never invalidate |

Updated Android areas:

```text
MainActivity.kt
auth/SessionInvalidationBus.kt
network/ApiService.kt
network/GeoTrackApi.kt
network/DailyLogApi.kt
network/TravelDeskApi.kt
```

Regression coverage is in:

```text
app/src/test/java/com/manjugroups/m_connect/auth/SessionInvalidationPolicyTest.kt
```

## 5. iOS Implementation

iOS now publishes a typed invalidation event containing the failed token and
the user-facing reason. The invalidation bus centrally verifies that the
request:

- targets `AppConfig.baseURL`;
- has a valid Bearer Authorization header; and
- failed using the token carried by that request.

`AuthRootView` compares the event token with `AuthStore.currentSession.token`.
Only a match calls `expireSession`.

Every iOS service that can publish a 401 invalidation now supplies its original
request. This includes auth, HR, marketing, chat, tasks, projects, issues,
fleet, front desk, daily log, post-sales, telecaller, staff security and the
MMS side of GeoTrack. Direct GeoTrack remains non-authoritative.

iOS startup restoration was also corrected:

| Validation result | Session behavior |
|---|---|
| HTTP 401 from MMS for current token | Clear session |
| DNS failure or offline state | Keep cached session |
| Timeout | Keep cached session |
| MMS HTTP 5xx | Keep cached session |
| Unexpected response/decoding failure | Keep cached session |
| Unreadable Keychain session payload | Clear unusable local payload |

## 6. Background Services

Background work must read the latest saved token when preparing an API call.
If work that already started with an older token finishes after login, its 401
is safely ignored because the failed token no longer matches the current one.

This protects attendance, GeoTrack, push registration, dashboard loading,
chat, CP/SV and other concurrent startup requests without suppressing a real
current-session expiry.

## 7. API Requirements

No new endpoint or response field is required.

Existing API behavior remains:

```http
GET /api/auth/validate-session
Authorization: Bearer <current-token>
```

An invalid or revoked current token should return HTTP 401 with a readable
error. A valid token should return HTTP 200 and the active user/session data.

The mobile clients continue using:

```text
MMS API: https://api-mfpl.theairix.com
Direct GeoTrack: https://api-geo.theairix.com
```

The direct GeoTrack host is not allowed to revoke the MMS session.

## 8. Validation Completed

Android validation:

```text
testDebugUnitTest: passed
assembleDebug: passed
Tests: 162
Failures: 0
Errors: 0
```

The public production health endpoint returned HTTP 200.

iOS validation completed on Windows:

- all invalidation producers pass the failed request;
- no untagged `SessionInvalidationBus.emit()` calls remain;
- all edited Swift files have balanced braces and parentheses;
- Git diff hygiene passed.

Native Xcode compilation and iPhone testing remain required on a Mac before
release.

## 9. Release Acceptance Checks

1. Invalid Employee ID/password shows the backend login error, not session
   expired.
2. Invalid OTP shows the OTP error and keeps the user on OTP verification.
3. Device-binding rejection shows the device message, not session expired.
4. A successful login saves the new token and opens the application normally.
5. Simulate a late HTTP 401 from the previous token after successful login;
   the new session remains active.
6. Revoke the current token on the backend; the app shows one session-expired
   message, stops session-owned background work and returns to login.
7. Trigger several current-token HTTP 401 responses concurrently; only one
   logout transition is visible.
8. Return HTTP 401 from direct GeoTrack while MMS remains valid; tracking may
   retry/fail, but the user stays logged in.
9. Start iOS offline with a previously valid cached session; temporary
   validation failure does not erase the session.
10. Restore connectivity and verify attendance, dashboard, CP/SV, chat and
    GeoTrack use the current token and synchronize normally.

## 10. Non-Regression Boundary

This fix does not change:

- login request or response payloads;
- backend session creation or single-device policy;
- CP, SV or Joint CP business rules;
- attendance or GeoTrack start/stop rules;
- dialer behavior;
- API base URLs;
- website/backend source or production data.

The change only determines whether a specific authenticated 401 is authorized
to clear the currently saved mobile session.
