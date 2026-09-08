# Deployed Endpoint Verification - 2026-09-08

## Scope

Production host: `https://api-mfpl.theairix.com/`

This verification used only invalid credentials, invalid bearer tokens, and
contract-probe identifiers. It did not send a real OTP, create a session,
change a device binding, reset a staff account, upload a file, or modify CP/SV
data.

Android currently uses app version `1.0`, build/version code `71`.

## Endpoint Results

| Endpoint | Purpose | Result |
| --- | --- | --- |
| `GET /api/mobile/app-version` | Returns current/minimum mobile build and update destination | Working for Android and iOS. Both report latest/minimum build `71`. Android has a Play URL; iOS returns `updateUrl: null`. |
| `POST /api/auth/send-otp` | Starts mobile-number login and checks build/device metadata | Current-build validation works. Build `70` is not rejected with HTTP 426. |
| `POST /api/auth/verify-otp` | Verifies OTP and creates or validates the device-bound session | Current-build invalid-OTP contract works. Build enforcement remains unverified because the rollout checker stops on the earlier send-OTP failure. |
| `POST /api/auth/login-with-employee-id` | Employee-ID/password login and device binding | Current build `71` returns the expected structured invalid-credential response. Build `70` also reaches credential validation instead of HTTP 426. |
| `POST /api/auth/device-binding/recovery/request` | Starts explicit OTP-backed device recovery | Working validation contract: HTTP 400 for an empty non-mutating probe. |
| `POST /api/auth/device-binding/recovery/confirm` | Confirms password-backed device recovery | Working validation contract: HTTP 400 for an empty non-mutating probe. |
| `POST /api/auth/device-binding/recovery/confirm-verified-otp` | Confirms recovery after verified OTP | Working validation contract: HTTP 400 for an empty non-mutating probe. |
| `GET /api/auth/validate-session` | Validates the active MMS session | Working authorization contract: HTTP 401 for an invalid token. |
| `POST /api/auth/logout` | Deactivates the current mobile session | Working idempotent contract: HTTP 200 for an already-invalid token. |
| `POST /api/push/register` | Registers the push token against the authenticated device | Working authorization contract: HTTP 401 for an invalid token. |
| `GET /api/hr/staff/security` | Reads admin-visible device security state | Working authorization contract: HTTP 401 for an invalid token. |
| `POST /api/hr/staff/device-reset` | Performs an authorized per-staff device reset | Working authorization contract: HTTP 401 for an invalid token. |

## CP And Joint CP Contracts

The nine safe protected-route probes passed with structured HTTP 401 responses:

- `GET /api/marketing/clientPlaceVisits/joint-workflow`
- `POST /api/marketing/clientPlaceVisits/joint-arrival-preflight`
- `POST /api/geotrack/visit/arrival-otp/request`
- `POST /api/geotrack/visit/arrival-otp/verify`
- `POST /api/marketing/clientPlaceVisits/markClientMet`
- `POST /api/marketing/clientPlaceVisits/setOutcome`
- `POST /api/marketing/clientPlaceVisits/joint-submit-review`
- `POST /api/marketing/clientPlaceVisits/joint-complete-review`
- `GET /api/marketing/clientPlaceVisits/my?scope=mine&status=completed&pageSize=1`

This verifies route availability and authentication boundaries. It does not
certify the mutating OTP/outcome/review/completion flow without a disposable
Joint CP fixture and both participant tokens.

## Storage Contracts

- `POST /api/storage/uploads`: working authorization contract (HTTP 401).
- `POST /api/storage/uploads/{storageId}/complete`: working authorization contract (HTTP 401).
- `DELETE /api/storage/uploads/{storageId}`: working authorization contract (HTTP 401).
- `GET /api/storage/files/{storageId}`: HTTP 404; expected protected-route HTTP 401.

## Performance Evidence

The production host showed severe intermittent latency during this run:

- Android app-version requests took approximately `5.9s`, `25.0s`, and `61.3s`.
- One legacy send-OTP validation took approximately `54.4s`.
- Employee-ID invalid-credential checks took approximately `28.5s` and `24.1s` before the host warmed up.
- Later warmed requests generally completed in `0.2s` to `4.1s`.

The Android MMS client has a `30s` read timeout. Therefore the reported
`Network connection timed out` screen is reproducible when the backend request
falls into the slow range. This is a backend/runtime latency issue, separate
from credential and device-binding validation.

## Commands

```powershell
node scripts/check-mobile-api.mjs contracts --timeout 60000
node scripts/check-mobile-api.mjs device-login-contracts --timeout 60000
node scripts/check-mobile-api.mjs auth-recovery-contracts --timeout 60000
node scripts/check-mobile-api.mjs device-rollout-contracts --rollout-mode compatibility --platform android --legacy-build 71 --target-build 72 --timeout 90000
node scripts/check-mobile-api.mjs device-rollout-contracts --rollout-mode compatibility --platform ios --legacy-build 71 --target-build 72 --timeout 90000
node scripts/check-mobile-api.mjs device-rollout-contracts --rollout-mode enforced --platform android --legacy-build 70 --target-build 71 --timeout 60000
node scripts/check-mobile-api.mjs storage-contracts --timeout 90000
```

## Verdict

Build `71` public contracts are reachable, and the device-recovery/support
routes enforce authentication. The rollout is not ready for a fleet reset or
strict Play-only takeover because build `70` authentication is not blocked,
production cold latency exceeds the mobile timeout, and the successful
first-login/rebinding path has not been exercised with a disposable account.
