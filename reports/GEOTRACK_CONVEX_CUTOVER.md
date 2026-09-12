# GeoTrack → Convex cutover: endpoints needed

**For:** backend / Convex admin
**From:** mobile (Android `merge`, iOS `darx`) — audit only, no web/Convex file was edited
**Date:** 2026-09-12
**Target:** complete before Sunday

---

## 0. TL;DR

Three separate things are mixed together in the current picture:

| # | Finding | Severity |
|---|---------|----------|
| A | Convex still computes trip distance / travel allowance / CP distance by scanning `locationPoints` — a table current app builds **stopped writing to on 2026-09-09**. Those numbers are silently degrading to straight-line start→end. | **Critical — affects money (travel allowance)** |
| B | The tracking traffic still hitting `api-mfpl.theairix.com` is **legacy app builds** (versionCode < 72), not current code. Nothing in the current Android app, iOS app or web calls those paths. | Medium — decays with rollout, but hides A |
| C | A GeoTrack session only ever carries `contextType: "attendance"`. **CP trips and On-Duty trips were not linked to the session at all**, so the geo service could not attribute points to them. **Fixed on both apps — see §7 for what is now on the wire.** | **Was blocking A's fix** |

Fixing A properly requires C. The endpoints for both are specified in §4 and §5.

---

## 1. Current architecture (as actually deployed)

```
                 ┌───────────────────────────────────────────┐
   Mobile ──────►│ api-geo.theairix.com   (GeoTrack service) │  raw telemetry
   (v72+)        │  points · heartbeat · tamper · sessions    │  system of record
                 └───────────────────────────────────────────┘
                          ▲  /api/tracking/trips/sync
                          │  /api/tracking/sessions/sync      (Convex → geo, X-API-Key)
                 ┌────────┴──────────────────────────────────┐
   Mobile ──────►│ api-mfpl.theairix.com  (Convex)           │  business records
   Web    ──────►│  geoTrips · trackingSessions · CP visits   │  system of record
                 │  attendance · travelAllowance             │
                 └───────────────────────────────────────────┘
```

The split is correct and should be kept. **The bug is that the arrow only goes one way.**
Convex pushes trips/sessions *out* to the geo service, but when Convex needs the
*telemetry* (distance, path, last known point) it still reads its own
`locationPoints` table — which no longer receives anything from upgraded phones.

### What each side calls today (verified in source)

**Mobile → api-geo (direct, `Authorization: Bearer <session token>`)** — already migrated,
see `GeoTrackApi.kt:707-722`:

```
POST /api/tracking/location/batch
POST /api/tracking/heartbeat
POST /api/tracking/tamper-events
POST /api/tracking/sessions/start
GET  /api/tracking/sessions/current
POST /api/tracking/sessions/end
GET  /api/tracking/live
GET  /api/tracking/trips
GET  /api/tracking/places/search
GET  /api/geotrack/day-status
GET  /api/geotrack/timeline
GET  /api/geotrack/session-route
GET  /api/geotrack/nearby-staff
POST /api/geotrack/route
POST /api/geotrack/geocode-address
```

**Mobile → Convex (business mutations — these should STAY on Convex):**

```
GET  /api/geotrack/assigned-places
GET  /api/geotrack/today-visits
POST /api/geotrack/visit/create
POST /api/geotrack/visit/start
POST /api/geotrack/visit/complete
POST /api/geotrack/visit/arrival-otp/request | /verify | /cancel
POST /api/geotrack/on-duty/start
POST /api/geotrack/on-duty/complete
```

These mutate CP visits, field visits, geoTrips and attendance. Moving them would
break working modules for no benefit. **Do not migrate these.** They only need to
start *reporting the link* to the geo service (§5).

---

## 2. Finding A — Convex reads a stream that is no longer written (CRITICAL)

`locationPoints` in Convex has been starved since commit `e54016d7`
("fix(geotrack): use direct tracking service", 2026-09-09, versionCode 72), which
repointed the app's batch/heartbeat/tamper writes at `api-geo`.

Every one of these still scans it:

| File | Line | What breaks when the stream is empty |
|---|---|---|
| `convex/geotrack/trips.ts` | 609 | `completeOnDutyTrip` — distance falls back to **straight-line start→end** |
| `convex/geotrack/trips.ts` | 329 | `buildDailyTrips` — daily trip segmentation produces nothing |
| `convex/travelAllowance.ts` | 505 | trip polyline falls back to empty path |
| `convex/marketing/clientPlaceVisits.ts` | 677, 983, 1224, 10788 | CP trip actual-distance → straight line |
| `convex/hr/fieldVisits.ts` | 1016 | field-visit trail |
| `convex/geotrack/monitoring.ts` | 268 | monitoring/alerting blind |
| `convex/geotrack/roads.ts` | 201, 337 | Roads API snapping has nothing to snap |
| `convex/adminCpVisitRepair.ts` | 2139 | repair tool reconstructs wrong distances |

**Why this matters commercially.** `completeOnDutyTrip` (`trips.ts:638-641`) does:

```ts
if (distanceMeters === 0) {
  distanceMeters = Math.round(haversineDistance(trip.startLat, trip.startLng, endLat, endLng));
}
```

A staff member who drives 40 km on a loop and ends near where they started now
records **≈0 m**, silently falls under the >1 km travel-allowance qualifier, and
is not paid. There is no error and no log — it looks like a short trip.

> This is the single most urgent item. It is already live and already affecting
> every staff member on versionCode ≥ 72.

---

## 3. Finding B — the leftover Convex tracking traffic is legacy app builds

From the Cloudflare report for `api-mfpl.theairix.com` (last 24 h):

| Path | Requests | Called by current Android? | Current iOS? | Web? |
|---|---|---|---|---|
| `/api/geotrack/tamper/report` | 16.36k | no | no | no |
| `/api/tracking/bootstrap` | 4.28k | no | no | no |
| `/api/tracking/device/sync` | 4.26k | no | no | no |
| `/api/tracking/heartbeat` | 404 | no (uses api-geo) | no (uses api-geo) | no |

All four existed in the app **before** `e54016d7` and were removed by it. Nothing
in any of the three current codebases references them (grepped: `app/src/main/java`,
`FoundationChat/`, and the web repo excluding `convex/http.ts` itself).

**Conclusion: these are phones still running versionCode ≤ 71.** They are writing
telemetry into Convex that nothing reads any more, and their route/distance data
lives in the wrong database.

### Action

1. **Do not delete these Convex routes yet** — deleting them makes legacy phones
   fail silently and lose tracking entirely until the user updates.
2. Raise the minimum supported build via the existing gate
   `GET /api/mobile/app-version` (already called by the app, 1.02k req/24 h) so
   legacy installs are forced to update. Current published versionCode is **83**;
   the safe floor is **72** (first build with direct tracking).
3. Watch the four paths decay to ~0, then retire them. Suggested order once at
   zero for 48 h: `tracking/device/sync` → `tracking/bootstrap` →
   `geotrack/tamper/report` → `tracking/heartbeat`.
4. Optional but recommended: have the legacy routes **forward** to the geo service
   instead of writing `locationPoints`, so telemetry from un-upgraded phones is not
   stranded during the rollout window.

---

## 4. Finding C — sessions carried no CP-trip / On-Duty context (app side now fixed)

> **Status:** the mobile half is done on Android and iOS; §7 documents the fields
> the service will now receive. The rest of this section is the background, and
> still applies to phones on older builds.

The app starts exactly one tracking session per attendance day
(`GeoTrackBootstrapSync.kt`), and it always sends:

```kotlin
DirectTrackingStartRequest(
    deviceId,
    contextType = "attendance",   // ← never anything else
    contextId   = attendanceId,   // ← only ever the attendance row id
    source      = "mconnect",
    trigger     = "attendance_punch_in",
    startedAt, lat, lng, batteryPct,
)
```

Call sites: `HomeViewModel.kt:328`, `AttendanceFlowViewModel.kt:450`,
`PunchSyncWorker.kt:101`. There is **no** call anywhere that passes
`contextType = "cp_trip"` or `"on_duty"`.

So when a CP trip starts (`/api/geotrack/visit/start` → Convex) or an on-duty trip
starts (`/api/geotrack/on-duty/start` → Convex), the geo service is never told.
It has the points, but no way to say which of them belong to that trip.

**This is why A cannot simply be fixed by "ask the geo service for the distance" —
there is nothing to key the question on except a raw time window.**

---

## 5. Endpoints needed

Two groups. Group 1 unblocks the money bug; Group 2 makes the linkage real.
Both are on the **GeoTrack service** (`api-geo.theairix.com`).

Auth convention, matching what already exists in `convex/lib/geoTrackingService.ts`:

* **Convex → geo:** header `X-API-Key: $GEO_API_KEY`, plus `X-Request-ID` and
  `Idempotency-Key` where noted.
* **Mobile → geo:** header `Authorization: Bearer <MMS session token>` (same token
  the app already uses on `/api/tracking/*`).

All responses: `200` with `{ "success": true, ... }` or `{ "success": false, "error": "..." }`.

---

### Group 1 — telemetry read-back (Convex → geo, `X-API-Key`)

These let every consumer in the §2 table stop reading `locationPoints`.

#### 1.1 `POST /api/tracking/metrics/distance`

Authoritative distance for a staff member over a time window.

```jsonc
// request
{
  "staffId":  "k57...",        // required, MMS staff id
  "fromMs":   1757600000000,   // required, inclusive
  "toMs":     1757612345000,   // required, inclusive
  "sessionId": "…",            // optional, narrows to one tracking session
  "segmentId": "…"             // optional, see 2.1 — preferred when available
}
```

```jsonc
// response
{
  "success": true,
  "distanceMeters": 41230,     // haversine sum over the filtered stream
  "durationSeconds": 4210,
  "pointCount": 812,
  "firstPoint": { "lat": 13.08, "lng": 80.27, "recordedAt": 1757600100000 },
  "lastPoint":  { "lat": 13.11, "lng": 80.31, "recordedAt": 1757612000000 },
  "source": "snapped" | "raw" | "empty"
}
```

* `source: "empty"` must be returned explicitly when there are no points — Convex
  needs to distinguish "genuinely didn't move" from "no data", because right now
  those two collapse into the same straight-line fallback.
* Apply the same accuracy filtering the service already uses for its own trip
  rollups, so the number matches what the admin console shows.

#### 1.2 `POST /api/tracking/metrics/batch`

Same as 1.1 for many windows in one round trip. Required — `travelAllowance.ts`
enriches every trip of a day, and N sequential calls will time out the Convex action.

```jsonc
// request
{ "windows": [ { "key": "trip_abc", "staffId": "k57...", "fromMs": 1, "toMs": 2 }, … ] }  // max 100
// response
{ "success": true, "results": { "trip_abc": { /* the 1.1 body, minus success */ }, … } }
```

#### 1.3 `POST /api/tracking/metrics/path`

Polyline for map rendering (replaces `travelAllowance.ts:505` and the
`session-route` fallbacks).

```jsonc
// request
{ "staffId": "k57...", "fromMs": 1, "toMs": 2, "maxPoints": 200, "preferSnapped": true }
// response
{ "success": true, "snapped": true, "path": [ { "lat": 13.08, "lng": 80.27, "recordedAt": 1757600100000 }, … ] }
```

* `maxPoints` must be honoured server-side by even sampling — Convex currently
  strides client-side over up to 2 000 rows, which is what makes that query heavy.

#### 1.4 `GET /api/tracking/points/latest?staffId=…&asOfMs=…`

Last known fix at or before `asOfMs`. Used for the "end coordinate" fallback in
`trips.ts:620-623` and `clientPlaceVisits.ts` when the phone cannot get a fix at
completion (indoors / in a vehicle).

```jsonc
{ "success": true, "point": { "lat": 13.11, "lng": 80.31, "recordedAt": 1757612000000, "accuracy": 12.4 } }
```

Return `{ "success": true, "point": null }` when there is none — not a 404.

---

### Group 2 — trip ↔ session linkage (segments)

The right model: **one session per attendance day, many segments inside it.**
A CP trip and an on-duty trip are segments of the shift, not separate sessions —
that keeps a single continuous point stream and avoids gaps at trip boundaries.

#### 2.1 `POST /api/tracking/segments/start`

Called by **Convex**, from inside the existing handlers, so the app contract does
not change and old builds keep working:

* `api.geotrack.trips.startOnDutyTrip` → `segmentType: "on_duty"`
* `/api/geotrack/visit/start` → `segmentType: "cp_trip"`
* site-visit start → `segmentType: "site_visit"`

```jsonc
// request  — X-API-Key + Idempotency-Key: segment-start:<refSource>:<refId>
{
  "staffId":     "k57...",
  "sessionId":   "…",            // optional; service resolves the open session for staffId when omitted
  "segmentType": "cp_trip",      // "cp_trip" | "on_duty" | "site_visit" | "attendance"
  "refId":       "j97...",       // the Convex row id (geoTrips._id / fieldVisits._id)
  "refSource":   "convex.geoTrips",
  "startedAt":   1757600000000,
  "lat": 13.08, "lng": 80.27,
  "meta": { "cpVisitId": "…", "onDutyCategory": "Projects" }   // free-form, optional
}
// response
{ "success": true, "segmentId": "seg_…", "sessionId": "…", "state": "open" }
```

* Must be **idempotent on `(refSource, refId)`** — the app retries trip starts
  after ambiguous timeouts, and a duplicate must return the existing segment,
  not create a second one.
* If no open session exists (staff not clocked in / tracking off), return
  `{ "success": true, "segmentId": null, "reason": "no_open_session" }` rather
  than an error, so a trip start is never blocked by a tracking problem.

#### 2.2 `POST /api/tracking/segments/end`

**This is the call that fixes Finding A.** It closes the segment and returns the
authoritative distance in the same response, so Convex can write a correct
`distanceMeters` at completion time instead of scanning an empty table.

```jsonc
// request
{
  "segmentId": "seg_…",             // or  { "refSource": "…", "refId": "…" }
  "endedAt":   1757612345000,
  "lat": 13.11, "lng": 80.31,
  "reason":    "trip_completed"
}
// response
{
  "success": true,
  "segmentId": "seg_…",
  "distanceMeters": 41230,
  "durationSeconds": 4210,
  "pointCount": 812,
  "source": "snapped" | "raw" | "empty",
  "startPoint": { "lat": 13.08, "lng": 80.27 },
  "endPoint":   { "lat": 13.11, "lng": 80.31 },
  "polyline":   "encoded…"          // optional, encoded polyline for the trip card
}
```

* Also idempotent: ending an already-ended segment returns the stored result.

#### 2.3 `GET /api/tracking/segments?staffId=…&date=YYYY-MM-DD`

Read-back for the HR attendance modal's Trips strip and for reconciliation.

```jsonc
{ "success": true, "segments": [ { "segmentId": "…", "segmentType": "cp_trip", "refId": "…",
  "startedAt": 1, "endedAt": 2, "distanceMeters": 41230, "state": "closed" }, … ] }
```

#### 2.4 `POST /api/tracking/segments/reconcile`

Nightly repair for segments left open by a crashed app or a lost completion call
(this happens today — the app closes a trip in a second HTTP call that can fail).

```jsonc
// request
{ "date": "2026-09-12", "staffId": "…" }   // staffId optional = whole org
// response
{ "success": true, "closed": 12, "stillOpen": 1 }
```

---

## 6. Convex-side changes that go with these

Listed for completeness — **all in web/Convex, none in mobile.**

1. Replace every `ctx.db.query("locationPoints")` in the §2 table with the matching
   Group 1 call, via the existing `requestGeoTrackingService()` helper.
   * `trips.ts:609` (`completeOnDutyTrip`) and `clientPlaceVisits.ts:983` are the
     two that must land first — they are the ones writing wrong money numbers.
2. Call `segments/start` / `segments/end` from the existing trip handlers (§2.1).
3. Keep writing `geoTrips` / `trackingSessions` in Convex and keep the existing
   `postgresSync.syncTrip` / `syncSession` push — the mirror stays.
4. Keep the straight-line fallback, but only when the service answers
   `source: "empty"`, and **stamp the row** (`distanceSource: "straightline"`) so
   these are auditable rather than invisible.
5. Raise the minimum app version (§3, action 2).

---

## 7. Mobile side — DONE, already sending the linkage

Android (`merge`) and iOS (`darx`) now tag every GeoTrack point and heartbeat
with the trip it belongs to. **No endpoint change is needed to accept this** —
the fields are additive and a service that does not read them ignores them — but
they are what makes §5.1 and §5.2 answerable per trip instead of per time window.

### What is now on the wire

`POST /api/tracking/location/batch` — each entry of `points[]` gains:

```jsonc
{ "...": "existing point fields",
  "contextType": "cp_trip",     // "attendance" | "cp_trip" | "site_visit" | "on_duty" | "fleet_trip"
  "contextId":   "j97..." }     // the Convex row id for that trip, or null
```

`POST /api/tracking/heartbeat` gains the same two fields.

### Rules the service can rely on

* `contextType` is **never** absent on a point captured by a current build; it is
  `"attendance"` when no trip is running.
* `contextId` may be null while a trip id is still being fetched (on-duty learns
  its `geoTrips._id` only when `/api/geotrack/on-duty/start` answers). The type is
  still correct in that window, so those points are attributable to *an* on-duty
  trip even before the id lands.
* **Both are stamped at CAPTURE time, not at upload.** A backlog flushed hours
  later still carries the trip it was recorded during. Do not infer attribution
  from the batch's arrival time.
* An unknown activity kind degrades to `"attendance"` rather than inventing a
  type, so the service will never receive a `contextType` outside the five above.
* Points buffered by a build older than this carry `null` for both and still
  flush — treat null as "unattributed", not as an error.

### Where it comes from

One source of truth per platform, set when a trip starts and cleared when it ends:

| | Android | iOS |
|---|---|---|
| store | `SessionManager.fieldActivity()` (+ new `refId`) | `TrackingContextStore` (UserDefaults) |
| mapping | `geotrack/TrackingContext.kt` | `Services/GeoTrackTrackingContext.swift` |
| buffer column | Room `pending_points.contextType/contextId` (v7, additive) | Core Data `PendingLocationPoint` (lightweight migration) |

Wired at: CP/SV trip start and completion, on-duty start and completion, fleet
driver trip start, and punch-out. On-duty attaches its trip id when the backend
call returns, so the whole trip is attributed rather than just the part after the
response.

### Still worth doing server-side

The segment endpoints in §5.2 are **still wanted** even though the app now sends
context, for one reason: phones that are never updated. A staff member on an old
build sends no context at all, and only a Convex-side `segments/start|end` call
can attribute their trips. The two mechanisms agree — `segments/end` gives the
authoritative number, and the per-point context lets the service compute it
without a time-window guess.

## 8. Verification checklist (before Sunday)

- [ ] `POST /api/tracking/metrics/distance` returns a non-zero distance for a staff
      member who is known to have travelled today, and `source: "raw"` or `"snapped"`.
- [ ] Complete one on-duty trip end-to-end; `geoTrips.distanceMeters` matches the
      geo service's number, not the straight-line figure.
- [ ] Complete one CP trip; the CP visit's actual distance matches.
- [ ] Travel-allowance dialog draws a real polyline, not a two-point line.
- [ ] A trip started and completed twice (simulating a retry) creates **one**
      segment, not two.
- [ ] Cloudflare: `/api/geotrack/tamper/report`, `/api/tracking/bootstrap`,
      `/api/tracking/device/sync`, `/api/tracking/heartbeat` on `api-mfpl` trending
      to zero after the min-version bump.
- [ ] No change in request volume to the eight Convex business routes listed in §1
      (`assigned-places`, `today-visits`, `visit/*`, `on-duty/*`) — if those move,
      something was migrated that should not have been.

---

## Appendix — two unrelated routes also needed (CP arrival-OTP reveal)

Separate feature, same admin. The Convex mutations and IAM key already exist
(`marketing.cpVisits.revealOtp`, default for AVP, hierarchy-scoped and audited in
`convex/hr/fieldVisitOtp.ts`); only the mobile HTTP routes are missing. The apps
are already built against them and stay inert until these are registered.

```
POST /api/marketing/cp-visits/reveal-otp
  body { sourceId, sourceType: "client_place_visit" }
  → api.hr.fieldVisitOtp.revealActiveOtpForSuperAdmin { sourceType, sourceId, sessionToken }

POST /api/marketing/cp-visits/reveal-otp/copied
  body { fieldVisitId }
  → api.hr.fieldVisitOtp.recordOtpAssistCopied { fieldVisitId, sessionToken }
```

Both: authenticate, forward `sessionToken`, add **no** permission logic of their
own — the mutations already gate themselves and must stay the only gate.
