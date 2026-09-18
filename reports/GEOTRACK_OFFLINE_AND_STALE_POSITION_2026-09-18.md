# GeoTrack in production: "Offline" with the app open, and stale map positions

**For:** geo-service admin and web/Convex admin
**From:** mobile
**Date:** 2026-09-18
**Measured on production** (`api-geo.theairix.com`), unauthenticated reads only.

---

## What production looks like right now

Of 393 live rows, **41 were seen within the last 15 minutes, and 13 of those read Offline.**

| Group | Rows | Live row's session | Their current session |
|---|---|---|---|
| **A** | 3 | e.g. `706a404c` | a **different, active** session (`0177c589`) |
| **B** | 6 | same id | `offline_buffering` |
| **C** | 4 | same id | **no active session** |

The service computes Online as:

```sql
is_online AND last_seen >= now() - interval '5 minutes'
```

and every point/heartbeat writes `is_online` as:

```sql
CASE WHEN EXISTS (session with this public_id)
     THEN EXISTS (that session AND state <> 'ended')
     ELSE true END
```

So a phone posting against a session the server has **ended** refreshes `last_seen` while forcing the row Offline. That is group A exactly, and it matches the reports: "app is open, last seen 2 minutes ago, still Offline, not tracking".

## Root cause (mobile) and the fix shipped

Every location point is stamped with the session id the phone holds **at capture time**, and the phone only re-checked that id at app start, on punch, and from a 15-minute worker. Once the server ended or replaced the session, the phone kept stamping the dead id indefinitely.

**Fixed in the app** (`GeoTrackService`): every 10 minutes the tracking service now
1. re-checks attendance — a clock-out from web, biometric or another device ends tracking here too;
2. reads `GET /api/tracking/sessions/current` and **adopts** the server's active session id, or opens a new session when the staff member is clocked in and the server has none.

Group C (still posting with no active session) is the same bug seen from the other side, and is also what "tracking continues after clock-out" looks like.

---

## What is still needed on the backend

### 1. Do not let a late batch decide "online" (geo service)

A backlog uploaded after a session ended sets `is_online = false` for a staff member who is otherwise fine. A batch is *history*; it should not downgrade a live row.

**Recommended** in `postgres_repository.go`, in the point-batch and heartbeat upserts:

```sql
-- only a fresh write may claim the row is online
is_online = CASE WHEN EXCLUDED.last_seen >= now() - interval '5 minutes'
                 THEN EXCLUDED.is_online ELSE tracking.live_status.is_online END
```

and, better, make the session check `state NOT IN ('ended')` **only for the session the batch belongs to**, falling back to "true" when that session is closed but the point is recent — the phone is clearly alive.

### 2. Heartbeat-missed flips people Offline and nothing flips them back

Group B sits in `offline_buffering` with fresh `last_seen`. The maintenance job sets `is_online=false`, `current_status='OFFLINE'`, and the session to `offline_buffering`. The heartbeat path restores `state='active'`, but a **points-only** upload does not, so a phone that uploads points without a heartbeat (Doze, restricted background) stays Offline while clearly reporting.

**Recommended:** in the point-batch path, also do
```sql
UPDATE tracking.sessions SET state = CASE WHEN state='offline_buffering' THEN 'active' ELSE state END
```
exactly as the heartbeat path does.

Also review the job's threshold against the app's heartbeat interval (75 s, longer under Doze). Today nearly every staff member carries an open `HEARTBEAT_MISSED` alert, which makes the signal useless.

### 3. The web shows a stale position as if it were current (web)

Reported as "he is shown far away but he is in my office". The live map draws `lat/lng` from the last point received. When a phone's tracking stalls (Doze, killed service, dead session) the map keeps the **last known point** — often hours old and kilometres away — with no visual difference from a live one.

Right now 10 of the 64 staff seen in the last hour are more than 25 km from the Chennai cluster, and every one of them has missed heartbeats.

**Recommended on the Geo Track Live page:**
- grey the marker and show "Last seen HH:MM (N min ago)" when `lastSeen` is older than ~5 minutes;
- keep Online strictly for fresh rows;
- optionally hide markers older than ~2 hours behind a "show stale" toggle.

This is presentation only; the data is already there.

---

## Verify after deploy

```bash
# Nobody should be "seen in the last 15 min" AND Offline for long.
curl -s https://api-geo.theairix.com/api/tracking/live |
  python -c "import sys,json,time;from datetime import datetime;d=json.load(sys.stdin)['data'];now=time.time()*1000;f=lambda v:datetime.fromisoformat(str(v).replace('Z','+00:00')).timestamp()*1000;print(sum(1 for r in d if f(r['lastSeen'])>now-9e5 and not r['isOnline']),'fresh-but-offline'))"
```

For one staff member, the live row's `sessionId` should equal their `sessions/current` id:

```bash
curl -s "https://api-geo.theairix.com/api/tracking/sessions/current?staffId=<id>"
```

Mobile note: phones only pick up the fix after they update; the app then repairs its own session within 10 minutes.
