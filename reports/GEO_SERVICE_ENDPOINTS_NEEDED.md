# geo-tracking-service — endpoints and config still needed

**For:** owner of `manjugroupsdev/geo-tracking-service` (branch `kira`, at `66b5376`)
**From:** mobile — all findings below were probed against the LIVE service, read-only
**Date:** 2026-09-15

Everything here is verified, not assumed. The service itself is healthy:

```
GET /healthz  → 200 {"status":"ok"}
GET /readyz   → 200 {"checks":{"postgres":"ok","redis":"ok"},"status":"ready"}
```

---

## 0. Do these first — neither is an endpoint

### 0.1 SECURITY — the tracking API is publicly readable

Every probe below was sent with **no `Authorization` header and no API key**, from an
unauthenticated shell, and returned real production data:

| Request (no auth) | Result |
|---|---|
| `GET /api/geotrack/live-status` | 200 — live staff ids + session data |
| `GET /api/tracking/live` | 200 — same |
| `GET /api/tracking/trips?staffId=<real id>` | 200 — **4.6 kB of trip records** for a named staff member |

**Anyone with the URL can track the field team's live position and movement history.**

This is consistent with the branch's own recent commits — *"Allow auth-disabled deploy
without API key"* and *"Honor bearer tokens when geotrack auth is disabled"* — so the
deployment is very likely running with geotrack auth disabled. Turn it back on before
anything else on this list.

### 0.2 CONFIG — `GOOGLE_MAPS_SERVER_KEY` is not set

`mapsclient.Client.configured()` (`internal/mapsclient/client.go:255`) returns
`ErrNotConfigured` when the key is blank, so `DrivingRoute` fails on **every** call and
`POST /api/geotrack/route` answers `200 {"success":false,"error":"GOOGLE_MAPS_SERVER_KEY not configured"}`.

Effect: no route line is ever drawn on a trip, on any device. The app now says *"Route line
unavailable. Use Open in Google Maps for directions."* instead of inviting a pointless
retry, but the feature stays dead until the key is set.

---

## 1. Missing routes that are breaking things TODAY

Go's default `http.NotFound` returns `Content-Type: text/plain` with the body
`404 page not found`. Convex then does `JSON.parse` on that
(`convex/lib/geoTrackingService.ts:86`) and reports:

```json
{"success":false,"error":"Geo tracking service returned invalid JSON"}
```

That is the exact banner on the web GeoTrack Live page. Confirmed live:

| Route | Status today | Needed by |
|---|---|---|
| `GET /api/geotrack/session-route` | **404 text/plain** | Android, iOS, web |
| `GET /api/geotrack/stats` | **404 text/plain** | web (Convex proxy) |
| `GET /api/geotrack/bootstrap` | **404 text/plain** | web (Convex proxy) |
| `GET /api/tracking/bootstrap` | **404 text/plain** | web (Convex proxy) |
| `GET /api/geotrack/employee-detail` | absent in source | web (Convex proxy) |

For contrast, these are implemented and returning real data:
`/api/geotrack/live-status`, `/api/tracking/live`, `/api/tracking/trips`,
`/api/geotrack/timeline`, `/api/geotrack/day-status`, `/api/geotrack/nearby-staff`,
`/api/geotrack/route`, `/api/geotrack/geocode-address`, `/api/tracking/places/search`,
`/api/tracking/device/sync`.

> **Two ways to close this, and either is fine:** implement the five routes, or make the
> Convex proxy fall back to its own handler when the upstream 404s. What must NOT stay is
> the current state, where a plain-text 404 is parsed as JSON and surfaces as a broken page.
>
> Whichever you choose, **also return JSON on unknown paths** — a `NotFoundHandler` that
> writes `{"success":false,"error":"unknown route"}` would have made this self-evident
> instead of producing a misleading "invalid JSON".

### 1.1 `GET /api/geotrack/session-route` — full contract

The only endpoint in the app's direct set with no handler. It powers **GeoTrack Live** and
the **attendance review route strip**; both 404 today, which is why they show nothing.

| Query | Type | Notes |
|---|---|---|
| `staffId` | string, optional | defaults to the authenticated staff |
| `dayStart` | int64 | Unix ms, required |
| `dayEnd` | int64 | Unix ms, required |
| `minStopMinutes` | int, optional | the app sends `30` |

```jsonc
// {"success":true,"data": … }
{
  "session": { /* same shape as /api/tracking/sessions/current */ },
  "timeline": [ /* same shape as /api/geotrack/timeline */ ],
  "trips":    [ /* same shape as /api/tracking/trips */ ],
  "stops":    [ /* TripStop, flattened across the day's trips */ ],
  "routeStart": 1789193662184,
  "routeEnd":   1789222462184,
  "distanceMeters": 41230
}
```

**No new SQL required** — every field already has a service method:

| Field | Source |
|---|---|
| `timeline` | `service.Timeline(ctx, staffID, start, end, limit)` |
| `trips` | `service.ListTrips(ctx, TripFilter{StaffID, From, To, Limit})` |
| `session` | `service.CurrentSession(ctx, staffID)` |
| `stops` | `service.GetTrip(ctx, tripID).Stops`, filtered by `minStopMinutes` |
| `distanceMeters` | sum of `trips[].DistanceMeters` |
| `routeStart`/`routeEnd` | min/max of timeline `RecordedAt`, else the query range |

Mirror `GET /api/geotrack/timeline` (`internal/httpapi/server.go:623`) for the guards,
`int64Query` parsing, `developmentStaffID` and `statusForTrackingError`, and register it
beside the others so it inherits the same auth middleware. **Cap the per-trip `GetTrip`
fan-out** — a long day holds dozens of trips and this sits behind a map.

---

## 2. New routes — trip attribution

This is the half that makes "properly linked to CP Trips, On-Duty, geotrack sessions" real.

**Both apps already send the linkage.** Every location point and heartbeat now carries:

```jsonc
{ "...": "existing fields",
  "contextType": "cp_trip",   // attendance | cp_trip | site_visit | on_duty | fleet_trip
  "contextId": "j97..." }     // the Convex trip row id, may be null briefly
```

Stamped at CAPTURE time, so a backlog uploaded hours later keeps the trip it was recorded
during. `contextType` is never absent from a current build; `"attendance"` means no trip is
running. Points from builds older than this carry null for both — treat as unattributed,
not an error.

### 2.1 Segments — one attendance-day session, many trips inside it

```
POST /api/tracking/segments/start
  { staffId, sessionId?, segmentType, refId, refSource, startedAt, lat, lng, meta? }
  → { success, segmentId, sessionId, state }
```

* **Idempotent on `(refSource, refId)`** — the app retries trip starts after ambiguous
  timeouts; a duplicate must return the existing segment.
* No open session (staff not clocked in) → `{ success: true, segmentId: null, reason: "no_open_session" }`,
  **not** an error. A tracking problem must never block a trip start.

```
POST /api/tracking/segments/end
  { segmentId | {refSource, refId}, endedAt, lat, lng, reason }
  → { success, segmentId, distanceMeters, durationSeconds, pointCount,
      source: "snapped"|"raw"|"empty", startPoint, endPoint, polyline? }
```

**This response is the point of the whole exercise.** It hands Convex the authoritative
distance at completion time, so it stops falling back to a straight line. Also idempotent.

```
GET  /api/tracking/segments?staffId=…&date=YYYY-MM-DD
POST /api/tracking/segments/reconcile   { date, staffId? }  → { success, closed, stillOpen }
```

The reconcile sweep matters: the app closes a trip in a second HTTP call that can fail, so
segments will be left open in production.

### 2.2 Telemetry read-back — so Convex stops reading a dead table

Convex still computes trip distance, travel allowance and CP distance by scanning its own
`locationPoints`, which current builds stopped writing to on 2026-09-09. Those numbers
silently degrade to a straight line — a 40 km loop ending near its start records ≈0 m and
drops under the >1 km travel-allowance qualifier.

```
POST /api/tracking/metrics/distance
  { staffId, fromMs, toMs, sessionId?, segmentId? }
  → { success, distanceMeters, durationSeconds, pointCount,
      firstPoint, lastPoint, source: "snapped"|"raw"|"empty" }

POST /api/tracking/metrics/batch            // REQUIRED, not optional
  { windows: [ { key, staffId, fromMs, toMs }, … ] }   // max 100
  → { success, results: { key: { …as above… } } }

POST /api/tracking/metrics/path
  { staffId, fromMs, toMs, maxPoints, preferSnapped }
  → { success, snapped, path: [ { lat, lng, recordedAt }, … ] }

GET  /api/tracking/points/latest?staffId=…&asOfMs=…
  → { success, point: { lat, lng, recordedAt, accuracy } | null }
```

* `source: "empty"` must be explicit — Convex needs to tell "genuinely didn't move" from
  "no data", which is exactly the distinction it cannot make today.
* The **batch** variant is required: travel allowance enriches every trip of a day, and
  sequential calls will time out the Convex action.
* `points/latest` returning `{ point: null }` — **not a 404** — is the end-coordinate
  fallback for a phone that can't get a fix indoors at completion.

---

## 3. Wire contract — timestamps and key names

Go marshals `time.Time` as RFC 3339. The published mobile contract is **epoch
milliseconds**. The mobile decoder throws on that and discards the **entire** payload — not
one field, the whole response.

| Type | Field | Mobile expects | Service sends |
|---|---|---|---|
| `LocationPoint` (`service.go:334`) | `recordedAt` | int64 ms | RFC 3339 |
| `Trip` (`web.go:17`) | id | key `_id` | key `tripId` |
| `Trip` (`web.go:20-21`) | `startedAt`, `endedAt` | int64 ms | RFC 3339 |
| `TripStop` (`web.go:52-53`) | `arrivedAt`, `departedAt` | int64 ms | RFC 3339 |
| `Session` (`service.go:70`) | id | key `_id` | key `sessionId` |
| `Session` (`service.go:75`) | state | key `sessionState` | key `state` |
| `Session` (`service.go:77-78`) | `startedAt`, `endedAt` | int64 ms | RFC 3339 |

**Android is already mitigated** — it accepts either shape and either key spelling
(9 regression tests). **iOS is NOT** and stays broken on trips/session data until this is
fixed here.

Recommended: emit the published contract at the **HTTP seam only**, leaving internal Go
types idiomatic, so existing web consumers are untouched:

* `time.Time` → `t.UnixMilli()`; `*time.Time` → `omitempty` int64 pointer
* **add** `_id` alongside `tripId` / `sessionId`
* **add** `sessionState` alongside `state`

Adding keys rather than renaming keeps it backward compatible.

---

## 4. What does NOT move, and why

These ten stay on Convex. They are *named* geotrack but they read and write Convex business
tables, and `kira` has no route, table or concept for any of them — I grepped the branch for
`visit/start`, `visit/complete`, `on-duty`, `arrival-otp`, `assigned-places`, `today-visits`
and `clientPlaceVisit`: **all absent**.

| Endpoint | Actually touches |
|---|---|
| `geotrack/assigned-places` | Convex `clientPlaces` |
| `geotrack/today-visits` | Convex `fieldVisits` |
| `geotrack/visit/create` · `start` · `complete` | CP visits + field visits |
| `geotrack/visit/arrival-otp/request` · `verify` · `cancel` | client OTP, tied to the CP and the client's phone |
| `geotrack/on-duty/start` · `complete` | creates/closes `geoTrips` rows |

Moving them means migrating CP visits, field visits and geoTrips out of Convex — a data
migration, not a routing change, and it would take the marketing and HR modules with it.

**The split that satisfies the requirement:** the geo service owns the *tracking truth*
(points, sessions, segments, distance); Convex keeps the *business record* and asks the
service for the numbers via §2. The per-point `contextType`/`contextId` plus the segment
endpoints are what tie the two together.

> **Separately:** the tracking traffic still visible on `api-mfpl` in the Cloudflare report
> is **legacy app builds** (versionCode ≤ 71). Nothing in the current Android app, iOS app
> or web calls those paths — they were removed by `e54016d7` on 2026-09-09. Raise the
> minimum version via the existing `/api/mobile/app-version` gate (published is 83, safe
> floor is 72) and that traffic decays on its own. Do not delete the Convex routes first, or
> un-upgraded phones lose tracking silently.

---

## 5. Also outstanding — two Convex routes (not this repo)

The CP arrival-OTP reveal mutations and the IAM key already exist; only the mobile HTTP
routes are missing. Both apps are built against them and stay inert until registered.

```
POST /api/marketing/cp-visits/reveal-otp
  { sourceId, sourceType: "client_place_visit" }
  → api.hr.fieldVisitOtp.revealActiveOtpForSuperAdmin
POST /api/marketing/cp-visits/reveal-otp/copied
  { fieldVisitId }
  → api.hr.fieldVisitOtp.recordOtpAssistCopied
```

Authenticate, forward `sessionToken`, add **no** permission logic of their own — the
mutations already gate themselves and must stay the only gate.

---

## Checklist

- [ ] **Re-enable auth** on the tracking API (§0.1) — live data exposure.
- [ ] Set `GOOGLE_MAPS_SERVER_KEY` (§0.2).
- [ ] Return JSON, not plain text, from the 404 handler.
- [ ] `GET /api/geotrack/session-route` implemented, stops fan-out capped.
- [ ] `stats`, `geotrack/bootstrap`, `tracking/bootstrap`, `employee-detail` implemented, **or** the Convex proxy falls back on 404.
- [ ] `segments/start`, `segments/end`, `segments`, `segments/reconcile` — idempotent.
- [ ] `metrics/distance`, `metrics/batch`, `metrics/path`, `points/latest`.
- [ ] Mobile-facing payloads emit int64 ms; `_id` and `sessionState` added alongside.
- [ ] `make check` green (required by `AGENTS.md`).
- [ ] Verified on a device: GeoTrack Live and the attendance route strip draw a path.
