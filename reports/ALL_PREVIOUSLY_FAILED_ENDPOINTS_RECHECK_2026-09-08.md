# Previously Failed Endpoint Recheck - 2026-09-08

## Scope

Production hosts checked:

- `https://api-mfpl.theairix.com/`
- `https://mg.theairix.com/`

The checks were non-mutating. They used empty credentials, invalid fixture IDs,
and unauthenticated requests. No OTP was sent, no staff login was performed, no
device binding was reset, no file was uploaded, and no CP/SV record was changed.

## Result Summary

| Contract group | Result | What it verifies |
|---|---:|---|
| Priority CP/Joint CP routes | 10/10 pass | OTP, outcome, Joint CP review, trip completion and completed-list routes are deployed and protected |
| Full CP/SV module | 111/112 pass | CP, SV, booking, collection, tracking, attendance and storage route/auth contracts |
| Employee ID and OTP login routes | 3/3 pass | Login routes reject invalid input with structured JSON instead of timing out or returning an update gate |
| Device recovery | 3/3 pass | Recovery request and both confirmation contracts are deployed |
| Play rollout compatibility | 13/13 pass | Legacy build 71 and target build 72 are not blocked; session and device-support routes are reachable |
| Staff security and reset | 11/11 pass | Security, active-login, logout, single reset and bulk reset routes are deployed and protected |
| Storage resolver | Fail | Known ID redirects but cannot be fetched; unknown ID returns 500 instead of 404 |

No new endpoint was added by this recheck.

## Login And Device Binding

| Endpoint | Purpose | Observed result |
|---|---|---|
| `POST /api/auth/send-otp` | Request mobile login OTP | Structured 400 for empty input; no `UPDATE_REQUIRED` block |
| `POST /api/auth/verify-otp` | Verify OTP and create session | Structured 400/401 for invalid fixture input |
| `POST /api/auth/login-with-employee-id` | Employee ID/password login | Structured 400/401 for invalid fixture input; no update gate |
| `GET /api/mobile/app-version` | Mobile release policy | 200; build 71 remains supported because minimum build is 0 |
| `GET /api/auth/validate-session` | Validate an existing bearer session | Structured 401 without bearer |
| `POST /api/auth/logout` | End the current mobile session | Structured 200 for the empty-session probe |
| `POST /api/push/register` | Register push/device identity after login | Structured 401 without bearer |
| `POST /api/auth/device-binding/recovery/request` | Start same-device recovery | Structured 400 validation response |
| `POST /api/auth/device-binding/recovery/confirm` | Confirm recovery challenge | Structured 400 with stable failure contract |
| `POST /api/auth/device-binding/recovery/confirm-verified-otp` | Confirm recovery after verified login OTP | Structured 400 with stable failure contract |
| `GET /api/hr/staff/security` | Read staff device/session security state | Structured 401 without bearer |
| `POST /api/hr/staff/device-reset` | Reset one staff device binding | Structured 401 without admin bearer |
| `POST /api/hr/staff/device-reset/bulk` | Reset selected staff bindings | Structured 401 without admin bearer; route is now deployed |
| `GET /api/hr/staff/active-logins` | Admin active-login list | Structured 401 without bearer |
| `GET /api/hr/staff/active-sessions` | Read one staff member's sessions | Structured 401 without bearer |
| `POST /api/hr/staff/force-logout` | Admin force logout | Structured 401 without bearer |
| `POST /api/hr/staff/logout-device` | End one device session | Structured 401 without bearer |
| `POST /api/hr/staff/logout-everywhere` | End all staff mobile sessions | Structured 401 without bearer |

Current app-version response:

```json
{
  "success": true,
  "platform": "android",
  "latestVersion": "1.0",
  "latestBuildNumber": 71,
  "minimumSupportedVersion": "1.0",
  "minimumSupportedBuildNumber": 0,
  "updateRequired": false,
  "updateUrl": "https://play.google.com/store/apps/details?id=com.manjugroups.mconnect",
  "publishedAt": "2026-09-03T12:00:00.000Z"
}
```

The workspace is Android build 72. Production advertising build 71 is not a
login blocker because `minimumSupportedBuildNumber` is 0. Update
`latestBuildNumber` only when build 72 is actually published.

## CP, Joint CP And Completion

These previously missing or failing routes now return structured 401 before
fixture-ID or body validation:

| Endpoint | Purpose | Result |
|---|---|---|
| `GET /api/mobile/dashboard` | CP/SV dashboard counts | Pass; the earlier unauthenticated company-data leak is closed |
| `GET /api/bookings/{id}` | Booking detail | Pass; the earlier malformed-ID 500 before authentication is closed |
| `GET /api/marketing/clientPlaceVisits/completed-count` | Completed CP count | Pass |
| `GET /api/marketing/clientPlaceVisits/completion-health` | Find inconsistent historical completion state | Pass |
| `POST /api/marketing/clientPlaceVisits/completion-repair/preview` | Preview historical status/count repair | Pass |
| `POST /api/marketing/clientPlaceVisits/completion-repair/apply` | Apply approved historical repair | Pass |
| `POST /api/marketing/clientPlaceVisits/joint-participant-ready` | Record each Joint CP participant start/readiness | Pass |
| `GET /api/marketing/clientPlaceVisits/joint-workflow` | Return outcome owner, reviewer and workflow state | Pass |
| `POST /api/marketing/clientPlaceVisits/joint-arrival-preflight` | Enforce Joint CP proximity before completion | Pass |
| `POST /api/geotrack/visit/arrival-otp/request` | Send client arrival OTP | Pass |
| `POST /api/geotrack/visit/arrival-otp/verify` | Verify OTP and arrival proof | Pass |
| `POST /api/marketing/clientPlaceVisits/setOutcome` | Save CP outcome and outcome fields | Pass |
| `POST /api/marketing/clientPlaceVisits/joint-submit-review` | Lower-level participant submits outcome | Pass |
| `POST /api/marketing/clientPlaceVisits/joint-complete-review` | Senior participant edits/reviews, remarks and completes | Pass |
| `POST /api/geotrack/visit/complete` | Close the visit/trip | Pass |
| `GET /api/marketing/clientPlaceVisits/my` | Synchronize lists, statuses and completed tab | Pass |

The complete route sweep also passed the deployed/auth contract for CP create,
CP detail/filter/cancel/referral/conversion, SV create/QR/counselling/outcome and
transport states, booking draft/create/edit/approval, collection submit/review,
attendance, location batching, heartbeat and tracking start/stop.

## Storage Upload And Fetch

| Endpoint | Purpose | Result |
|---|---|---|
| `POST /api/storage/uploads` | Create upload session | Pass: structured 401 without bearer |
| `POST /api/storage/uploads/{fileId}/complete` | Confirm uploaded object | Pass: structured 401 without bearer |
| `DELETE /api/storage/uploads/{fileId}` | Abort failed upload | Pass: structured 401 without bearer |
| `GET /api/storage/files/{storageId}` | Fetch profile/CP/SV/attendance/collection media | **Fail** |

Known compatibility ID result:

```http
GET /api/storage/files/64ceeb75-bfb4-4ed7-aabb-ae5f4297190a
HTTP/1.1 307 Temporary Redirect
Location: https://api-mfpl.theairix.com/api/storage/serve?storageId=64ceeb75-bfb4-4ed7-aabb-ae5f4297190a
```

Following the redirect currently returns:

```http
HTTP/1.1 404 Not Found
Content-Type: text/plain; charset=UTF-8

File not found
```

Unknown-ID result:

```http
GET /api/storage/files/contract-probe
HTTP/1.1 500 Internal Server Error
Content-Type: application/json
```

```json
{
  "success": false,
  "error": "failed to load file"
}
```

This can still break profile photos and uploaded CP/SV proof even though upload
route authentication contracts pass.

## Verification Boundary

The route/auth checks prove deployment, authentication ordering and structured
error contracts. They do not prove authenticated success-path data mutations.
The following still require disposable authenticated fixtures:

- real Employee ID login, OTP login and same-device relogin;
- different-device rejection after a binding exists;
- a two-account Joint CP from both participant starts through OTP, photo,
  outcome, senior remarks, 50-metre validation and completion credit;
- historical completion repair preview/apply and assigned-date count result;
- a real storage upload followed by byte-for-byte image retrieval.
