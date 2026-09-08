# APK Login Immediate Compatibility Fix

Date: 2026-09-08

## Incident

Staff using the distributed APK are currently seeing one of these login
failures:

- `Update Mconnect to continue signing in.`
- `Network connection timed out. Check your internet and try again.`

These are two separate backend rollout problems:

1. The production app-version setting currently declares build `71` as the
   minimum, so every APK below build `71` is treated as unsupported.
2. Production requests were observed taking up to `61.3 seconds`, while the
   Android MMS client has a `30-second` read timeout.

The corrected local APK at `app/build/outputs/apk/debug/app-debug.apk` is now
version `1.0`, build `72`. An older APK distributed before build `71` can
receive the update-required dialog under the current production setting.

## Required Immediate Backend State

Temporarily place Android login in **compatibility mode** until the Play build
has been verified with a pilot account.

1. Find the version code of the APK currently distributed to staff.
2. Set Android `minimumSupportedBuildNumber` to that build or lower. If the
   distributed APK is build `70`, use `70`.
3. Set Android `latestBuildNumber` to `72` when the new Play release is ready.
4. Return `updateRequired: false` for the distributed APK build.
5. Do not return HTTP 426 from the three authentication routes for the
   temporarily supported APK build.
6. Do not run the fleet device-binding reset while compatibility mode is active.
7. Do not change iOS minimum-build settings as part of the Android emergency
   rollback.

Example temporary Android setting:

```json
{
  "platform": "android",
  "latestVersion": "1.0",
  "latestBuildNumber": 72,
  "minimumSupportedVersion": "1.0",
  "minimumSupportedBuildNumber": 70,
  "updateUrl": "https://play.google.com/store/apps/details?id=com.manjugroups.mconnect"
}
```

If staff use a build lower than `70`, replace `70` with the exact distributed
build number. Do not guess this value.

## Authentication Build Contract

Apply the same platform-specific compatibility decision to all three routes:

- `POST /api/auth/send-otp`
- `POST /api/auth/verify-otp`
- `POST /api/auth/login-with-employee-id`

The Android app sends `appVersion` and `appBuild` in each authentication JSON
body. The app-version request also sends `X-App-Version` and `X-App-Build`
headers. Backend authentication handlers must read the JSON fields; they must
not require auth headers that the released login client does not send.

While compatibility mode supports build `70`:

```json
{
  "appVersion": "1.0",
  "appBuild": 70,
  "devicePlatform": "android"
}
```

must continue to normal credential, OTP, and device-binding validation. It
must not return `UPDATE_REQUIRED`.

Build `71` and build `72` must also continue normally. Missing build metadata from an older
known-compatible APK should remain supported during this emergency phase.

## Device-Binding Safety

- Do not disable the one-account/one-device rule globally.
- Existing matching bindings must continue to allow same-device login.
- A usable binding belonging to another physical device must still return the
  stable device-conflict response.
- Do not silently rebind on an ordinary login request.
- Do not run the global reset until Play build `71` is verified, because a
  supported older APK could reclaim the cleared binding first.
- A reset must remove every binding row and deactivate only mobile sessions and
  push registrations. It must not touch web sessions, passwords, attendance,
  CP/SV/Joint CP, tracking, IAM, uploads, or business data.

## Fix The Timeout

The timeout shown in the supplied screenshot is not an invalid-password or
device-binding response. The server did not return an HTTP response before the
mobile `30-second` read timeout.

Backend action:

- inspect cold starts, runtime saturation, database connection setup, and auth
  middleware;
- add timing around build validation, staff lookup, binding lookup, and session
  creation;
- keep auth p95 below `10 seconds` and never near `30 seconds`;
- ensure a timed-out request cannot leave a partial session or device binding;
- warm or scale the production runtime before asking staff to retry.

Increasing the app timeout alone is not the correct immediate fix because it
would make staff wait longer and still require a new app release.

## Safe Verification

Verify the app-version result for the actual distributed APK build. For build
`70`, the temporary expected result is HTTP 200 with:

```json
{
  "latestBuildNumber": 72,
  "minimumSupportedBuildNumber": 70,
  "updateRequired": false
}
```

Run the non-mutating contract checks:

```powershell
node scripts/check-mobile-api.mjs device-rollout-contracts --rollout-mode compatibility --platform android --legacy-build 70 --target-build 72 --timeout 90000
node scripts/check-mobile-api.mjs device-login-contracts --timeout 90000
node scripts/check-mobile-api.mjs auth-recovery-contracts --timeout 90000
```

Acceptance requirements:

- Distributed APK users no longer receive the mandatory-update login block.
- Build `71` and build `72` users do not receive the mandatory-update login block.
- Employee-ID and OTP routes return an HTTP response in less than `10 seconds`.
- Same-device login succeeds for a disposable account with a matching binding.
- A different device remains rejected with the stable conflict code.
- No reset or login test uses a real staff account.

## Later Enforced Rollout

Only after the Play-installed build `72` passes first login, same-device
relogin, different-device rejection, and session validation:

1. Enable Android minimum build `72`.
2. Confirm builds below `72` receive HTTP 426 `UPDATE_REQUIRED` from all three auth
   routes before any OTP, credential, session, or binding work.
3. Run the audited device-binding reset in reviewed pages.
4. Confirm the first Play build-72 login creates exactly one binding.
5. Monitor login latency and binding conflicts before completing the rollout.
