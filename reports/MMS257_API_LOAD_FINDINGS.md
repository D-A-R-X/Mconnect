# MMS-257: API 429/503 Investigation

## Confirmed Mobile Causes Of Extra Load

- Main prewarms the hidden Attendance tab. Its `onResume` started a 20-second
  status poll even while hidden; `onHiddenChanged` did not stop it. Actual phone
  request metadata showed attendance reads at 17:29:14, 17:29:34 and 17:29:54 IST
  while Home remained visible. This also refreshed monthly attendance and ran
  the hidden clock-in UI geofence watcher.
- Home called its data refresh, unread-count refresh, profile lookup and fleet
  dispatch loading from both `onViewCreated` and the immediately following
  `onResume`. The first two attendance reads sometimes overlapped successfully,
  but the other reads were duplicated. Fleet loading cancelled and restarted.
- `AttendanceReads` coalesced an ordinary overlapping request, but after its
  owner was cancelled every waiting caller ran its own replacement request.
  A deterministic test reproduced 21 total calls for one cancelled owner and
  20 waiters, instead of one owner plus one replacement.

## Fix And Safety Boundary

- `onResume` now owns initial and return-to-Home loading. Initial header binding
  still paints cached session details, with profile refresh on resume.
- A hidden prewarmed Attendance tab does not start its reads or UI watchers.
  Showing it immediately refreshes attendance and resumes the existing visible
  status/geofence watchers. Hiding/pausing stops those UI watchers. The visible
  polling interval and actual GeoTrack service remain unchanged.
- Surviving attendance readers elect one replacement after owner cancellation.
  A cancelled waiter does not cancel other readers.
- No response TTL/cache was added. A later read after an action is fresh.
  Accounts, dates and endpoints remain separate keys. HTTP 429/503 propagate
  normally without automatic retries, fake successful responses or polling.
- Explicit pull-to-refresh, punch/visit completion refreshes, GPS collection,
  tracking lifecycle, queues, heartbeat, authentication and write calls are
  unchanged. Tests exercise the real tracking gate's unknown-on-overload rule:
  an outage must not stop an established route or start an unverified one.

## Server-Side Evidence And Remaining Gap

The 19 November phone test captured 429/503 responses across several MMS read
endpoints and slow successful requests. PostHog intentionally does not retain
response bodies, authentication headers or query values, so these records
cannot identify a particular upstream capacity limit or failed database query.

Read-only Git inspection found the earlier batched mobile-verification fix in
the production branch. Git membership does not prove the runtime deployed it.
No server settings, deployment, rate limits, quotas or backend code were changed.

The operator reports no access to server logs. The existing local Convex CLI
configuration selects a self-hosted-mode admin origin; its relationship to
the app's production host has not been independently verified. Do not deploy,
change that configuration or use credentials against another host based on
this investigation. No production diagnostic probes were run.

The server operator still needs to correlate the original PostHog timestamps
with gateway/upstream logs and resource/concurrency metrics, then identify
the exact failing function or capacity condition. This mobile fix reduces
confirmed avoidable load; it is not proof that every production 429/503 is fixed.

## Validation

- The cancellation fan-out regression failed before the fix (21 calls, expected
  2) and passes afterward. Full debug assembly and all 563 JVM tests pass,
  including session-invalidation, attendance/tracking and overload tests.
- On the connected OPPO/Android 16, the opt-in visibility test passed: a real
  prewarmed hidden HR fragment had no active UI watcher jobs; showing Attendance
  started them; returning to Home stopped them. The test invokes the production
  prewarm runnable explicitly rather than relying on its delayed timer.
- The in-place debug update preserves the app's session/data. No real attendance,
  visit or trip was submitted. An actual active GeoTrack route and long-term
  battery use were not exercised, so those are not claimed as verified.
