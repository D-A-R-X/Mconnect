# Dev GeoTrack: staff clocked in but not "Online"

**For:** geo-service admin and web/Convex admin
**From:** mobile. No web, Convex or Go source has been edited by mobile.
**Date:** 2026-09-17
**Environment:** dev
- `dev-mg.theairix.com`
- `dev-cvx-http-mg.theairix.com`
- `dev-api-geo.theairix.com`

**Code checked:**
- MMS `development-testing` @ `f35f6f69`, pulled today
- geo service `main` @ `20778a9`

---

## Short answer

**Not a mobile bug.** The app clocked in and asked the geo service for its tracking session. The geo service could not work out **who the phone is**, so it refused, and no tracking session was ever created.

Three backend problems stack on each other:

| # | Problem | Side | Effect |
|---|---|---|---|
| **1** | Dev geo service checks login tokens against **production** Convex | Geo service config | Every dev login is rejected, so no dev phone can track |
| **2** | The token check requires the **"view GeoTrack Live"** permission | Convex | Ordinary field staff are rejected even with a correct URL |
| **3** | The web page's roster and counts go to geo routes that don't exist | Convex | Web shows 0 Online and "No staff found" even when data exists |

All three must be fixed for the dev web page to show the staff member as Online.

---

## Evidence

**1. The phone's own network log** (dev build, clocked in, captured today at 16:19):

```
GET https://dev-api-geo.theairix.com/api/tracking/sessions/current   → 400  (5 times)
{"error":"staffId is required while authentication is disabled","success":false}
```

The geo service runs with `GEO_AUTH_DISABLED=true`. In that mode it first tries to turn the bearer token into a staff ID. When that fails, it looks for a `staffId` in the request, finds none, and returns this 400. The app only creates a session once this call succeeds, so tracking never starts.

**2. The dev geo database is empty.** It isn't only this phone:

```
GET https://dev-api-geo.theairix.com/api/tracking/live     → {"data":[],"success":true}
GET https://dev-api-geo.theairix.com/api/tracking/trips    → {"data":[],"success":true}
```

**3. The same phone, same minute, on Convex:**

```
GET https://dev-cvx-http-mg.theairix.com/api/geotrack/assigned-places  → 404 {"success":false,"error":"Geo tracking service returned invalid JSON"}
GET https://dev-cvx-http-mg.theairix.com/api/geotrack/today-visits     → 404 same
```

---

## Fix 1 (geo service): point token checks at the dev backend

**Where:** `internal/config/config.go:72`

```go
AuthIntrospectionURL: valueOrDefault("AUTH_INTROSPECTION_URL",
    "https://api-mfpl.theairix.com/api/auth/geotrack-access"),   // ← production
```

The geo service sends every phone's bearer token to this URL to learn the staff ID. A token issued by **dev** Convex is unknown to **production** Convex, so it's always rejected.

**It can't be overridden today.** `compose.prod.yaml` passes only these auth variables into the container:

```yaml
GEO_API_KEY: ${GEO_API_KEY:-}
GEO_AUTH_DISABLED: ${GEO_AUTH_DISABLED:-false}
```

It does not pass `AUTH_INTROSPECTION_URL`, `AUTH_JWKS_URL`, `AUTH_JWT_ISSUER`, `AUTH_JWT_AUDIENCE` or `ALERT_WEBHOOK_URL`. So the dev deployment silently uses the production defaults, whatever is in its `.env`.

### Change

`compose.prod.yaml`, in the service's `environment:` block:

```yaml
AUTH_INTROSPECTION_URL: ${AUTH_INTROSPECTION_URL:-https://api-mfpl.theairix.com/api/auth/geotrack-access}
AUTH_JWKS_URL: ${AUTH_JWKS_URL:-}
AUTH_JWT_ISSUER: ${AUTH_JWT_ISSUER:-}
AUTH_JWT_AUDIENCE: ${AUTH_JWT_AUDIENCE:-}
ALERT_WEBHOOK_URL: ${ALERT_WEBHOOK_URL:-https://api-mfpl.theairix.com/api/internal/geotrack/alert}
```

Dev deployment `.env`:

```env
AUTH_INTROSPECTION_URL=https://dev-cvx-http-mg.theairix.com/api/auth/geotrack-access
ALERT_WEBHOOK_URL=https://dev-cvx-http-mg.theairix.com/api/internal/geotrack/alert
CORS_ALLOWED_ORIGINS=https://dev-mg.theairix.com
```

- **Use `dev-cvx-http-mg`, not `dev-cvx-mg`.** `dev-cvx-mg` is the web's live-connection host and returns 404 for every HTTP route.
- Production keeps its defaults, so nothing changes there.

Then:
1. Redeploy / restart `dev-api-geo`.
2. Clear cached denials. The service caches a rejected token for 5 minutes under `geotrack:auth:*` in Redis. Either wait 5 minutes or run:
   ```bash
   redis-cli --scan --pattern 'geotrack:auth:*' | xargs -r redis-cli del
   ```

---

## Fix 2 (Convex): token check must not require the manager permission

**Where:** `convex/http.ts:4757`, `GET /api/auth/geotrack-access`, the endpoint from Fix 1.

```ts
const denied = await ensureGeoTrackLiveAccess(ctx, auth.user);   // :4782
```

and `ensureGeoTrackLiveAccess`:

```ts
const allowed =
  iam?.isAdmin === true ||
  iam.permissions.includes("attendance.liveTracking");   // permission to VIEW Geo Track Live
if (!allowed) return 403 "You do not have permission to access GeoTrack Live";
```

The geo service uses this endpoint to identify a phone **sending its own location**. But the check is for permission to **view everyone's live map**, a manager/HR permission. A field staff member without `attendance.liveTracking` (such as the dev ID `surya`, designation Delivery Boy) gets 403. The geo service then can't identify them, and it's the same 400 as in the evidence.

### Change

Identify any valid, active staff session. Keep the live-map permission only for **viewing other staff**, where it is already enforced by `ensureGeoTrackScopeAccess` on the web read routes.

```ts
// /api/auth/geotrack-access — identity for the geo service.
// The geo service uses this to learn WHO is sending telemetry, not whether
// they may view other staff. Viewing others stays gated by
// ensureGeoTrackScopeAccess on the read routes.
const auth = await authenticateHighFrequencyRequest(ctx, req);
if (auth instanceof Response) return auth;
if (auth.user.status && auth.user.status !== "active") {
  return json({ success: false, error: "Staff is not active" }, 403);
}
return json({ success: true, staffId: String(auth.user._id) }, 200);
```

Optional: also refuse staff with `geoTrackingEnabled === false`. The app already never starts tracking for them.

**Keep the existing 120-second in-memory cache.** It exists because every phone calls this on every request that misses the geo Redis cache.

> **Check production too.** Production uses the same endpoint and gate. Production does show 170+ active sessions in the last 15 minutes, so something is getting through there. That may be admins, older app builds going through the Convex proxy, or a different production config.
>
> To confirm, call the endpoint with a **field-staff** token (no `attendance.liveTracking`). If it returns 403, field staff on current app builds are not being tracked in production either. That would explain the earlier "my mobile is active but not showing" reports.

**Quick way to prove the chain on dev before changing code:** grant `attendance.liveTracking` to the dev ID, clock out and back in, and the staff member should appear in `/api/tracking/live`.

---

## Fix 3 (Convex): web Geo Track Live roster and counts

This is item **B1** of `GEOTRACK_JOINT_CP_BACKEND_FIXES_2026-09-17.md`, and it is **live on dev now**.

The dev page shows *"Live tracking is temporarily unavailable"*, **Online 0 / Offline 0 / Geo enabled 0** and **"No staff found"**, because `development-testing` proxies these routes to geo handlers that don't exist:

| Route | `http.ts` | Restore to |
|---|---|---|
| `GET /api/geotrack/bootstrap` | 18270 | `internal.geotrack.location.liveRoster` + `api.projects.liveTrackable` |
| `GET /api/geotrack/stats` | 18464 | `api.geotrack.trips.stats` |
| `GET /api/geotrack/employee-detail` | 18331 | `api.geotrack.location.employeeDetail` |
| `GET /api/tracking/bootstrap` | 17933 | previous Convex handler |
| `GET /api/geotrack/assigned-places` | 18487 | `api.clientPlaces.listByStaff` |
| `GET /api/geotrack/today-visits` | 18505 | `api.hr.fieldVisits.listTodayByStaff` |

The roster (who exists, names, photos, geo-enabled flag) lives in Convex `staff`. Without it the page has nothing to attach live positions to, so **even after Fixes 1 and 2 the page still reads 0** until this is restored.

`assigned-places` and `today-visits` are also why the dev app's CP and SV lists come back empty.

---

## Verify, in order

```bash
# Fix 1+2: introspection with the dev staff member's token → expect 200 {"success":true,"staffId":"…"}
curl -H "Authorization: Bearer <dev staff token>" \
  https://dev-cvx-http-mg.theairix.com/api/auth/geotrack-access
```

1. On the phone: clock out, then clock in.
2. Expect the staff member in the geo data within about 30 seconds:
   ```bash
   curl https://dev-api-geo.theairix.com/api/tracking/live
   ```
3. After Fix 3: `https://dev-mg.theairix.com` → Geo Tracking → Live shows the staff member Online, with the banner gone.

Mobile can confirm step 2 from the phone log: `GET /api/tracking/sessions/current` should return **200**, followed by `POST /api/tracking/sessions/start` and `/api/tracking/location/batch`.

---

## Checklist

- [ ] **Geo:** pass `AUTH_INTROSPECTION_URL`, `ALERT_WEBHOOK_URL` and the JWT variables through `compose.prod.yaml`.
- [ ] **Geo (dev):** set them to `dev-cvx-http-mg`; `CORS_ALLOWED_ORIGINS=https://dev-mg.theairix.com`; restart; clear `geotrack:auth:*`.
- [ ] **Convex:** `/api/auth/geotrack-access` identifies any active staff session; the live-map permission stays on reads only.
- [ ] **Convex:** test production with a field-staff token; if 403, ship the same fix to production.
- [ ] **Convex:** restore the 6 routes in Fix 3.
- [ ] Verify steps 1–3 above.
