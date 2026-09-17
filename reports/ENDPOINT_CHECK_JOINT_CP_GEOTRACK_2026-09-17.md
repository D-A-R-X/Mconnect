# Joint CP + GeoTrack: live endpoint check

**Date:** 2026-09-17
**Scope:** every endpoint the Android app calls for Joint CP / CP visits and GeoTrack (iOS calls the same paths)
**Method:** live probes against production. Nothing was written to either server:
- **Convex (`api-mfpl.theairix.com`):** unauthenticated. Every POST handler checks auth before doing anything, so a **401** means the route exists and is protected, and **404** means the route is missing.
- **Geo (`api-geo.theairix.com`):** no POSTs to write routes, because auth is switched off there and a POST would really write data. Instead I sent a GET to each POST-only route: **405** means the route exists, **404** means it's missing. Read routes and the two routes that only compute (route, geocode) were called for real.

---

## Summary

| | Total | Working | Broken |
|---|---|---|---|
| Convex: Joint CP / CP / visit / on-duty | 33 | 31 | **2** |
| Geo service: tracking / route / timeline | 15 | 14 | **1** |
| Geo routes the **web** proxy calls | 4 | 0 | **4** |

**Joint CP create, OTP, outcome, review and complete all work.** The only Joint CP/CP routes missing are the two OTP-reveal routes.

**Mobile tracking works:** session start/end, location batch, heartbeat and tamper events are all registered. Mobile staff-route maps and web Geo Track Live are broken.

---

## 1. Broken: MMS web backend (Convex)

### 1.1 CP arrival-OTP reveal: routes not registered

```
POST /api/marketing/cp-visits/reveal-otp          → 404 "No matching routes found"
POST /api/marketing/cp-visits/reveal-otp/copied   → 404 "No matching routes found"
```

**Effect:** the "Reveal OTP" action for AVP/GM does nothing. When a route 404s the app hides the button for the rest of that session, so users see no error. Staff still have to ask the tech team for the OTP.

**Fix:** register the two routes as specified in `MMS_WEB_BACKEND_ENDPOINTS_NEEDED.md` §3:

```
POST /api/marketing/cp-visits/reveal-otp
  body { sourceId, sourceType: "client_place_visit" }
  → api.hr.fieldVisitOtp.revealActiveOtpForSuperAdmin { sourceType, sourceId, sessionToken }

POST /api/marketing/cp-visits/reveal-otp/copied
  body { fieldVisitId }
  → api.hr.fieldVisitOtp.recordOtpAssistCopied { fieldVisitId, sessionToken }
```

Authenticate the request and forward `sessionToken`. Add no permission check here: the mutations already check permissions and must stay the only check.

### 1.2 Web Geo Track Live: the proxy calls geo routes that don't exist

The Convex proxy forwards these to the geo service, and all four answer a plain-text 404:

```
GET /api/geotrack/stats            → 404 text/plain "404 page not found"
GET /api/geotrack/bootstrap        → 404 text/plain
GET /api/tracking/bootstrap        → 404 text/plain
GET /api/geotrack/employee-detail  → 404 text/plain
```

The proxy then tries to parse that text as JSON, which gives `{"success":false,"error":"Geo tracking service returned invalid JSON"}`.

**Fix:** remove these from the proxy and restore the original Convex handlers. They read the Convex `staff`, `projects` and `geoTrips` tables, which the geo service doesn't have, so a geo deploy can't fix them. Also keep `assigned-places` and `today-visits` out of the proxy. Both answer 401 on Convex today, which is correct. Full reasoning is in `GEOTRACK_LIVE_STILL_BROKEN_2026-09-15.md` §2.

---

## 2. Broken: geo service (`kira` branch is merged but not deployed)

### 2.1 `session-route` missing on production

```
GET /api/geotrack/session-route?staffId=…&dayStart=…&dayEnd=…
  → 404 text/plain "404 page not found"
```

`session-route` is in `53b954f` on `kira`. If that build were running, this would answer 200/400 JSON, and unknown paths would get a JSON 404. Both still come back as plain text, so production is still running the old build.

**Effect on mobile today:**

| Screen | What users see |
|---|---|
| Geo Track Live → pick a staff member | Empty map, toast "Failed to load staff route" |
| Attendance review → map | No route line; GPS points / trips / distance stay blank |

**Fix:** run `make check` on the merged branch, then rebuild and redeploy `api-geo.theairix.com`. To confirm, re-run the probe above: a JSON response instead of plain text means the new build is live.

---

## 3. Working: confirmed live

### Convex: 31 routes, all 401 (exist and require auth)

**Joint CP workflow**
`GET  clientPlaceVisits/joint-workflow` ·
`POST clientPlaceVisits/joint-arrival-preflight` ·
`POST clientPlaceVisits/joint-participant-ready` ·
`POST clientPlaceVisits/joint-submit-review` ·
`POST clientPlaceVisits/joint-complete-review`

**CP visits**
`GET clientPlaceVisits/my` · `get` · `completed-count` · `filter-options` ·
`POST clientPlaceVisits/create` · `cancel` · `markClientMet` · `setOutcome` · `referral` · `convertToSiteVisit`

**Approvals / OTP assist**
`GET cp-visits/approval-route` · `pending-approvals` ·
`POST cp-visits/approve` · `reject` · `geofence-remark` · `otp-assist`

**Visit trip + arrival OTP**
`POST geotrack/visit/create` · `visit/start` · `visit/complete` ·
`visit/arrival-otp/request` · `arrival-otp/verify` · `arrival-otp/cancel`

**On-duty**
`POST geotrack/on-duty/start` · `on-duty/complete`

**Lists (correctly still on Convex)**
`GET geotrack/assigned-places` · `geotrack/today-visits`

### Geo service: 14 routes

| Route | Result |
|---|---|
| `POST /api/tracking/sessions/start` | 405 on GET, so it exists |
| `POST /api/tracking/sessions/end` | exists |
| `POST /api/tracking/location/batch` | exists |
| `POST /api/tracking/heartbeat` | exists |
| `GET /api/tracking/sessions/current` | 400 "staffId is required", so it exists |
| `GET /api/tracking/tamper-events` | 200, real rows |
| `GET /api/tracking/live` | 200, real rows |
| `GET /api/tracking/trips` | 200, real rows |
| `GET /api/geotrack/live-status` | 200, real rows |
| `GET /api/geotrack/timeline` | 200 JSON |
| `GET /api/geotrack/day-status` | 200, real rows |
| `GET /api/geotrack/nearby-staff` | 200 JSON |
| `POST /api/geotrack/route` | **200, real polyline** (6.8 km / 28 min test route) |
| `POST /api/geotrack/geocode-address` | **200**, resolved "MG Road Bengaluru" |
| `GET /api/tracking/places/search?q=` | **200**, real Google place |
| `GET /readyz` | postgres ok, redis ok |

> **Change since 15 Sep:** `GOOGLE_MAPS_SERVER_KEY` is now set. Road route lines, geocoding and place search work, so the *"Route line unavailable"* toast should stop appearing.

---

## 4. Still open: security (not a broken endpoint)

**The geo service still requires no authentication.** Every geo probe above was made with no token. Live staff positions (`/api/tracking/live`, `/api/geotrack/live-status`), trip history and tamper events can be read by anyone who has the URL. Turn off `GEO_AUTH_DISABLED` after the web proxy and the apps have been confirmed to send auth.

---

## Checklist

| # | Owner | Action | Confirm with |
|---|---|---|---|
| 1 | Web/Convex | Register `reveal-otp` and `reveal-otp/copied` | unauthenticated POST returns 401, not 404 |
| 2 | Web/Convex | Remove `stats`, `bootstrap` (both), `employee-detail` from the geo proxy | web Geo Track Live tiles show non-zero counts |
| 3 | Geo | `make check`, then deploy the merged `kira` build | `session-route` returns JSON, not plain text |
| 4 | Geo | Turn off `GEO_AUTH_DISABLED` after 1–3 | unauthenticated `GET /api/tracking/live` returns 401 |

No app change is needed for any of these. Android and iOS already call the correct paths.
