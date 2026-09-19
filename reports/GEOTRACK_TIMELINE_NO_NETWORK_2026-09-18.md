# GeoTrack timeline shows "Network Offline" almost everywhere

**For:** geo-service admin and web admin
**From:** mobile
**Date:** 2026-09-18

---

## What is wrong

Most rows in the staff Activity Timeline read **"Network Offline — Mobile network disconnected"**, including for staff who had a working network the whole time.

Three things add up to this. One was in the app (fixed); two are on the backend/web.

### 1. The web gives a missed heartbeat the "Network Offline" label (web)

`app/geotrack/live/_helpers.ts`:

```ts
if (u.includes("NETWORK_OFFLINE") || u === "NETWORK_OFF" || u.includes("HEARTBEAT_MISSED") || u.includes("SILENT")) {
  return { title: "Network Offline", desc: "Mobile network disconnected" }
}
```

`HEARTBEAT_MISSED` only means "the server heard nothing for a while". The usual cause is Android pausing the app in the background, not the network. Because it shares a branch with `NETWORK_OFFLINE`, every gap is shown as a network loss.

**Change requested:** give `HEARTBEAT_MISSED` / `SILENT` a label of its own, and use the reason when the event has one:

```ts
if (u.includes("HEARTBEAT_MISSED") || u.includes("SILENT")) {
  return {
    title: "No updates from phone",
    desc: metadata?.reason ?? "The phone stopped reporting for a while",
  }
}
if (u.includes("NETWORK_OFFLINE") || u === "NETWORK_OFF") {
  return { title: "Network Offline", desc: "Mobile network disconnected" }
}
```

(The old Convex monitor already writes `metadata.reason` / `reasonCode`, e.g. "Flight mode was turned on" or "the phone likely suspended the app in the background". The geo service does not yet; see 3.)

### 2. The geo service raises HEARTBEAT_MISSED after only 5 minutes (geo service)

`internal/jobs/runner.go`:

```go
r.store.CheckMissedHeartbeats(ctx, started, 5*time.Minute, 30*time.Minute)
```

The Convex monitor this replaced had raised the threshold to **15 minutes** on purpose, because one normal Android doze cycle on OPPO / Realme / Xiaomi / Vivo is longer than 5 minutes.

When the job fires it also writes, in `postgres_jobs.go`:

```sql
UPDATE tracking.live_status SET is_online=false, has_tamper_alert=true,
    network_available=false, current_status='OFFLINE' ...
```

This records **`network_available=false` for a network the service knows nothing about**. `postgres_web_repository.go` does the same for a reported `HEARTBEAT_MISSED` (`network_available = CASE WHEN $2 IN ('NETWORK_OFFLINE','HEARTBEAT_MISSED') THEN false ...`).

**Changes requested:**
- threshold `5*time.Minute` → `15*time.Minute`;
- in both places, leave `network_available` unchanged for `HEARTBEAT_MISSED`, and set it to false only for `NETWORK_OFFLINE`;
- keep `is_online=false` / `current_status='OFFLINE'`, which is correct for a silent phone.

### 3. Attribute the gap (geo service, optional but recommended)

Add `reasonCode` / `reason` to the `HEARTBEAT_MISSED` metadata, using the same order as the old Convex `attributeHeartbeatGap`:

1. device events inside the gap: `DEVICE_SHUTDOWN`, `DEVICE_REBOOT`, `AIRPLANE_MODE_ON`, `LOCATION_DISABLED` / `GPS_DISABLED`, `PERMISSION_*`, `APP_FORCE_KILLED`, `NETWORK_OFFLINE`;
2. otherwise the last heartbeat's `airplaneMode`, `locationEnabled`, `networkAvailable`;
3. then battery ≤ 5%;
4. otherwise "UNEXPLAINED — the phone likely suspended the app in the background".

With this the timeline can say *why* a phone went quiet instead of guessing.

---

## What the app sent wrong, and the fix (shipped in the app)

1. **Heartbeats stopped while the phone slept.** The 75 s heartbeat waited with a coroutine `delay`, whose clock stops while the CPU is suspended. With no session-long wakelock (removed for battery), a still phone with the screen off stopped sending for many minutes, so the geo service raised `HEARTBEAT_MISSED` after 5 minutes.
   **Fix:** the wait is now measured on the wall clock. It is woken by an allow-while-idle alarm and by any location fix that arrives while a heartbeat is overdue. There is still no long wakelock.
2. **"No network" was reported for phones that had a network.** The app read `ConnectivityManager.activeNetwork`, which Android sets to null while the app is *blocked* from the network (Doze, app standby, Data Saver). It also raised `NETWORK_OFFLINE` when any single network dropped, e.g. Wi-Fi turning off while mobile data was up.
   **Fix:** the app tracks every internet-capable network. It reports offline only when none remain, and only after the outage lasts 60 s; `NETWORK_ONLINE` is sent only after a reported outage. Heartbeat `networkAvailable` now means "the phone has internet".

iOS never sent `NETWORK_OFFLINE` or a network flag, so it was not a source.

Note: in deep Doze Android spaces allow-while-idle alarms roughly 9+ minutes apart. With the 15-minute threshold (item 2) that no longer produces a false gap; with 5 minutes it still can.

---

## Verify after deploy

```bash
# Share of today's timeline events that are HEARTBEAT_MISSED vs real NETWORK_OFFLINE
curl -s "https://api-geo.theairix.com/api/tracking/tamper-events?staffId=<id>&limit=200" |
  python -c "import sys,json,collections;d=json.load(sys.stdin).get('data',[]);print(collections.Counter(e.get('eventType') for e in d))"
```

Once phones have the new app build, `NETWORK_OFFLINE` should appear only for real outages longer than a minute. With the backend changes in, `HEARTBEAT_MISSED` should show as "No updates from phone" rather than "Network Offline".

---

## Status update — geo service fixes made (branch `kira`, uncommitted)

Production scale at the time: 9,295 `HEARTBEAT_MISSED` (318 staff) vs 80 `NETWORK_OFFLINE` (24 staff) in 24 h; 322 open `heartbeat_missed` alerts; 0 events carried a reason.

Done in `geo-tracking-service`:

| # | Change | Where |
|---|---|---|
| 2 | Missed-heartbeat threshold is now 15 min (was 5), set in `Config.HeartbeatMissedAfter`; covered by a test | `internal/jobs/runner.go`, `runner_test.go` |
| 3 | The job no longer writes `network_available=false`; a reported `HEARTBEAT_MISSED` doesn't either (only `NETWORK_OFFLINE` does). `is_online=false` and `current_status='OFFLINE'` are kept | `postgres_jobs.go`, `postgres_web_repository.go` |
| 4 | Each `HEARTBEAT_MISSED` gets `reasonCode`, `reason`, `lastAirplaneMode`, `lastLocationEnabled`, `lastNetworkAvailable`. Order: device event in the gap → last reported state → battery 1–5 % → `UNEXPLAINED` | `postgres_jobs.go` |
| 5 | **`has_tamper_alert` belongs to the open tamper events.** A location batch now counts as contact: it resolves open `HEARTBEAT_MISSED` / `NETWORK_OFFLINE` events and the heartbeat alert, returns an `offline_buffering` session to `active`, and sets the flag from open events instead of forcing `false`. Heartbeats already worked this way | `postgres_repository.go` |

Reason codes the web can use: `DEVICE_OFF`, `DEVICE_REBOOT`, `AIRPLANE_MODE`, `LOCATION_OFF`, `PERMISSION_LOST`, `APP_KILLED`, `NO_NETWORK`, `AIRPLANE_MODE_LAST`, `LOCATION_OFF_LAST`, `NO_NETWORK_LAST`, `BATTERY_DEAD`, `UNEXPLAINED`.

Tests: `go test ./internal/jobs ./internal/tracking` pass. The new SQL has **not** been run against Postgres: the integration tests need `TEST_DATABASE_URL` and there is no database here. Run them on dev before deploying.

Existing unrelated item: `internal/httpapi` test `TestAuthDisabledRejectsInvalidBearerInsteadOfFallingBackToBodyStaffID` already failed before this change (auth fallback, commit fb347d1). The stub method the test file was missing (`ResolveStaffIDBySession`) was added so the package compiles.

**Still needed from the web team (not done by mobile):** item 1, the `_helpers.ts` label, including reading `metadata.reason`.
