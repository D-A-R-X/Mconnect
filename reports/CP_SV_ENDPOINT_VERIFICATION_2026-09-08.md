# CP/SV Android Endpoint Verification - 2026-09-08

## Scope and meaning of PASS

This audit traces every Android API call reachable from CP creation, trip tracking,
arrival OTP/photo, outcome forms, Joint CP review, SV confirmation/counselling,
booking conversion, collection, list refresh, filters, and their nested form
dependencies.

The production probe is deliberately non-mutating. A protected route passes this
check when it exists and returns structured JSON at its authentication or request
validation boundary. This proves deployment, routing, and parseable error handling;
it does **not** prove a successful authenticated write, SMS delivery, object upload,
or web synchronization. Those need disposable authenticated fixtures.

Command:

```powershell
node scripts/check-mobile-api.mjs cp-sv-contracts --timeout 60000
```

Hosts checked:

- MMS/business: `https://api-mfpl.theairix.com/`
- GeoTrack ingestion: `https://api-geo.theairix.com/`
- Mobile storage: `https://mg.theairix.com/`

## Result

- Total contracts: **107**
- Passed routing/auth/JSON boundary: **104**
- Failed: **3**
- Live production records changed: **0**

The three failures are documented separately in
`reports/CP_SV_MISSING_BACKEND_ENDPOINTS_2026-09-08.md`.

## Form, lookup, and attendance gates

| Endpoint | Mobile purpose | Observed response |
|---|---|---|
| `GET /api/hr/staff?status=active` | Field staff and Joint CP participant picker | PASS: structured `401` |
| `GET /api/hr/staff/get?id=...` | Staff template/level detail | PASS: structured `401` |
| `GET /api/projects/get?id=...` | Project detail used by outcome/booking | PASS: structured `401` |
| `GET /api/telecaller/leads/search-by-phone` | Existing lead prefill | PASS: structured `401` |
| `GET /api/clients/search-by-phone` | Existing client prefill | PASS: structured `401` |
| `GET /api/clients/referral-candidates` | Referral client picker | PASS: structured `401` |
| `POST /api/telecaller/leads/update` | Persist edited client/lead fields | PASS: structured `401` |
| `POST /api/address/parse` | Parse typed/pinned address | PASS: structured `401` |
| `GET /api/address/autocomplete` | Address search suggestions | PASS: structured `401` |
| `GET /api/address/place` | Selected address coordinates/detail | PASS: structured `401` |
| `POST /api/geotrack/geocode-address` | Address-to-coordinate fallback | PASS: structured `401` |
| `POST /api/geotrack/route` | Route preview | PASS: structured validation `400` |
| `GET /api/hr/attendance/today` | CP/SV clocked-in gate | PASS: structured `401` |
| `GET /api/hr/attendance/day-sessions` | Active mobile work-session gate | PASS: structured `401` |
| `GET /api/hr/attendance/my` | Attendance/home reconciliation | PASS: structured `401` |
| `POST /api/hr/attendance/punch-in` | Start attendance and tracking eligibility | PASS: structured `401` |
| `POST /api/hr/attendance/punch-out` | End attendance and tracking eligibility | PASS: structured `401` |
| `GET /api/hr/permissions/monthly-usage` | Home operational data refresh | PASS: structured `401` |
| `GET /api/mobile/dashboard` | Home CP/SV counters | **FAIL: unauthenticated `200` with company totals** |

## Tracking and trip lifecycle

| Endpoint | Mobile purpose | Observed response |
|---|---|---|
| `GET /api/tracking/bootstrap` | Tracking/session bootstrap | PASS: structured `401` |
| `POST /api/tracking/device/sync` | Device tracking registration | PASS: structured validation `400` |
| `POST /api/tracking/consent` | New tracking-consent contract | PASS: structured `401` |
| `POST /api/geotrack/tamper/report` | MMS tamper fallback | PASS: structured validation `400` |
| `POST /api/geotrack/consent` | Legacy tracking consent | PASS: structured `401` |
| `GET /api/geotrack/consent/status` | Consent state | PASS: structured `401` |
| `GET /api/geotrack/assigned-places` | Assigned trip places | PASS: structured `401` |
| `GET /api/geotrack/today-visits` | Today's field visits | PASS: structured `401` |
| `GET /api/sitevisits/my` | SV list and synchronization | PASS: structured `401` |
| `POST /api/geotrack/visit/create` | Create backing field visit | PASS: structured `401` |
| `POST /api/geotrack/visit/start` | Start CP/SV trip | PASS: structured `401` |
| `POST /api/geotrack/visit/complete` | Final trip completion | PASS route; **payload contract gap found in source** |
| `GET /api/geotrack/timeline` | Staff/visit tracking timeline | PASS: structured validation `400` |
| `GET /api/geotrack/session-route` | Recorded route polyline | PASS: structured `401` |
| `GET /api/mms-fleet/driver/trips` | Driver-backed SV trip list | PASS: structured `401` |
| `POST /api/mms-fleet/driver/arrive` | Driver arrival | PASS: structured `401` |
| `POST /api/mms-fleet/driver/start` | Driver trip start | PASS: structured `401` |
| `POST /api/mms-fleet/driver/on-site` | Driver reached site | PASS: structured `401` |
| `POST /api/mms-fleet/driver/picked-from-site` | Driver picked client from site | PASS: structured `401` |
| `POST /api/mms-fleet/driver/end` | Driver trip completion | PASS: structured `401` |

Direct GeoTrack ingestion:

| Endpoint | Mobile purpose | Observed response |
|---|---|---|
| `POST /api/tracking/location/batch` | Buffered location upload | PASS: structured validation `400` |
| `POST /api/tracking/heartbeat` | Online/offline/battery heartbeat | PASS: structured validation `400` |
| `POST /api/tracking/tamper-events` | Tamper event ingestion | PASS: structured validation `400` |
| `POST /api/geotrack/start` | Direct tracking start | PASS: structured validation `400` |
| `POST /api/geotrack/stop` | Direct tracking stop | PASS: structured validation `400` |

## Arrival OTP and proof

| Endpoint | Mobile purpose | Observed response |
|---|---|---|
| `POST /api/geotrack/visit/arrival-otp/request` | Send client OTP | PASS: structured `401` |
| `POST /api/geotrack/visit/arrival-otp/verify` | Verify client OTP and photo reference | PASS: structured `401` |
| `POST /api/geotrack/visit/arrival-otp/cancel` | Cancel an OTP challenge | PASS: structured `401` |
| `POST /api/marketing/cp-visits/otp-assist` | Manager OTP assistance | PASS: structured `401` |

## CP and Joint CP

| Endpoint | Mobile purpose | Observed response |
|---|---|---|
| `POST /api/marketing/clientPlaceVisits/create` | Create normal/Joint/typed CP | PASS: structured `401` |
| `GET /api/marketing/clientPlaceVisits/get` | CP detail and completion context | PASS: structured `401` |
| `GET /api/marketing/clientPlaceVisits/my` | My/Team/All/status lists | PASS: structured `401` |
| `GET /api/marketing/clientPlaceVisits/filter-options` | Filter staff/project/type options | PASS: structured `401` |
| `POST /api/marketing/clientPlaceVisits/markClientMet` | Record client-met decision | PASS: structured `401` |
| `POST /api/marketing/clientPlaceVisits/setOutcome` | Save CP outcome and fields | PASS: structured `401` |
| `POST /api/marketing/clientPlaceVisits/referral` | Referral outcome | PASS: structured `401` |
| `POST /api/marketing/clientPlaceVisits/cancel` | Cancel CP with remarks | PASS: structured `401` |
| `POST /api/marketing/clientPlaceVisits/convertToSiteVisit` | CP-to-SV conversion | PASS: structured `401` |
| `POST /api/marketing/cp-visits/geofence-remark` | Out-of-geofence reason | PASS: structured `401` |
| `GET /api/marketing/cp-visits/pending-approvals` | Approval queue | PASS: structured `401` |
| `GET /api/marketing/cp-visits/approval-route` | Approval tracking route | PASS: structured `401` |
| `POST /api/marketing/cp-visits/approve` | Approve completion | PASS: structured `401` |
| `POST /api/marketing/cp-visits/reject` | Reject completion | PASS: structured `401` |
| `GET /api/marketing/clientPlaceVisits/joint-workflow` | Owner/reviewer state, role, distance, revision | PASS: structured `401` |
| `POST /api/marketing/clientPlaceVisits/joint-arrival-preflight` | Enforce participant distance under 50 m | PASS: structured `401` |
| `POST /api/marketing/clientPlaceVisits/joint-submit-review` | Lower-level owner sends outcome | PASS: structured `401` |
| `POST /api/marketing/clientPlaceVisits/joint-complete-review` | Higher-level reviewer remarks/completion | PASS: structured `401` |

Android now refreshes `joint-workflow` immediately before
`joint-complete-review`, then submits the latest `outcomeRevision`. This prevents
reviewer edits from causing a stale-revision completion failure.

## Site visit confirmation and counselling

| Endpoint | Mobile purpose | Observed response |
|---|---|---|
| `POST /api/marketing/siteVisits/create` | Create/confirm SV from mobile | PASS: structured `401` |
| `POST /api/marketing/siteVisits/scanQr` | Validate SV QR | PASS: structured `401` |
| `POST /api/marketing/siteVisits/markOnCounselling` | Start counselling | PASS: structured `401` |
| `POST /api/marketing/siteVisits/setOutcome` | Save SV outcome | PASS: structured `401` |
| `POST /api/marketing/siteVisits/postpone` | Postpone SV | PASS: structured `401` |
| `POST /api/marketing/siteVisits/cancel` | Cancel SV with remarks | PASS: structured `401` |
| `POST /api/marketing/siteVisits/convertToBooking` | Convert SV to booking | PASS: structured `401` |
| `POST /api/marketing/siteVisits/markPickedUp` | SV pickup state | PASS: structured `401` |
| `POST /api/marketing/siteVisits/markClientStarted` | Client journey started | PASS: structured `401` |
| `POST /api/marketing/siteVisits/markArrivedSite` | Client arrived at site | PASS: structured `401` |
| `POST /api/marketing/siteVisits/markPickedFromSite` | Return pickup state | PASS: structured `401` |
| `POST /api/marketing/siteVisits/markDropped` | Client dropped/completed transport | PASS: structured `401` |
| `GET /api/sitevisits/filter-options` | SV filters | PASS: structured `401` |

## Booking conversion and review

| Endpoint | Mobile purpose | Observed response |
|---|---|---|
| `GET /api/marketing/projects` | Booking project picker | PASS: structured `401` |
| `GET /api/marketing/inventory-units` | Plot/unit picker | PASS: structured `401` |
| `GET /api/marketing/inventory-units/layout` | Inventory layout view | PASS: structured `401` |
| `GET /api/bookings/plot-prefill` | Plot and pricing prefill | PASS: structured `401` |
| `GET /api/bookings/conversion-prefill` | Existing CP/SV/client prefill | PASS: structured `401` |
| `GET /api/bookings/exchange-source-candidates` | Exchange booking source | PASS: structured `401` |
| `POST /api/bookings` | Submit booking confirmation | PASS: structured `401` |
| `POST /api/bookings/draft/save` | Save outcome form draft | PASS: structured `401` |
| `GET /api/bookings/draft/get` | Restore outcome form draft | PASS: structured `401` |
| `POST /api/bookings/draft/clear` | Clear completed draft | PASS: structured `401` |
| `GET /api/marketing/bookings/my` | Booking list synchronization | PASS: structured `401` |
| `GET /api/bookings/{id}` | Booking detail | **FAIL: structured `500` for malformed ID before auth** |
| `PATCH /api/bookings/{id}` | Edit booking | PASS: structured `401` |
| `POST /api/bookings/{id}/approve` | Approve booking | PASS: structured `401` |
| `POST /api/bookings/{id}/reject` | Reject booking | PASS: structured `401` |

## Collection

| Endpoint | Mobile purpose | Observed response |
|---|---|---|
| `GET /api/postsales/cases/byMobile` | Confirmed booking/case lookup | PASS: structured `401` |
| `GET /api/postsales/cases/list` | Collection case list | PASS: structured `401` |
| `POST /api/postsales/collections/submit` | Submit amount/mode/reference/proof | PASS: structured `401` |
| `GET /api/postsales/collections/my` | Staff collection synchronization | PASS: structured `401` |
| `POST /api/postsales/collections/correct` | Correct rejected collection | PASS: structured `401` |
| `GET /api/postsales/collections/for-accounts` | Accounts review queue | PASS: structured `401` |
| `POST /api/postsales/collections/approve` | Accounts approval | PASS: structured `401` |
| `POST /api/postsales/collections/reject` | Accounts rejection | PASS: structured `401` |

Collection completion also calls `markClientMet`, `setOutcome`, and
`visit/complete`, already listed above.

## Photo and document storage

| Endpoint | Mobile purpose | Observed response |
|---|---|---|
| `POST https://mg.theairix.com/api/storage/uploads` | Create preferred upload contract | PASS: structured `401` |
| `PUT {presigned uploadUrl}` | Upload bytes | Not safely runnable without creating an upload contract |
| `POST https://mg.theairix.com/api/storage/uploads/{fileId}/complete` | Confirm upload | PASS: structured `401` |
| `DELETE https://mg.theairix.com/api/storage/uploads/{fileId}` | Abort failed upload | PASS: structured `401` |
| `GET https://mg.theairix.com/api/storage/files/{storageId}` | Read/display uploaded proof | **FAIL: raw `404`** |
| `POST /api/storage/upload` | MMS compatibility upload fallback | PASS: structured `401` |

Android upload metadata was corrected in this audit: newly compressed images are
sent as JPEG with a `.jpg` filename; untouched PNG/WebP images keep their true
MIME type and extension; PDFs stay `application/pdf` with `.pdf`.

## Functional verification still required

With disposable lower-level/higher-level users, client phone, CP, SV, booking,
and collection case, run one authenticated staging flow to prove:

1. OTP is delivered and verifies only for the intended client.
2. Uploaded bytes can be read back as the original image/PDF.
3. CP and SV outcomes persist every form field and appear identically on web.
4. Joint CP blocks at 50 m or more, owner submits, reviewer edits/remarks, and
   both participant trips/counts become completed.
5. Booking and collection writes are idempotent and list/detail reads match.

Production was not mutated during this audit.
