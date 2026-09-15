# Geo Track Live still broken after merging `kira` — what is actually wrong

**Date:** 2026-09-15
**Short answer:** two separate problems. One is a deploy, the other is a design mistake in
the proxy. **No new endpoint is needed from anywhere.**

---

## 1. The merge is not deployed

Merging `kira` into `main` in Git changes nothing until the service is rebuilt and
redeployed. It has not been. Probed live just now, unauthenticated:

```
GET https://api-geo.theairix.com/api/geotrack/session-route?staffId=x&dayStart=1&dayEnd=2
  → 404   Content-Type: text/plain   "404 page not found"
```

`session-route` was added in `53b954f` on `kira`, and that commit also added a JSON 404
handler. If the new binary were running, this path would answer **400** (bad `dayStart`), and
any unknown path would answer **JSON**, not `text/plain`.

It answers Go's default plain-text 404. **The running binary predates the commit.**

> Action: rebuild and redeploy `api-geo.theairix.com` from the merged branch.
> Confirm with the probe above — a 400 means the new build is live.

Note also: `make check` has still never been run against `53b954f`. There is no Go toolchain
on the machine it was written on. **Run it before deploying**, not after.

---

## 2. `bootstrap`, `stats` and `employee-detail` must NOT be proxied to the geo service

This is the part that will still fail after the deploy, and it is why the tiles read
**Online now 0 / Offline 0 / Geo enabled 0** while `Client places 34` and `Planned visit 17`
are fine — those two come from handlers that were left alone.

The failing request in the network tab is `bootstrap?date=…`. Here is what that endpoint
actually does on Convex (`convex/http.ts:17733`):

```ts
const [roster, projects] = await Promise.all([
  ctx.runQuery(internal.geotrack.location.liveRoster, { viewerStaffId, date }),
  ctx.runQuery(api.projects.liveTrackable, {}),
]);
return { success: true, data: { roster, projects } };
```

* `liveRoster` reads the Convex **`staff`** table — who exists, who has GeoTrack enabled.
* `liveTrackable` reads Convex **`projects`**.

**The geo service has neither table.** It stores points, sessions, trips and tamper events
keyed by an opaque staff id. It does not know your staff list and it does not know your
projects. Proxying this endpoint to it can never work — not after a deploy, not ever.

The same is true of the other two:

| Endpoint | Convex handler | Reads |
|---|---|---|
| `/api/geotrack/bootstrap` | `location.liveRoster` + `projects.liveTrackable` | Convex `staff`, `projects` |
| `/api/geotrack/stats` | `api.geotrack.trips.stats` | Convex `geoTrips` |
| `/api/geotrack/employee-detail` | `api.geotrack.location.employeeDetail` | Convex staff + location |

### Fix

**Remove these three from the proxy list and let their original Convex handlers run.**
That is the whole fix. No endpoint has to be built anywhere.

If you want them served from Postgres eventually, that is a data migration — the staff and
projects tables would have to move — and it is a separate project, not a routing change.

### Also remove these two

Already flagged on 12 Sep and still in the proxy list:

| Endpoint | Reads |
|---|---|
| `/api/geotrack/assigned-places` | Convex `clientPlaces` (`http.ts:17969`) |
| `/api/geotrack/today-visits` | Convex `fieldVisits` (`http.ts:17987`) |

The geo service implements neither. If that proxy is unconditional, deploying it takes out
the **mobile** CP/SV lists as well as the web page.

---

## 3. Why "mobile is active but not showing"

The phone genuinely is reporting. Verified live, unauthenticated:

```
GET /api/geotrack/live-status  → 200, real staff rows with session data
GET /api/tracking/live         → 200, same
GET /api/tracking/trips        → 200, 4.6 kB of trip records
```

The data is in the geo service. The page cannot draw it because the **roster** it needs to
map those staff ids to names, photos and geo-enabled flags comes from `bootstrap` — which is
failing. No roster, no rows, so the panel shows "No staff found" and the counters sit at 0.

Fix §2 and the positions appear, because the positions were never the missing part.

---

## 4. What the proxy should do even when it is right

Whatever stays proxied needs a fallback. A proxy that hard-fails when the upstream 404s is
worse than no proxy — it takes down a page that used to work.

In `convex/lib/geoTrackingService.ts`, a non-JSON body currently becomes
`{"success":false,"error":"Geo tracking service returned invalid JSON"}` and is surfaced
straight to the user. Catch the upstream 404 (and any non-JSON body) and fall through to the
Convex handler instead.

---

## Checklist

- [ ] Run `make check` on the merged geo branch.
- [ ] Rebuild and redeploy `api-geo.theairix.com`; confirm `session-route` answers **400**, not a plain-text 404.
- [ ] Remove `bootstrap`, `stats`, `employee-detail` from the proxy — restore the Convex handlers.
- [ ] Remove `assigned-places` and `today-visits` from the proxy.
- [ ] Add a Convex-fallback path for anything that stays proxied.
- [ ] Reload Geo Track Live: Online/Offline/Geo-enabled should populate from the existing `live-status` data.

---

## Still outstanding, unrelated to this page

- **The tracking API is publicly readable.** Every probe in this document was made with no
  token and no API key. `GEO_AUTH_DISABLED` is set on the deployment. Live staff positions
  and trip history are readable by anyone with the URL.
- **`GOOGLE_MAPS_SERVER_KEY` is unset**, so `DrivingRoute` fails on every call and no trip
  ever draws a route line.
