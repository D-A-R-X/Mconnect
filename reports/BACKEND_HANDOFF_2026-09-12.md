# Backend handoff — 2026-09-12

**For:** backend / Convex / geo-service admin
**From:** mobile (Android `merge`+`main`, iOS `darx`)
**Scope:** investigation and specs only. **No web, Convex or Go source was edited.**

Three independent problems, in the order they should be tackled. Each section stands alone.

| # | Problem | Where | Urgency |
|---|---------|-------|---------|
| 1 | GeoTrack screens show nothing — a missing endpoint plus a diverged wire contract | `geo-tracking-service` (Go) | **Highest — visibly broken now** |
| 2 | Legacy Joint CPs reject their real outcome owner at the arrival OTP | `convex/hr/fieldVisitOtp.ts` | **High — must ship with the pending outcome fix, not after** |
| 3 | SV-cum-CP skips "Fixed", and a follow-up strands the fixed SV | `convex/marketing/clientPlaceVisits.ts` | Medium |

---
---

# 1. GeoTrack — "tracking is not working"

Measured against the geo repo's own rule:

> Preserve the published mobile and web HTTP contracts until compatibility routes are
> explicitly retired. — `AGENTS.md`

The mobile contract was published first (epoch milliseconds, `_id`). The service currently
diverges from it in two ways.

> **Why no Go was written:** `AGENTS.md` requires `make check` before committing, and there
> is no Go toolchain on the machine this was investigated from — nothing could be compiled,
> vetted or tested. Pushing unverified Go into the service carrying live attendance
> tracking is not worth the risk. Everything below is precise enough to apply and verify in
> one sitting where Go is available.

## 1.1 `/api/geotrack/session-route` is not implemented

It is the **only** endpoint in the app's direct-GeoTrack set with no handler. All fourteen
others match:

```
/api/tracking/location/batch      ok      /api/geotrack/day-status      ok
/api/tracking/heartbeat           ok      /api/geotrack/timeline        ok
/api/tracking/tamper-events       ok      /api/geotrack/nearby-staff    ok
/api/tracking/sessions/start      ok      /api/geotrack/route           ok
/api/tracking/sessions/current    ok      /api/geotrack/geocode-address ok
/api/tracking/sessions/end        ok      /api/tracking/places/search   ok
/api/tracking/live                ok      /api/tracking/trips           ok
                                          /api/geotrack/session-route   MISSING
```

Two screens 404 as a result: **GeoTrack Live** (`GeoTrackLiveFragment`) and the
**attendance review route strip** (`AttendanceReviewBottomSheet`). Both are map views, so
the visible symptom is "tracking shows nothing". iOS calls it too
(`GeoTrackAPIService.sessionRoute`).

### Contract to implement

`GET /api/geotrack/session-route`

| Query | Type | Notes |
|---|---|---|
| `staffId` | string, optional | defaults to the authenticated staff |
| `dayStart` | int64 | Unix ms, required |
| `dayEnd` | int64 | Unix ms, required |
| `minStopMinutes` | int, optional | app sends `30` |

Response — `{"success":true,"data":{…}}`:

```jsonc
{
  "session": { /* the day's session, same shape as /api/tracking/sessions/current */ },
  "timeline": [ /* same shape as /api/geotrack/timeline */ ],
  "trips":    [ /* same shape as /api/tracking/trips */ ],
  "stops":    [ /* TripStop, flattened across the day's trips */ ],
  "routeStart": 1789193662184,   // Unix ms
  "routeEnd":   1789222462184,   // Unix ms
  "distanceMeters": 41230
}
```

### It composes from what already exists — no new SQL

| Field | Source |
|---|---|
| `timeline` | `service.Timeline(ctx, staffID, start, end, limit)` |
| `trips` | `service.ListTrips(ctx, TripFilter{StaffID, From, To, Limit})` |
| `session` | `service.CurrentSession(ctx, staffID)` |
| `stops` | `service.GetTrip(ctx, tripID).Stops` per trip, filtered by `minStopMinutes` |
| `distanceMeters` | sum of `trips[].DistanceMeters` |
| `routeStart` / `routeEnd` | min/max of the timeline's `RecordedAt`, falling back to `dayStart`/`dayEnd` |

Mirror `GET /api/geotrack/timeline` (`internal/httpapi/server.go:623`) for the guard
clauses, `int64Query` parsing, `developmentStaffID` resolution and `statusForTrackingError`
mapping. Register it beside the others so it inherits the same auth middleware.

Cap the per-trip `GetTrip` fan-out, or add a bulk stops query — a long day can hold dozens
of trips and this is a foreground request behind a map.

## 1.2 Timestamps and key names do not match the published contract

This breaks endpoints that **are** implemented.

Go marshals `time.Time` as RFC 3339 (`"2026-09-12T06:14:22.184376Z"`). The published mobile
contract is **epoch milliseconds**. The mobile models declare these as integers, so the
decoder throws and **the entire response is discarded** — not one field, the whole payload.
The screen renders empty with no error, indistinguishable from "no data".

| Type | Field | Mobile expects | Service sends |
|---|---|---|---|
| `LocationPoint` (`service.go:334`) | `recordedAt` | int64 ms | RFC 3339 |
| `Trip` (`web.go:17`) | `tripId` | key `_id` | key `tripId` |
| `Trip` (`web.go:20-21`) | `startedAt`, `endedAt` | int64 ms | RFC 3339 |
| `TripStop` (`web.go:52-53`) | `arrivedAt`, `departedAt` | int64 ms | RFC 3339 |
| `Session` (`service.go:70`) | `sessionId` | key `_id` | key `sessionId` |
| `Session` (`service.go:75`) | `state` | key `sessionState` | key `state` |
| `Session` (`service.go:77-78`) | `startedAt`, `endedAt` | int64 ms | RFC 3339 |

### Already mitigated on Android, NOT on iOS

Android now accepts **both** shapes — epoch millis or RFC 3339, either key spelling
(`EpochMillisAdapter` + `SerializedName` alternates, 9 regression tests, shipped in
`4dff5204`). Android will work the moment `session-route` exists, with no change here.

That is a compatibility shim, not a reason to leave the contract split:

* **iOS is NOT mitigated.** Its models still declare `startedAt: Double?` and
  `case id = "_id"`, so iOS stays broken on trips/session data until this service emits the
  published contract or iOS gets the same shim.
* Two shapes for one field is a standing trap for the next consumer.

### Recommended fix

Emit the published contract at the **HTTP seam only**, leaving internal Go types idiomatic —
a marshalling DTO for the mobile-facing handlers, so web consumers built against the current
shapes are untouched:

* `time.Time` → `t.UnixMilli()`, `*time.Time` → `omitempty` int64 pointer
* **add** `_id` alongside `tripId` / `sessionId` (emit both; retire the duplicate only once
  every client is confirmed off it)
* **add** `sessionState` alongside `state`

Adding keys rather than renaming keeps this backward compatible for web.

## 1.3 Verification

```bash
make check                 # required by AGENTS.md; could not be run here
```

Contract-seam tests (the repo's stated preference over SQL-coupled tests):

1. `session-route` with a known staff/day returns timeline, trips, stops, and a
   `distanceMeters` equal to the sum of the trips.
2. Missing/invalid `dayStart`/`dayEnd` → 400, matching the timeline handler's wording.
3. `minStopMinutes` filters short stops out.
4. No session that day → `session: null` with the rest populated, **not** a 404.
5. Every timestamp in mobile-facing payloads is an integer, and `_id` is present wherever
   `tripId` / `sessionId` is.
6. A staff member with no data → empty arrays, `success: true`.

After deploying, confirm on a real device that GeoTrack Live and the attendance route strip
draw a path.

---
---

# 2. Joint CP — legacy arrival-OTP fix (the missing half)

**Companion to:** the pending `JOINT_CP_ROLE_INVERSION_RUNTIME_FIX`.

## 2.1 Why this is needed

That pending fix normalizes roles in `loadJointCpWorkflowContext`, repairing the **outcome**
step for legacy Joint CPs. It does not repair the **arrival OTP** step, which happens FIRST.

Until both are fixed the affected staff member is stopped before ever reaching the outcome
form — they simply see `OTP_OWNER_ONLY` where they used to see `REVIEWER_ONLY`, and will
report the bug as still open.

> **Ship this together with the outcome fix, not after it.** Shipping only the outcome half
> is *worse* than today: the workflow query starts reporting the junior as owner, so both
> apps show them the OTP screen, which the server then refuses.

## 2.2 Scope

Joint CP rows created before `840299a3` (2026-09-04, "feat(iam): add template-level
hierarchy and joint CP workflow roles"). Their participant legs have:

* `workflowRole` — **absent** on both legs, and
* `isPrimary` — on the **senior** participant, the reverse of today's rule.

Rows created after that commit snapshot `workflowRole` at creation
(`convex/marketing/lib/jointCp.ts:137`) and are unaffected.

## 2.3 The defect

`convex/hr/fieldVisitOtp.ts:277-288`, inside `_readVisitForOtp`:

```ts
let jointFieldVisitRole: "outcome_owner" | "reviewer" | null = null;
if (cpVisit?.cpType === "joint_cp") {
  const legs = await loadJointLegs(ctx, cpVisit._id);
  const fieldVisitLeg = legs.find(
    (leg) => String(leg.staffId) === String(visit.staffId),
  );
  if (fieldVisitLeg) {
    jointFieldVisitRole =
      fieldVisitLeg.workflowRole ??
      (fieldVisitLeg.isPrimary ? "outcome_owner" : "reviewer");   // ← inverts legacy rows
  }
}
```

Consumed by two gates, both rejecting the real outcome owner on a legacy row:

| Gate | Line | Message |
|---|---|---|
| request arrival OTP | `:598-607` | `OTP_OWNER_ONLY: Only the Joint CP outcome owner can request arrival OTP.` |
| verify arrival OTP | `:1056-1065` | `OTP_OWNER_ONLY: Only the Joint CP outcome owner can verify arrival OTP.` |

## 2.4 The patch

### Import

```ts
import { resolveJointCpAuthorityByLevel } from "../marketing/lib/jointCpAuthority";
```

### Helper

```ts
/**
 * Role for a leg that predates workflowRole snapshotting.
 *
 * Legacy legs carry no role and put `isPrimary` on the SENIOR participant,
 * which is the reverse of the current rule, so `isPrimary` cannot be trusted
 * as a fallback. Resolve from the same numeric-level authority that creation
 * and the admin repair tool use, so all three agree.
 *
 * Returns null — never throws — when the pair cannot be resolved (a leg whose
 * staff was deleted or deactivated, a participant with no numeric level, or a
 * pair that has since drifted to the same level). This runs on a READ path
 * that gates arrival for a staff member standing at a client's door; a data
 * problem must degrade, not become a hard failure.
 */
async function legacyJointRoleForStaff(
  ctx: QueryCtx | MutationCtx,
  legs: Awaited<ReturnType<typeof loadJointLegs>>,
  staffId: Id<"staff">,
): Promise<"outcome_owner" | "reviewer" | null> {
  const staffIds = legs.map((leg) => leg.staffId);
  if (staffIds.length !== 2) return null;
  try {
    const authority = await resolveJointCpAuthorityByLevel(ctx, staffIds, {
      // Eligibility is a CREATION rule. A participant on a months-old visit may
      // since have moved department or changed designation; failing that check
      // here would block a legitimate arrival on a visit that already exists.
      requireEligibleStaff: false,
    });
    return String(authority.ownerStaffId) === String(staffId)
      ? "outcome_owner"
      : "reviewer";
  } catch {
    return null;
  }
}
```

### Replace the derivation in `_readVisitForOtp`

```ts
let jointFieldVisitRole: "outcome_owner" | "reviewer" | null = null;
if (cpVisit?.cpType === "joint_cp") {
  const legs = await loadJointLegs(ctx, cpVisit._id);
  const fieldVisitLeg = legs.find(
    (leg) => String(leg.staffId) === String(visit.staffId),
  );
  if (fieldVisitLeg) {
    jointFieldVisitRole =
      // A stamped role always wins — never re-label a correctly created visit.
      fieldVisitLeg.workflowRole ??
      (await legacyJointRoleForStaff(ctx, legs, visit.staffId)) ??
      // Last resort for a row that cannot be resolved at all. Unchanged from
      // today's behaviour, so this patch can only ever improve a row.
      (fieldVisitLeg.isPrimary ? "outcome_owner" : "reviewer");
  }
}
```

## 2.5 Must match the other half

Both halves must reach the **same** answer for the same row, or the OTP owner and the
outcome owner disagree and the visit deadlocks: one person can verify arrival, a different
person can file the outcome, and neither can finish alone.

Apply the identical rule in `loadJointCpWorkflowContext`
(`convex/marketing/clientPlaceVisits.ts:612`):

* normalize **only when `workflowRole` is absent**,
* use `resolveJointCpAuthorityByLevel` with `requireEligibleStaff: false`,
* swallow the throw and fall back to `isPrimary`.

Best of all: extract the helper into `convex/marketing/lib/jointCp.ts` next to
`jointCpWorkflowRoleForLeg` and have both files call it, so a future third caller cannot
reintroduce the split.

## 2.6 Rows this cannot fix

`resolveJointCpAuthorityByLevel` throws — and the helper returns null — when:

* a participant's staff row is missing or `status !== "active"`,
* a participant has no numeric designation/IAM level,
* the two participants now sit at the **same** level, or on the same IAM template
  (e.g. one was promoted since the visit was created).

Those rows keep today's inverted behaviour. They are genuinely ambiguous — the system has no
basis to pick an owner — and need the existing admin repair tool with a human deciding. Log
them so the repair tool gets a work list rather than a full-table scan.

## 2.7 Tests — `convex/fieldVisitOtp.test.ts`

That suite currently has **14 tests and no Joint CP legacy coverage at all**, which is why
this gap was invisible. Add a legacy fixture: two participants at different numeric levels,
both legs `workflowRole: undefined`, `isPrimary: true` on the **senior**.

1. The **lower-level** staff calls `requestArrivalOtp` → succeeds, no `OTP_OWNER_ONLY`.
2. The **lower-level** staff calls `verifyArrivalOtp` → succeeds.
3. The **senior** staff calls either → still refused with `OTP_OWNER_ONLY`.
4. A row **with** `workflowRole` stamped is untouched — the stamped role wins even when it
   disagrees with current levels.
5. A row whose participants now share a level → helper returns null, no throw, the read
   still returns a role.
6. End-to-end on one legacy row: the same staff member passes the OTP gate **and** the
   outcome gate — this is the test that proves the two halves agree.

```bash
pnpm vitest run convex/fieldVisitOtp.test.ts convex/jointCpVisits.test.ts
```

Baseline on a clean checkout: `fieldVisitOtp.test.ts` 14 passed, `jointCpVisits.test.ts` 31
passed. tsc baseline: **27 `error TS` total, 0 in either file you will touch** — so any error
appearing in those files afterwards is new, not pre-existing noise.

## 2.8 Mobile

No app change. Both apps take the server's `outcomeOwnerStaffId` first and only fall back to
leg flags when the server omits it (`ui/home/CpCompletionContract.kt:123-140`, and the iOS
equivalent), so correcting the server propagates without a release. The apps do not
second-guess `OTP_OWNER_ONLY` either — it is surfaced verbatim from `convex/http.ts:278-283`.

---
---

# 3. SV-cum-CP — SV skips "Fixed", and follow-up strands it

Both causes are backend-side. The mobile app has no part to change: its SV create screen is
standalone and never holds a CP id, and the Fixed/Confirmed split it renders is driven
entirely by the `confirmationStatus` the server sends.

## 3.1 Intended behaviour (confirmed correct in code)

```
telecaller fixes SV for a CP   →  confirmationStatus = "pending"   →  shows in FIXED
field staff completes the CP   →  setOutcome("interested")         →  flips to "confirmed"
                                                                    →  leaves FIXED
```

The app renders exactly this and needs no change (`SiteVisitsFragment.kt:522-525`: Fixed =
scheduled state **and** `confirmationStatus == "pending"`).

## 3.2 Problem 1 — the SV is born "confirmed" and never passes through Fixed

### Root cause

`convertToSiteVisit` (`convex/marketing/clientPlaceVisits.ts:11123`, insert at **~11318**)
stamps the new SV confirmed at creation, unconditionally:

```ts
routing: "same_area",
confirmationStatus: "confirmed",     // ← never pending, so it never appears in Fixed
confirmationRequiredBy: "cp",
confirmedAt: now,
```

This is the mutation the **mobile** CP→SV conversion calls
(`/api/marketing/clientPlaceVisits/convertToSiteVisit`, from the app's `persistSiteVisit()`
path). Every SV created this way skips Fixed entirely.

Two sibling paths get it right:

* `spawnNewClientCpFromAster` (`:6865`) — *"Stays pending until the CP completes
  interested/converted"*
* `siteVisits.create` (`convex/marketing/siteVisits.ts:4999-5015`):

```ts
const confirmationFields = args.clientPlaceVisitId
  ? { confirmationStatus: "pending", confirmationRequiredBy: "cp", … }   // SV-cum-CP
  : args.routing === "direct_sv"
    ? {}
    : { confirmationStatus: "confirmed", confirmedAt: now, … };
```

### The judgement call — decide before patching

`convertToSiteVisit` runs when the **field staff converts the CP at the client's door**, so
the verification has arguably just happened.

| Option | Change | Result | Risk |
|---|---|---|---|
| **A — pass through** (recommended) | create as `pending`, then immediately stamp `confirmed` + `confirmedAt` + `confirmedByStaffId` in the same mutation | The row genuinely passes Fixed → Confirmed, history shows both, and the staff still completes in one step. No UI change for them. | Very low. End state identical to today. |
| **B — require a separate confirm** | create as `pending` and stop | The SV sits in Fixed until someone confirms it | **High.** The conversion IS the confirmation on this path — nobody confirms a second time, so these SVs sit in Fixed forever. Recreates the stuck-in-Fixed bug from the other direction. |

Take **A** unless you specifically want a second human approval for field-converted SVs. If
you want B, it also needs a way to confirm from the web, or those rows strand.

### Also check

Whether the **web** telecaller flow passes `clientPlaceVisitId` into `siteVisits.create` when
fixing an SV against a CP. If not, the `else` branch fires and those SVs are born confirmed
too — same symptom, different entry point.

> **Not a mobile gap:** `CreateSiteVisitBottomSheet` is the standalone "fix a Site Visit"
> flow and never carries a CP id. The mobile route's arg builder
> (`mobileSiteVisitCreateArgs`, `convex/http.ts:20795-20809`) does not read
> `clientPlaceVisitId` either — worth adding only if you want mobile to fix an SV against a
> CP directly.

## 3.3 Problem 2 — CP closed as Follow-up leaves the fixed SV stranded

### Required behaviour

> When a CP with a linked fixed (pending) SV is closed as **Follow-up**: **cancel that SV**,
> and **create the follow-up CP**.

### What happens today

`closePendingSvCumCpSiteVisit` (`convex/marketing/clientPlaceVisits.ts:5806`) already does
the cancellation half — patches the linked pending SV to `status: "cancelled"` with
`confirmationStatus: "confirmed"` (the schema's resolved value) plus a cancellation marker.

It is reached from only two places:

| Caller | Line | Condition |
|---|---|---|
| `applyCpCompletionEffects` | `:9104` | `completedVisit.clientMet === false` |
| outcome sweep | `:5874` | `outcome === "other"` and the visit is completed |

**`follow_up` is not among them.** A Follow-up close leaves the SV `pending`, sitting in the
Fixed tab indefinitely, with the CP already closed and nothing left to confirm it.

### The change

```ts
// A follow-up means the client is not ready. The SV that was fixed against
// this CP can never be confirmed now — the CP that would have confirmed it is
// closed — so release it instead of leaving it pending in the Fixed tab
// forever. A fresh SV gets fixed if the follow-up lands.
if (
  completedVisit.clientMet === false ||
  completedVisit.outcome === "follow_up"
) {
  await closePendingSvCumCpSiteVisit(ctx, completedVisit, now, notes);
}
```

**Split this from the not-met effects.** Keep the strike counter and the `notMetReschedule`
stamp scoped to `clientMet === false` only — a follow-up is a *productive* visit and must not
increment the "client unavailable" strike count or trigger the 48-hour not-met reactivation
cron. Those behaviours currently share the same `if`.

### The follow-up CP itself

Confirm whether it is already created on this path. The CP outcome flow carries a follow-up
date (the app sends `followUpDate` / `followUpTime` on `setOutcome`) and there is existing
revisit machinery (`CpRevisitInfo` / `pendingCpRevisit` on mobile). If a CP is already
spawned for the follow-up date, nothing more is needed. If not, spawn a **new** CP row dated
to the follow-up date, leaving the closed one closed.

Do **not** reuse the not-met reactivation cron: it anchors on `lastNotMetAt + 48h`, whereas a
follow-up has an explicit date the staff member agreed with the client, and silently
overriding it would be worse than not creating one.

## 3.4 Tests

1. An SV created by `convertToSiteVisit` passes through `pending` and ends `confirmed`
   (option A), with `confirmedAt` and `confirmedByStaffId` set.
2. An SV created by `siteVisits.create` **with** `clientPlaceVisitId` is `pending`.
3. An SV created **without** it, routing `direct_sv`, has no confirmation fields.
4. A CP closed as `follow_up` with a linked pending SV → the SV is `cancelled`.
5. That same close does **not** increment `consecutiveNotMetCount` and does **not** stamp
   `notMetReschedule`.
6. A CP closed as `follow_up` with **no** linked SV → no error, nothing cancelled.
7. A CP closed as `interested` still flips its linked SV to `confirmed` (regression guard).

## 3.5 Mobile

No change on Android or iOS. The Fixed/Confirmed split is read straight from the server's
`confirmationStatus`: `"pending"` → Fixed, anything else → Scheduled, `null` → Scheduled (the
pre-deploy fallback noted in `SiteVisitsFragment.kt:519-521`). Once the server sends
`"pending"` on these rows they appear in the Fixed tab with no app release.

---
---

# Combined checklist

**GeoTrack (§1)**
- [ ] `/api/geotrack/session-route` implemented and registered with the same auth middleware.
- [ ] Per-trip stops fan-out capped or replaced with a bulk query.
- [ ] Mobile-facing payloads emit int64 ms timestamps.
- [ ] `_id` emitted alongside `tripId` / `sessionId`; `sessionState` alongside `state`.
- [ ] `make check` green.
- [ ] Verified on a device: Live and the attendance route strip draw a path.

**Joint CP legacy OTP (§2)**
- [ ] `legacyJointRoleForStaff` added, used in `_readVisitForOtp`.
- [ ] Same rule in `loadJointCpWorkflowContext` — ideally one shared helper.
- [ ] Stamped `workflowRole` still wins everywhere.
- [ ] Unresolvable rows degrade (no throw on a read path) and are logged for repair.
- [ ] Six tests added; both suites green.
- [ ] tsc still 27 errors total, 0 in the changed files.
- [ ] **Both halves released together.**

**SV-cum-CP (§3)**
- [ ] Option A or B chosen for `convertToSiteVisit`.
- [ ] Web telecaller flow confirmed to pass `clientPlaceVisitId`.
- [ ] `follow_up` added to the cancellation trigger, **split** from the not-met effects.
- [ ] Follow-up CP creation confirmed or implemented.
- [ ] Seven tests added.
