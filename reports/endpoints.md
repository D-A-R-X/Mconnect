# Mconnect Mobile Required Endpoints

Date: 2026-09-07

This is the consolidated backend handoff for Android and iOS. It covers the
mobile features that are implemented or planned and the server work that is
still required. It replaces neither API source code nor the focused handoff
documents; it provides one release checklist for the backend, mobile, and QA
teams.

## Hosts and authentication

| Service | Base URL | Authentication |
| --- | --- | --- |
| MMS, CP, SV, attendance, app policy | `https://api-mfpl.theairix.com/` | `Authorization: Bearer <MMS_SESSION_TOKEN>` unless marked public |
| Direct GeoTrack writes | `https://api-geo.theairix.com/` | Current MMS bearer plus `Idempotency-Key` |
| Modern Dialer provider | A resolvable `*.theairix.com` API host is still required | Server-to-server `X-Service-Secret`; never embed this secret in mobile |

All authenticated requests must resolve staff and IAM from the bearer token.
Client-supplied staff IDs, template names, roles, device models, or IP addresses
are not authorization evidence.

## Status legend

| Status | Meaning |
| --- | --- |
| `EXISTING` | Route is present; keep the contract and verify it in the release environment. |
| `FIX REQUIRED` | Do not add a duplicate route. Correct or deploy the stated behavior on the existing route. |
| `MISSING` | A backend/provider route is still required. |
| `INTERNAL REPAIR` | No public mobile route is needed; an audited admin job/mutation must repair historical data. |

An unauthenticated HTTP 401 proves only that a protected route is reachable. It
does not prove the authenticated success path, SMS delivery, IAM visibility,
database synchronization, or completion behavior.

## Immediate backend work

1. Release the Android/iOS verified-OTP recovery clients for the deployed
   same-device recovery routes. Do not weaken normal login-time enforcement.
2. Finish and deploy the Joint CP template-role workflow, including a required
   reviewer remark on reviewer completion and all four workflow routes in the
   tracked backend source.
3. Keep CP/SV list and filter queries bounded, IAM-scoped, paginated, searchable,
   and stable under concurrent requests.
4. Enforce attendance-bounded tracking at bootstrap and ingestion, then repair
   historical pre-punch/post-punch tracking evidence.
5. Block mobile attendance remark/time-correction writes server-side so old app
   builds cannot submit them.
6. Supply the missing MMS Dialer history route and the missing Modern Dialer
   provider API host/routes, push delivery, and TURN/ICE/RTP repair.
7. Configure and verify the iOS app-version policy with an approved App Store,
   TestFlight, or managed-distribution URL before making it mandatory.

## 1. Login, session, and same-device recovery

| Method and route | Status | Required contract |
| --- | --- | --- |
| `POST /api/auth/send-otp` | `EXISTING` | Public login route. Return structured success/error. Its 401 must never clear an existing MMS session. |
| `POST /api/auth/verify-otp` | `EXISTING` | Verify OTP, issue a mobile session, and apply strict device binding. Do not accept a different bound device during public login. |
| `POST /api/auth/login-with-employee-id` | `EXISTING` | Validate employee ID/password and strict device binding; return structured error codes, not only `HTTP 401`. |
| `POST /api/auth/device-binding/recovery/request` | `EXISTING` | After a device-lock response, verify Employee ID/password and send a short-lived OTP only to the registered phone. |
| `POST /api/auth/device-binding/recovery/confirm` | `EXISTING` | Verify the single-use challenge, OTP and same device context; atomically rebind and return one new mobile session. |
| `GET /api/auth/validate-session` | `EXISTING` | Authoritative MMS session check. |
| `POST /api/auth/logout` | `EXISTING` | End only the intended session and deactivate its push registration. |
| `POST /api/push/register` | `EXISTING` | Register push and optionally report `bindingStatus`; authenticated migration remains available for a still-valid same-staff mobile session. |
| `POST /api/push/unregister` | `FIX REQUIRED` | Deactivate MMS and forwarded Dialer device tokens without affecting business data. |
| `GET /api/hr/staff/security?staffId=<id>` | `EXISTING` | Authorized security/device-binding status read. |
| `POST /api/hr/staff/device-reset` | `EXISTING` | Admin-only fallback requiring `staff.resetDeviceBinding`; body is `{ "staffId": "<id>" }`. |

### Pre-login recovery flow

Normal Employee-ID login remains strict. Only a response with stable code
`DEVICE_BOUND_TO_OTHER_DEVICE` may reveal **Recover this device**. The mobile
app then calls recovery request with the same credential and current device
identity, prompts for the OTP sent to the account's registered phone, and calls
confirm with the same challenge/device identity. Successful confirm returns the
new mobile token and user, which enter the unchanged secure session bootstrap.

### Production binding incident action

The fleet-wide logout followed by rejection on the original phone matches the
historical bad-binding incident: some `staffDeviceBindings.deviceId` values were
captured from the GeoTrack UUID channel instead of the login identity channel.
Those rows can never equal Android `ANDROID_ID` or the iOS Keychain login ID.

The backend owner must deploy the capture-only binding fix and run the audited
legacy-row repair before expecting ordinary login to work for already-affected
accounts. Repair only rows proven to have the legacy/unusable identifier shape;
do not bulk accept an incoming device ID and do not weaken exact matching for a
valid existing binding. Confirm from failed-login audit metadata that
`attemptedDeviceId` and `boundDeviceId` differ for the same staff/device model.

The mobile clients also support the deployed request/confirm recovery flow for
accounts with a valid Employee-ID credential. They recognize both the stable
`DEVICE_BOUND_TO_OTHER_DEVICE` code and the older canonical binding message;
generic HTTP 401, invalid password, network and server errors never expose the
recovery action. OTP login binding failures direct the user to this explicit
Employee-ID recovery flow rather than pretending the failure is connectivity.

Invalid credentials, generic 401 responses, malformed responses and network
errors must not reveal or automatically run recovery. Recovery must not clear
attendance, CP/SV, tracking, Dialer or offline business data.

### Authenticated behavior on `POST /api/push/register`

This remains a separate silent recovery path for an app that still has a valid
mobile bearer and supplies the current app device identifier:

```http
POST /api/push/register
Authorization: Bearer <CURRENT_MOBILE_SESSION_TOKEN>
Content-Type: application/json

{
  "token": "<FCM_OR_APNS_TOKEN>",
  "platform": "android|ios",
  "provider": "fcm|apns",
  "bundleId": "<PLATFORM_BUNDLE_ID>",
  "deviceId": "<CURRENT_APP_DEVICE_ID>",
  "deviceModel": "<MODEL>"
}
```

When the stored device ID differs, migrate the binding only if the bearer maps
to a session that exists, is active, is unexpired, has `deviceType=mobile`,
belongs to the same staff member, and is not impersonated. Update only device
identity/telemetry fields. Preserve the mobile session and every attendance,
CP, SV, tracking, Dialer, permission, and offline-sync record.

Missing, expired, inactive, web, other-staff, or impersonated session proof must
leave the binding unchanged. A user already at the login screen must use the
verified pre-login recovery flow or the authorized admin device-reset fallback.
Matching model, IP, phone number, password, or push token alone must never
rebind a device.

Keep the public response backward compatible while allowing diagnostics:

```json
{
  "success": true,
  "deviceTokenId": "<registered-row-id>",
  "bindingStatus": "ok|bound|authenticated_migration|mismatch_ignored|skipped"
}
```

Session invalidation rule: mobile may clear its MMS session only when an
authenticated request to the authoritative MMS host returns HTTP 401. A 401
from login/public routes, GeoTrack, Dialer, maps, storage, chat, or another
secondary host is operation-specific and must not log the user out.

## 2. Mobile app version and guarded update

| Method and route | Status | Required contract |
| --- | --- | --- |
| `GET /api/mobile/app-version?platform=android|ios&currentVersion=<version>&buildNumber=<build>` | `EXISTING` | Public, cacheable policy endpoint. Numeric build number is authoritative. |

Required response fields:

```json
{
  "success": true,
  "platform": "android",
  "latestVersion": "1.1.0",
  "latestBuildNumber": 70,
  "minimumSupportedVersion": "1.1.0",
  "minimumSupportedBuildNumber": 70,
  "updateRequired": true,
  "updateUrl": "https://play.google.com/store/apps/details?id=com.manjugroups.mconnect",
  "publishedAt": "<ISO-8601>"
}
```

Only raise `minimumSupportedBuildNumber` after the replacement build is live and
verified. Android must receive the Mconnect Play URL. iOS must receive an
approved App Store/TestFlight/managed HTTPS URL before `updateRequired=true`.
The app itself decides whether installation/restart is safe; the policy route
must not claim that a CP, SV, attendance/tracking session, call, or offline queue
is idle.

## 3. Staff directory, IAM, and direct-team scope

| Method and route | Status | Required contract |
| --- | --- | --- |
| `GET /api/hr/staff` | `FIX REQUIRED` | Return all IAM-authorized selectable staff and Joint CP template metadata. Do not truncate to the first page. |
| `GET /api/hr/staff/search?query=<text>` | `EXISTING` | Server-side searchable staff list for large organizations. |
| `GET /api/iam/my-permissions` | `EXISTING` | Effective IAM used to enable creation/review actions. |

Every Joint CP staff object must include server-resolved `iamTemplateId`,
`iamTemplateName`, `iamTemplateLevel`, and `jointCpWorkflowRole` with role
`outcome_owner`, `reviewer`, or `null`.

For CP/SV scope, `mine` means the signed-in staff member, `direct` means only
staff who report directly to that member, and `all` is available only when IAM
explicitly allows organization-wide access. If C reports to A and A reports to
B, C must be visible to A but not to B through `direct` scope. Joint CP
participants must still see their own shared visit.

## 4. CP creation, lists, filters, and completion

| Method and route | Status | Required contract |
| --- | --- | --- |
| `POST /api/marketing/clientPlaceVisits/create` | `FIX REQUIRED` | Idempotent CP creation; persist canonical `cpType`, `jointCpCategory`, client identity, participants, and request ID. Reject invalid/missing type. |
| `GET /api/marketing/clientPlaceVisits/my` | `FIX REQUIRED` | IAM-scoped, paginated list supporting `scope`, date, status, outcome, CP type, field staff, telecaller, search, cursor, and page size. Stable under concurrent filters. |
| `GET /api/marketing/clientPlaceVisits/get?id=<cpId>` | `EXISTING` | Return the same canonical type/status/client/participant data shown by web. |
| `GET /api/marketing/clientPlaceVisits/filter-options` | `FIX REQUIRED` | Return complete authorized searchable facets and counts without unbounded enrichment. |
| `POST /api/marketing/clientPlaceVisits/markClientMet` | `FIX REQUIRED` | `clientMet=false` requires photo but never OTP; preserve Joint CP review state. |
| `POST /api/marketing/clientPlaceVisits/setOutcome` | `FIX REQUIRED` | Persist structured outcome and linked state atomically; HTTP 200 with `success=false` is failure. |
| `POST /api/marketing/clientPlaceVisits/referral` | `EXISTING` | Idempotent referral linkage with no duplicate referral on retry. |
| `POST /api/marketing/clientPlaceVisits/cancel` | `FIX REQUIRED` | Require a trimmed reason and atomically close CP, linked SV, trips, participant legs, and tasks. |
| `POST /api/marketing/clientPlaceVisits/convertToSiteVisit` | `EXISTING` | Preserve source CP ID/type and create only one linked SV. |
| `POST /api/geotrack/visit/arrival-otp/request` | `FIX REQUIRED` | Send only after role/geofence/proximity checks; do not send for client-not-met. |
| `POST /api/geotrack/visit/arrival-otp/verify` | `FIX REQUIRED` | Verify against the correct visit/client and preserve unrelated sessions. |
| `POST /api/geotrack/visit/arrival-otp/cancel` | `EXISTING` | Cancel only the active OTP challenge. |
| `POST /api/geotrack/visit/complete` | `FIX REQUIRED` | Persist CP outcome before completing linked trip; return explicit success/error and remain idempotent. |
| `GET /api/marketing/cp-visits/pending-approvals` | `EXISTING` | Authorized approval queue. |
| `GET /api/marketing/cp-visits/approval-route?id=<cpId>` | `EXISTING` | Approval detail and route evidence. |
| `POST /api/marketing/cp-visits/approve` | `EXISTING` | Idempotent approval and linked-state transition. |
| `POST /api/marketing/cp-visits/reject` | `EXISTING` | Required reason and linked-state transition. |
| `POST /api/marketing/cp-visits/otp-assist` | `EXISTING` | GM/admin OTP assistance under IAM; never bypass proof rules. |
| `POST /api/marketing/cp-visits/geofence-remark` | `EXISTING` | Store out-of-geofence remark without inventing completion. |

Canonical CP types must be stable machine values such as `old_client`,
`new_client`, `follow_up`, `other_cp`, and `joint_cp`; labels are presentation
only. Completion must never erase or derive CP type from outcome/status.

CP filter responses must include all authorized status/outcome/type/staff values
used by web. Staff facets need server-side search or complete bounded pagination.
`scope=direct` must remain one reporting level only. Empty filters return HTTP
200 with empty arrays/list, not an error.

For SV-cum-CP completion, `POST /api/geotrack/visit/complete` must apply
`clientMet` and `outcome` to the parent CP before closing the field visit. This
prevents web showing Completed while mobile remains Enroute/Pending, or a linked
SV remaining Fixed because the CP outcome was never persisted.

## 5. Joint CP template, seniority, OTP, and review

| Method and route | Status | Required contract |
| --- | --- | --- |
| `POST /api/marketing/clientPlaceVisits/create` | `FIX REQUIRED` | Resolve both effective IAM levels server-side; reject same staff, missing level, or equal level before creating any rows. Lower level is outcome owner and higher level is reviewer, regardless of template ID or legacy role strings. |
| `GET /api/marketing/clientPlaceVisits/joint-workflow?id=<cpId>` | `FIX REQUIRED` | Return canonical state, actor role, owner/reviewer identity, permissions, proximity, revision, draft, review, and completion data. |
| `POST /api/marketing/clientPlaceVisits/joint-arrival-preflight` | `FIX REQUIRED` | Owner-only, two fresh accurate locations, server-calculated strict distance `< 50m`. |
| `POST /api/marketing/clientPlaceVisits/joint-submit-review` | `FIX REQUIRED` | Owner-only; require OTP/photo/outcome where applicable, recheck proximity, atomically finish both trip legs, and set `pending_review`. |
| `POST /api/marketing/clientPlaceVisits/joint-complete-review` | `FIX REQUIRED` | Reviewer-only; require latest revision and reviewer remark, publish final outcome, and complete once. |

Authority comes from the numeric level of the assigned IAM template, never
free-text designation, template ID equality, participant order, or a legacy
workflow-role string. The lower level fills OTP/photo/outcome and sends review;
the higher level previews, may edit, adds the required remark, and completes.
UI text must use the actual owner/reviewer name, never hardcode BDO/SM/GM.

Preferred completion body change on the existing route:

```http
POST /api/marketing/clientPlaceVisits/joint-complete-review
Authorization: Bearer <MMS_SESSION_TOKEN>
Idempotency-Key: <UUID>
Content-Type: application/json

{
  "id": "<CP_ID>",
  "expectedOutcomeRevision": 4,
  "reviewerRemark": "Reviewed with the client and confirmed"
}
```

`reviewerRemark` must be trimmed and non-empty. Store reviewer staff/template,
remark, review time, owner revision, and final revision. Return the final
structured outcome and `reviewedByName`/`reviewedByTemplateName`.

Client-not-met is an explicit edge case: photo is required, OTP is not. It must
still proceed through owner Send Review and reviewer completion. A reviewer may
never request/verify client OTP. At exactly 50 metres the flow is blocked.

Completed detail must return two immutable participant route records and two
separate point arrays, including staff ID and field-visit ID. Recommended colors
are owner blue `#1565C0` and reviewer orange `#EF6C00`.

The four Joint CP workflow handlers are reachable in the deployed environment,
but they were absent from the inspected backend `convex/http.ts`. Add them to or
reconcile them with the tracked backend source before the next deployment.

## 6. Site Visit creation, QR, counselling, outcome, and filters

| Method and route | Status | Required contract |
| --- | --- | --- |
| `POST /api/marketing/siteVisits/create` | `EXISTING` | Create one SV with source CP/client/project/staff ownership preserved. |
| `GET /api/sitevisits/my` | `FIX REQUIRED` | IAM-scoped, indexed, paginated list with date/project/LMO/field-staff/status/search filters and stable cursor. |
| `GET /api/sitevisits/filter-options` | `FIX REQUIRED` | Return `projects`, `lmos`, `fieldStaff`, and grouped `statuses`; complete authorized options without full-row enrichment. |
| `POST /api/geotrack/visit/start` | `EXISTING` | Start only the selected SV trip and remain idempotent. |
| `POST /api/marketing/siteVisits/scanQr` | `FIX REQUIRED` | Validate the QR against the selected SV/project and return explicit confirmation. |
| `POST /api/marketing/siteVisits/markOnCounselling` | `FIX REQUIRED` | Start counselling only after valid QR/arrival state; return canonical SV status. |
| `POST /api/marketing/siteVisits/setOutcome` | `FIX REQUIRED` | Persist the selected outcome and return canonical final/pending state. |
| `POST /api/marketing/siteVisits/convertToBooking` | `FIX REQUIRED` | Create/submit one linked booking and preserve source IDs. |
| `POST /api/marketing/siteVisits/postpone` | `EXISTING` | Persist reason/date and keep trip/state consistent. |
| `POST /api/marketing/siteVisits/cancel` | `FIX REQUIRED` | Require remarks; cancel from Trip Details without auto-opening the SV form. |
| `POST /api/marketing/siteVisits/markPickedUp` | `EXISTING` | Canonical transport transition. |
| `POST /api/marketing/siteVisits/markClientStarted` | `EXISTING` | Canonical client-start transition. |
| `POST /api/marketing/siteVisits/markArrivedSite` | `EXISTING` | Canonical site-arrival transition. |
| `POST /api/marketing/siteVisits/markPickedFromSite` | `EXISTING` | Canonical return transition. |
| `POST /api/marketing/siteVisits/markDropped` | `EXISTING` | Canonical drop/completion transition. |
| `POST /api/geotrack/visit/complete` | `FIX REQUIRED` | Close the correct trip only after the required SV outcome/booking workflow succeeds. |

The SV list and filter routes must page before enriching projects, leads,
clients, CP rows, staff, or field visits. Return `success`, `visits`, `total`,
`nextCursor`, and `hasMore`; no authorized matches is HTTP 200 with an empty
list. Scrolling/pagination must not duplicate rows or return oversized payloads.

Booking outcome must not report success unless the server returns the linked
booking and canonical SV state. A saved booking draft must never finalize CP/SV
or stop its trip.

## 7. Booking, referral, address, and map support

| Method and route | Status | Required contract |
| --- | --- | --- |
| `POST /api/bookings` | `FIX REQUIRED` | Idempotent source-linked submission. Draft stays nonterminal; submitted booking follows CP/SV and Joint CP review rules. |
| `POST /api/bookings/draft/save` | `EXISTING` | Save resumable draft without completing its source visit. |
| `GET /api/bookings/draft/get` | `EXISTING` | Restore the correct staff/source draft. |
| `POST /api/bookings/draft/clear` | `EXISTING` | Clear only the selected draft. |
| `GET /api/marketing/bookings/filter-options` | `EXISTING` | Searchable project/plot/status facets consistent with web. |
| `POST /api/address/parse` | `EXISTING` | Normalize pasted/shared map text and coordinates. |
| `GET /api/address/autocomplete?query=<text>` | `EXISTING` | Search suggestions through the backend; do not expose Google server keys to mobile. |
| `GET /api/address/place?placeId=<id>` | `EXISTING` | Resolve selected place details and coordinates. |
| `GET /api/tracking/places/search?q=<text>` | `EXISTING` | Search authorized internal client/project places. |
| `POST /api/geotrack/geocode-address` | `EXISTING` | Resolve an address through the configured backend map integration. |
| `POST /api/geotrack/route` | `EXISTING` | Return route geometry/distance for an authorized trip. |

## 8. Attendance-bounded GeoTrack

| Method and route | Status | Required contract |
| --- | --- | --- |
| `GET /api/hr/attendance/today?date=yyyy-MM-dd` | `FIX REQUIRED` | Authoritative first-punch/final-punch state. |
| `GET /api/hr/attendance/day-sessions?date=yyyy-MM-dd` | `FIX REQUIRED` | Return actual punch intervals used by tracking enforcement. |
| `POST /api/hr/attendance/punch-in` | `EXISTING` | Start attendance; tracking may begin only after confirmed success. |
| `POST /api/hr/attendance/punch-out` | `EXISTING` | End attendance; tracking must stop and final in-window buffer may sync. |
| `GET /api/tracking/bootstrap?deviceId=<id>` | `FIX REQUIRED` | Before first punch/after final punch return `shouldTrack=false`, `activeSession=null`. |
| `POST /api/tracking/device/sync` | `FIX REQUIRED` | Never create/start tracking from assignment alone; reflect attendance window. |
| `POST /api/geotrack/start` | `FIX REQUIRED` | Direct host must validate an active punch interval before opening a tracking session. |
| `POST /api/geotrack/stop` | `EXISTING` | Idempotently close the active tracking session. |
| `POST /api/tracking/location/batch` | `FIX REQUIRED` | Accept only points whose original timestamps fall inside an authorized punch interval. |
| `POST /api/tracking/heartbeat` | `FIX REQUIRED` | Reject/filter out-of-window heartbeats and do not update live status. |
| `POST /api/tracking/tamper-events` | `FIX REQUIRED` | Reject/filter out-of-window events and do not create timeline evidence. |
| `GET /api/geotrack/live-status` | `EXISTING` | Return state derived only from authorized tracking intervals. |
| `GET /api/geotrack/timeline` | `EXISTING` | Exclude repaired/invalid pre-punch and post-punch events. |
| `GET /api/geotrack/session-route` | `EXISTING` | Return route for the requested authorized session. |

Offline points captured during a valid punch interval may upload later using
their original timestamps. Events before first punch, between closed sessions,
or after final punch-out must not create location, timeline, distance, battery,
network, permission, tamper, or live-status data.

The missed-heartbeat monitor must join each `trackingActive` row to an open
attendance session for the current `Asia/Kolkata` date before creating
`HEARTBEAT_MISSED`/`NETWORK_OFFLINE`. Cached `trackingActive=true`, an old
tracking session, a tracking assignment, or yesterday's punch is never enough.
When attendance is absent or finalized, atomically demote live status, end stale
tracking sessions, resolve open heartbeat alerts, and create no new timeline
event. Apply this rule in whichever service owns production monitoring:
Convex when `GEO_SERVICE_OWNS_JOBS != 1`, and the direct PostgreSQL GeoTrack
worker when `GEO_SERVICE_OWNS_JOBS == 1`.

## 9. Disable mobile attendance correction/remark requests

| Method and route | Status | Required contract |
| --- | --- | --- |
| `POST /api/hr/attendance/request` | `FIX REQUIRED` | Reject mobile `remark` and `correction` submissions before creating any request/task/notification. |

Required mobile response:

```http
HTTP/1.1 410 Gone
Content-Type: application/json

{
  "success": false,
  "code": "MOBILE_ATTENDANCE_REQUEST_DISABLED",
  "error": "Attendance remark and time correction requests are not available in the mobile app.",
  "retryable": false
}
```

Keep existing authorized web/admin review behavior where policy permits it.

## 10. Filter endpoints used across mobile

| Method and route | Status | Required contract |
| --- | --- | --- |
| `GET /api/marketing/clientPlaceVisits/filter-options` | `FIX REQUIRED` | CP date/status/outcome/type/field-staff/telecaller facets, counts, search, IAM scope. |
| `GET /api/sitevisits/filter-options` | `FIX REQUIRED` | SV date/project/LMO/field-staff/status facets, counts, search, IAM scope. |
| `GET /api/hr/attendance/filter-options?view=my|team|approval|all|hr_review` | `EXISTING` | Authorized staff/department/status facets; empty arrays are valid. |
| `GET /api/marketing/bookings/filter-options` | `EXISTING` | Project/plot/status facets. |
| `GET /api/hr/leaves/filter-options?scope=my|direct|all` | `EXISTING` | Authorized leave facets and direct-report semantics. |
| `GET /api/hr/permissions/filter-options?scope=my|direct|all` | `EXISTING` | Authorized permission facets and direct-report semantics. |

Every options route must support bounded server-side search or cursor paging for
large staff/project lists. The options and list endpoint must apply the same IAM
scope and vocabulary so selecting a visible option cannot yield a permission
leak or a contradictory count.

## 11. MMS Dialer endpoints

| Method and route | Status | Required contract |
| --- | --- | --- |
| `GET /api/mobile/dialer/config` | `EXISTING` | Return the signed-in staff's provider mapping without provider secrets. |
| `GET /api/mobile/dialer/calls/current` | `EXISTING` | MMS proxy for the authoritative current call. |
| `POST /api/mobile/dialer/calls/{callId}/action` | `EXISTING` | Idempotent pickup/reject/hangup proxy. |
| `POST /api/mobile/dialer/calls/{callId}/media/restart` | `EXISTING` | Proxy a real provider ICE restart. |
| `GET /api/mobile/dialer/calls/{callId}/media` | `EXISTING` | Sanitized diagnostics only. |
| `GET /api/mobile/dialer/history?limit=20&cursor=<cursor>` | `MISSING` | Authoritative, staff-scoped, newest-first call history with real remote client name and stable cursor. |

History must include incoming, outgoing, completed, missed, rejected, failed,
and no-answer calls. Empty history returns HTTP 200 and `calls: []`.

## 12. Modern Dialer provider endpoints

These are server-to-server routes on a resolvable provider API host under
`*.theairix.com` and remain missing until the provider supplies and verifies
them.

| Method and route | Status | Required contract |
| --- | --- | --- |
| `POST /api/mobile/device-tokens` | `MISSING` | Upsert/deactivate Android FCM and iOS PushKit/APNs tokens by staff, platform, provider, bundle, and device. |
| `GET /api/mobile/calls/current` | `MISSING` | Authoritative current call, ownership checked by extension/external ID. |
| `POST /api/mobile/calls/{callId}/action` | `MISSING` | Idempotent pickup/reject/hangup without requiring the embedded Dialer or foreground app. |
| `POST /api/mobile/calls/{callId}/media/restart` | `MISSING` | Perform a real ICE restart/re-offer. |
| `GET /api/mobile/calls/{callId}/media` | `MISSING` | Sanitized ICE/RTP counters with no credentials or secrets. |

MMS `POST /api/push/register` and `POST /api/push/unregister` must forward token
activation/deactivation to this provider. Incoming call push delivery requires
high-priority data-only FCM on Android and PushKit/CallKit-compatible APNs VoIP
delivery on iOS. A route alone is not completion: TURN/ICE/RTP must be verified
with two-way audio on real networks.

## 13. Historical data repair (no public endpoint)

### Completed CP linked-state repair

Use the existing internal audited repair
`internal.adminCpVisitRepair.repairCompletedCpLinkedState` in dry-run batches of
at most 50, with a stable job ID. Only a parent CP whose exact status is
`completed` is eligible. Reconcile stale linked field visits, participant rows,
geo trips, tasks, and tracking state without fabricating missing OTP, photo,
outcome, type, client identity, or reviewer evidence.

### Blank CP type repair

Backfill historical blank CP types only from authoritative stored source data or
an approved admin mapping. Never guess type from outcome/status. No new mobile
mutation route is required.

### Pre-punch/post-punch tracking repair

Remove or invalidate events outside real punch intervals, then recompute route
distance, stops/movement, live status, battery/network/permission/tamper
summaries, and timeline rows. Preserve a complete audit trail and support dry
run, bounded batches, conflict reporting, and idempotent reruns.

## External mobile storage

Status: **MOBILE READY; BACKEND ROUTES MISSING**

All Android and iOS upload features now prefer the following authenticated
MFPL control routes on `https://mg.theairix.com` while preserving the existing
business-facing `storageId`:

- `POST /api/storage/uploads`
- `POST /api/storage/uploads/{fileId}/complete`
- `DELETE /api/storage/uploads/{fileId}`
- `GET /api/storage/files/{storageId}`

The upload byte request is an exact-header `PUT` to the URL returned by create;
the MFPL bearer must not be attached unless explicitly returned as a required
header. Mobile must never receive `STORAGE_API_KEY`, MinIO or S3 credentials.

As of 2026-09-07 all four routes return `404` in production. Upload clients
therefore keep a rollout-only fallback to the existing authenticated MMS
`POST /api/storage/upload` route for create `404`/`503` only. Read migration is
deferred because changing it now would break historical files. See
`reports/MOBILE_STORAGE_MIGRATION_BLOCKERS.md` for the security finding and
deployment acceptance checks.

## Release acceptance gate

1. Run route/auth contract probes with `scripts/check-mobile-api.mjs`; do not
   treat unauthenticated 401 as business-flow proof.
2. Use disposable Android and iOS test accounts, test clients, and test visits.
   Never mutate a staff member's active production CP/SV/attendance/tracking.
3. Verify OTP delivery with an SMS provider receipt or the test client's actual
   received OTP; an HTTP success alone is insufficient.
4. Complete ordinary CP outcomes, client-not-met, postponement, cancellation,
   referral, booking, valid Joint CP review, SV QR/counselling/booking, and
   compare the same IDs, types, names, numbers, outcomes, counts, and statuses
   in Android, iOS, and web.
5. Verify the Joint CP owner and reviewer both see the final completed count and
   distinct route lines, with no partial completion on retry/network loss.
6. Test My/Team/All with a three-level hierarchy and a super-admin account.
   Direct reports must not include reports-of-reports; authorized All must show
   the full dataset through pagination.
7. Verify reinstall/same-device recovery with an active same-staff mobile
   session, then verify a genuinely different device remains blocked.
8. Verify pre-punch and post-punch GeoTrack writes cannot create live/timeline
   data, while delayed in-window offline points still synchronize.
9. Confirm malformed JSON, timeout, HTTP 400/403/401/409/410/500, and
   `success=false` never produce false UI success or clear the session outside
   the authoritative MMS authenticated-401 rule.
10. Publish and verify replacement Play/TestFlight builds before increasing the
    mandatory minimum build. Confirm update installation remains deferred while
    CP, SV, attendance/tracking, calls, or unsynced work is active.

## Focused source documents

- `reports/SAME_DEVICE_BINDING_RECOVERY_API_HANDOFF.md`
- `reports/JOINT_CP_TEMPLATE_REVIEW_API_HANDOFF.md`
- `reports/CP_TYPE_PERSISTENCE_API_HANDOFF.md`
- `reports/CP_CREATION_COMPLETION_API_VERIFICATION.md`
- `reports/bug issues.md`
- `reports/MOBILE_ALL_FILTER_API_HANDOFF.md`
- `reports/MMS_REMAINING_API_ENDPOINTS.md`
- `reports/DIALER_REMAINING_API_ENDPOINTS.md`
- `reports/GOOGLE_MAP_SEARCH_API_DOCUMENTATION.md`
- `reports/MOBILE_STORAGE_MIGRATION_BLOCKERS.md`
