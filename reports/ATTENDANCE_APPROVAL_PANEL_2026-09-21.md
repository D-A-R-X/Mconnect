# Geo panel shows the REVIEWER's own route under every staff member

**For:** geo-service (the fix) + web/Convex (the scope field it needs)
**From:** mobile
**Date:** 2026-09-21
**Seen on:** HR → Attendance Approvals → HARIKRISHNAN. M, 20 Sept 2026

---

## The bug

Every per-staff geo read in the admin web returns **the logged-in reviewer's
own** timeline and trips, no matter which staff member is open.

That is why the same staff member, on the same date, looked completely
different to two reviewers:

| Opened by | Distance | Trips | GPS points | Map |
|---|---|---|---|---|
| ANANTHA KUMAR (VP) | 1.26 km | 1 (07:58–08:05 pm) | 15 | route in Vadapalani |
| Manoj Prabhakar | 0 m | 0 | 0 | only the clock-in pin |

Neither is Harikrishnan's. The first is **Anand sir's own** movement on 20 Sept;
the second is empty because that reviewer is not tracked.

**No data leaks between staff** — a caller can only ever see themselves. The
damage is the opposite: the map is *labelled* as someone else, so an RO or HR
approves or rejects attendance against evidence belonging to the wrong person.

## Root cause

`internal/httpapi/server.go:860`:

```go
func requestStaffIdentifier(r *http.Request, bodyValue string) string {
	if staffID := authenticatedStaffID(r); staffID != "" {
		return staffID          // the caller's own id always wins
	}
	return firstNonEmpty(strings.TrimSpace(bodyValue), strings.TrimSpace(r.Header.Get("X-Airix-Staff-Id")))
}
```

The authenticated caller's staff id **overrides the requested `?staffId=`**, and
`resolveStaffID` (`:877`) feeds that into the query. For the write endpoints
this is exactly right — a phone must not be able to post points as someone
else. But the same helper serves the admin **read** endpoints, so the web's
`?staffId=<harikrishnan>` is discarded and replaced by the reviewer's id.

Introduced 2026-09-07 in `36f58a5` "fix: cache geotrack token introspection",
which added the override to what was then `developmentStaffID`. Everything since
that deploy is affected.

The carve-out was designed but never wired: `isWebReadPath`
(`internal/httpapi/security.go:219`) lists exactly these admin GET paths and is
**dead code — it has no callers anywhere in the repo**.

### Affected (single-staff reads, all pinned to the caller)

| Endpoint | Where |
|---|---|
| `GET /api/geotrack/timeline` | `server.go:714` |
| `GET /api/geotrack/session-route` | `server.go:743` |
| `GET /api/tracking/sessions/current` | `server.go:355` |
| `GET /api/tracking/trips` | `tracking_web.go:22` |
| `GET /api/tracking/alerts` | `tracking_web.go:49` |
| `GET /api/tracking/tamper-events` | `tracking_web.go:105` |

### Not affected

The roster/list reads take `staffIds` and go through `resolveStaffIDList`
(`server.go:668`, `:844`), which does not pin to the caller. So the Geo Track
Live map and the live-status list are correct — only the per-staff drill-down
is wrong.

## What the fix needs

Honouring `?staffId=` on its own would let any staff member read anyone's
location. The geo service cannot tell who is allowed: the introspection
response it consumes carries only `{success, staffId}`
(`internal/httpapi/security.go:124-127`) — no role, no scope.

**Option A (smaller, keeps the browser call).** Convex
`/api/auth/geotrack-access` also returns a viewer scope, e.g.
`"scope": "self" | "all"` (or `canViewAll: true`). The geo service caches it
next to the staff id in the Redis allow entry, and on the paths
`isWebReadPath` already lists, lets the query `staffId` win when the scope is
`all`, otherwise keeps pinning to the caller. Writes keep the current
behaviour unconditionally.
*Needs from web/Convex: the extra field on that endpoint. Nothing else.*

**Option B.** The web stops calling the geo API from the browser and proxies
these reads through its own Next.js route using the geo `X-API-Key`, which
bypasses the bearer pinning, doing the permission check where the role is
already known.
*Needs from web: new server routes; no geo-service change.*

Option A is the smaller change and keeps the current client code.

---

## Separate, still valid: the 09:24 / 09:24 punch pair

Independent of the geo bug, and correct behaviour:

He clocked in at 09:24 am and never clocked out. The midnight finalizer closes
such a session at its own punch-in instant (`convex/staffAttendance.ts:8024`,
`totalMinutes: 0`, `autoClosedAtDayEnd: true`) so a 9 am punch-in cannot become
a 15-hour "present" day. Zero worked time → **Absent**. The record is right.

The display is not. The punch-log dialog strips the synthetic row and says
"Did not punch out"
(`features/attendance/attendance-page-content.tsx:2743`), but the ATTENDANCE
SUMMARY tile renders `punchOutTime` raw (`:2331`), showing a punch-out that
never happened.

**Change requested (web):** in the Punch Out tile, show "Not punched out" when
the session is a synthetic close. The marker is already on the session
(`autoClosedAtDayEnd === true`, plus the legacy `${date}T18:29:59.000Z` match;
both covered by `sessionIsSyntheticDayEndClose`, `convex/staffAttendance.ts:708`).

## Also worth doing while in there (web)

`staffDayGeo.error` is never rendered in the approvals panel — the only
"nothing here" message is the literal "No trips recorded for this date"
(`features/attendance/attendance-page-content.tsx:2490`). On a failed request
the hook empties the arrays and sets `error`
(`app/geotrack/live/_use-postgres-geo.ts:267-276`), so a geo outage is
pixel-identical to "this person did not move". Render the error: tiles "—",
timeline "Tracking data could not be loaded".

---

## Corrections to the 2026-09-21 first pass

Two conclusions in the earlier version of this file were wrong, both caused by
trusting the panel's numbers:

1. The 0 m / 0 trips / 0 points view was **not** a failed fetch. It was the
   second reviewer's own (empty) tracking data.
2. The 07:58–08:05 pm trip was **not** Harikrishnan's, so it is no evidence
   that the app kept tracking him after a missed clock-out. Whether he was
   tracked at all on 20 Sept cannot be read off this screen until the geo fix
   lands.

(The underlying design fact still stands on its own: with no mobile clock-out,
`AttendanceTrackingGate` keeps the work session active and
`GeoTrackService.scheduleAttendanceDayBoundaryStop()` only stops tracking at IST
midnight. Worth deciding separately, but this screenshot does not demonstrate
it.)
