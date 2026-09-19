# Prod GeoTrack drops live location points as "future_timestamp"

**For:** geo-service / server admin
**From:** mobile
**Date:** 2026-09-18 (measured 19:18–19:30 IST)

## Symptom

Staff show Online, but the map pin sits on a placeholder or an old position. For example DHIVAGAR.B (`q574jgr3x05d9vmz3c8ky62b3s85v0pt`) was shown at **(13, 80)**, Irungattukottai, while the phone was in Ashok Nagar.

## Evidence

The phone captured correct fixes and uploaded them. Prod accepted the request and discarded every point:

```
GPS #3: 13.0439295,80.2121439 acc=100 at 19:24:23 IST
POST /api/tracking/location/batch  recordedAt=1789739663593  (= 13:54:23.593Z)
<- 200 {"filteredCount":1,"filteredReasons":{"future_timestamp":1},"insertedCount":0}
```

- Phone clock: exact (`adb shell date +%s` equals real time; automatic time on).
- The upload reached the server about 64 s after capture.
- `PushLocationBatch` filters `point.RecordedAt.After(now.Add(time.Minute))`. A 64-second-old point can only fail this if the **API process's clock is more than ~2 minutes behind** real time, or if prod runs a build other than `main`, which parses `recordedAt` with `time.UnixMilli` exactly as the app sends it.

It is org-wide: of 7 staff online at 19:30 IST, 5 had **0** stored points today, and the other two had their last stored points at 11:57Z and 12:24Z. 83 of 393 live rows sit on placeholders: (0,0) ×78 and (13,80) ×5.

## Fix (server)

1. On the prod geo host: `date -u` inside the API container vs real UTC; enable NTP (`timedatectl set-ntp true` / chrony) and restart the container.
2. Confirm which image/ref prod runs (`deploy.yml` is manual).
3. Hardening, recommended: don't drop small future skews. Clamp `recordedAt` to server time when it is within a few minutes ahead, and log the skew instead of discarding the point.

## Verify

```bash
curl -s "https://api-geo.theairix.com/api/geotrack/timeline?staffId=<id>&dayStart=<ms>&dayEnd=<ms>"
```
Points should appear within about a minute of capture, and the live pin should follow them.

## Also fixed in the app (separate issue)

When a still phone went into low-power mode, it requested fixes only after 40 m of movement, so it got no fixes at all. The filter has been removed; storage is still limited to one point per 5 min within 50 m.

---

## Re-check 2026-09-19 11:26 IST — still happening

DHIVAGAR.B (`q574jgr3x05d9vmz3c8ky62b3s85v0pt`), phone in Ashok Nagar:

```
11:26:14.262  GPS #2: 13.0439191,80.2120919 acc=100 (coarse fallback, stored locally)
11:26:23.180  POST /api/tracking/location/batch  recordedAt=1789797374249 (= 11:26:14.249 IST)
11:26:23.392  <- 200 {"filteredCount":1,"filteredReasons":{"future_timestamp":1},"insertedCount":0}
```

- Phone clock = PC clock = Google `Date` header, to the second. The point was **9 s old** at upload.
- So the prod geo API's clock is **more than ~1 minute behind** real time, or prod is not running `main`.
- Live row: `lat 13, lng 80` (placeholder), `online true`; 0 points stored today. The web draws him at Irungattukottai.
- Org-wide at 11:30 IST: **14 of 22 online staff have 0 points today**; 7 are pinned at (13, 80), 1 at (0, 0).

**Action (server admin):** `docker exec <geo-api> date -u` vs real UTC; enable NTP / chrony on the host, restart. Confirm the deployed image/ref.
