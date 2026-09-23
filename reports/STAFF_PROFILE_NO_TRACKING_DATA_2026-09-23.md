# Staff profile always says "No tracking data"

**For:** web team (`manjusitedevelopment`)
**From:** mobile
**Date:** 2026-09-23
**Where the bug is:** web, one call site. Nothing wrong with the app, the geo
service, or the staff member's phone.

---

## Symptom

Open any field staff member's profile — HR → Staff → *(staff)*. The **Live
Location** card reads:

> Live Location
> **No tracking data**
> *No location data*

…while the same staff member, at the same moment, appears on **Geo Track Live**
with a battery percentage and a recent ping.

Observed on ARUN.G (employee `21601`, Business Development Executive): profile
said "No tracking data"; the live roster said **61%, 10m ago**. His profile
also shows *Geo Tracking: Enabled (locked for Field Staff)*, so the reading is
doubly misleading.

## Cause

The two screens read **different backends**, and only one of them is still fed.

| Screen | Source | Still written to? |
|---|---|---|
| Staff profile Live Location | `api.geotrack.location.liveStatus` → Convex table `geoLiveStatus` | **No** |
| Geo Track Live | `_use-postgres-geo` → Postgres geo service, `api-geo.theairix.com` | Yes |

`features/hr/staff-detail/page.tsx:234`:

```ts
const liveStatuses = useQuery(
  api.geotrack.location.liveStatus,
  canViewStaff ? {} : "skip",
) ?? []
const liveLoc = (liveStatuses as any[]).find((s: any) => s.staffId === id)
```

Since GeoTrack moved to the Postgres service, the mobile app posts **only**
there:

- `GeoTrackApi.DIRECT_BASE_URL = "https://api-geo.theairix.com"` (heartbeat,
  `/api/tracking/location/batch`, tamper, session control);
- there is no Convex geotrack ingest left in the app — searching the whole
  network layer for `geotrack/heartbeat|location|ingest` returns nothing;
- `convex/http.ts` contains no `geoLiveStatus` reference at all.

The only remaining writers of `geoLiveStatus` are seed/debug/tamper helpers
(`convex/geotrack/seedFromExport.ts`, `debugHelper.ts`, `tamper.ts`), none of
which run in normal operation.

So the table is empty for current staff, `find(...)` returns `undefined`, and
the card falls through to its last branch — **for every staff member, always**,
however healthy their phone is.

### The label makes it worse

`features/hr/staff-detail/page.tsx:1674`:

```ts
{liveLoc?.isOnline ? "Online now" : liveLoc ? "Offline" : "No tracking data"}
```

"No tracking data" is the **row-not-found** branch, not an "untracked" branch.
It reads as a device or permission fault, so a working phone is reported as
broken. That is worth fixing even after the source is corrected, because
"not found" and "tracked but idle" are genuinely different states.

## Fix

Point the card at the source that is actually fed — the same one Geo Track Live
uses — and separate the two failure states.

```ts
// features/hr/staff-detail/page.tsx
import { useStaffDayGeo } from "@/app/geotrack/live/_use-postgres-geo"

// ...
const dayStart = new Date(new Date().setHours(0, 0, 0, 0)).getTime()
const geo = useStaffDayGeo({
  enabled: canViewStaff && !!id,
  token,
  staffId: id,
  dayStart,
  dayEnd: dayStart + 86_400_000 - 1,
  live: true,
})
const liveLoc = geo.liveStatus
```

`useStaffDayGeo` already returns `liveStatus` for exactly one staff member
(it filters `live-status` by `staffId`), so this is close to a drop-in
replacement — `liveLoc.isOnline`, `.lat`, `.lng` keep the same shape and the
mini-map below needs no change.

Then make the three states distinct:

```ts
{geo.loading
  ? "Checking…"
  : geo.error
    ? "Couldn't load tracking"
    : liveLoc?.isOnline
      ? "Online now"
      : liveLoc
        ? `Offline · last seen ${relTime(liveLoc.lastSeen)}`
        : "Never reported"}
```

**One caveat worth knowing before you wire it up.** `useStaffDayGeo` hits the
geo service with the viewer's bearer token, and that service currently pins
every per-staff read to the **caller's** own staff id — so an admin opening
someone else's profile would get their own (empty) row and the card would
still look wrong. The geo-side fix is deployed; it stays inert until Convex
returns a viewer scope on `/api/auth/geotrack-access`. That contract is in
`geo-tracking-service/docs/VIEWER_SCOPE_CONTRACT.md`, and **it needs to ship
first or alongside this change.**

## Scope — how far does this go?

Only this one call site. Searching the web app for `api.geotrack.*`:

| Call site | Query | Affected? |
|---|---|---|
| `features/hr/staff-detail/page.tsx` | `location.liveStatus` | **Yes** — the bug |
| `app/geotrack/live/page.tsx` | `location.fieldIntelligence` | No |
| `components/staff-tracking/staff-day-tracking-map.tsx` | `location.fieldIntelligence` | No |

`fieldIntelligence` reads `clientPlaces` / `clientPlaceVisits` — business data
that Convex still owns and still writes. It is correctly Convex-backed and
should be left alone.

## Verifying

After deploying, open a field staff member who appears on Geo Track Live with a
recent ping. The profile card should show the same state ("Online now", or
"Offline · last seen …") and drop a pin on the mini-map. A staff member who
genuinely has never reported should read **"Never reported"**, not
"No tracking data".
