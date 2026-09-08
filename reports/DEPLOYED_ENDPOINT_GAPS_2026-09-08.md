# Deployed Endpoint Gaps - 2026-09-08

This is a new gaps-only document. Existing endpoint handoff documents were not
modified.

## 1. Enforce The Minimum Build Before Authentication Work

Affected routes:

- `POST /api/auth/send-otp`
- `POST /api/auth/verify-otp`
- `POST /api/auth/login-with-employee-id`

Production app-version configuration says Android and iOS build `71` are the
minimum. However, an Android build `70` Employee-ID request returned ordinary
HTTP 401 invalid credentials, and a build `70` send-OTP request returned
ordinary HTTP 400 validation. Both should return this before OTP lookup,
credential lookup, session creation, or device-binding mutation:

```http
HTTP/1.1 426 Upgrade Required
Content-Type: application/json
```

```json
{
  "success": false,
  "code": "UPDATE_REQUIRED",
  "error": "Please update M-connect to continue.",
  "minimumBuild": 71
}
```

Reason: without this gate, an older APK can reclaim a binding after the fleet
reset and recreate the Play-signing identity conflict.

## 2. Fix Production Auth Latency

No new mobile endpoint is needed, but the deployed auth/runtime path must be
made consistently faster than the Android client's `30s` read timeout.
Observed harmless requests took up to `61.3s`; one send-OTP validation took
`54.4s`. This directly produces the app's `Network connection timed out`
message even when the user's internet is working.

Required backend checks:

- inspect cold-start/runtime saturation and database connection setup;
- inspect middleware that runs before all `/api/auth/*` handlers;
- add server timing for build validation, staff lookup, binding lookup, and
  session creation;
- keep p95 comfortably below `10s` and never near the `30s` mobile timeout;
- confirm timeout/error responses do not create partial sessions or bindings.

## 3. Configure The iOS Update Destination

`GET /api/mobile/app-version?platform=ios...` reports minimum build `71` but
returns `updateUrl: null`. Configure the intended App Store or TestFlight URL
before enforcing a mandatory iOS update so users have a valid upgrade path.

## 4. Implement Or Route Authenticated Storage File Reads

Expected route:

```http
GET /api/storage/files/{storageId}
Authorization: Bearer <mobile-session-token>
```

The contract probe currently returns HTTP 404 before authentication; the other
three storage routes return structured HTTP 401. Add the route or document and
configure the replacement authenticated read URL used after upload completion.

## 5. Provide Safe Verification Access For The Mutating Rollout

The public probes cannot safely prove these operations without authorized test
fixtures:

- audited dry-run and confirmed fleet device-binding reset;
- first build-71 login creating exactly one binding;
- same-device logout/relogin reuse;
- different-device stable conflict response;
- full Joint CP OTP/photo/outcome/reviewer completion for both participants.

Provide a disposable staff pair and operator-approved test reset, or return the
redacted dry-run/apply audit output. Do not use a real staff account for these
write-path checks.
