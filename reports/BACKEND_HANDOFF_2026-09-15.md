# Backend handoff — 2026-09-15

**Supersedes:** `BACKEND_HANDOFF_2026-09-12.md` (everything in it is carried forward here).
**For:** backend / Convex / geo-service admin
**From:** mobile (Android `merge`+`main` @ `e8c2b1ee`, iOS `darx` @ `3657bc8`)
**Scope:** investigation and specs. **No web, Convex or Go source has been edited by mobile.**

---

## 0. Status board

| # | Item | Where | State |
|---|---|---|---|
| **A** | **In-progress backend work could not be verified, and one claim looks dangerous** | `codex/development-testing-publish-20260912` | **Read §1 before deploying anything** |
| 1 | GeoTrack: `session-route` missing + wire contract diverged | `geo-tracking-service` (Go) | Open. Android mitigated, **iOS still broken** |
| 2 | Legacy Joint CP rejects its real owner at the arrival OTP | `convex/hr/fieldVisitOtp.ts` | Open |
| 3 | SV-cum-CP skips "Fixed"; follow-up strands the fixed SV | `convex/marketing/clientPlaceVisits.ts` | Open |
| 4 | Reviewer is never notified that the outcome is waiting | Convex push | **New — found 15 Sep** |
| 5 | Joint CP showed "Completed" before review | mobile | **Fixed & shipped, both platforms** |

---

# 1. Verification of the in-progress work — READ FIRST

The handoff `GEOTRACK_CP_BACKEND_DEVELOPER_HANDOFF_2026-09-12.md` describes work on branch
`codex/development-testing-publish-20260912`.

## 1.1 The implementation is not verifiable

The branch exists on the remote, but its only two commits (`f82c7a2b`, `9fb16c65`, both
messaged `a`) touch `convex/schema.ts` and `convex/asterCalls.ts` — **four lines total**,
none of the claimed files. `convex/marketing/lib/jointCpRoles.ts` does not exist anywhere in
the repository, on any branch.

That matches the doc's own "not committed or pushed", so the work is local to one machine.
**Nothing about the OTP or SV logic could be reviewed.** Please push the branch.

## 1.2 CRITICAL — the proxy list includes two Convex business reads

The handoff lists these among routes now proxied to the Geo service:

```
GET /api/geotrack/assigned-places
GET /api/geotrack/today-visits
```

Those are **not geo data**. Today they run:

| Route | Handler | Reads |
|---|---|---|
| `assigned-places` | `api.clientPlaces.listByStaff` (`http.ts:17969`) | Convex `clientPlaces` |
| `today-visits` | `api.hr.fieldVisits.listTodayByStaff` (`:17987`) | Convex `fieldVisits` |

Checked every claimed-proxy route against the Go source:

| Route | Exists in Go service? |
|---|---|
| `assigned-places`, `today-visits`, `employee-detail`, `stats`, `geotrack/bootstrap`, `session-route`, `tracking/bootstrap` | **No** |
| `geotrack/route`, `geotrack/geocode-address`, `tracking/device/sync` | Yes |

**If that proxy is unconditional rather than a Convex-first fallback, deploying it breaks
the mobile assigned-places and today-visits screens** — core CP/SV surfaces — which is worse
than the bug being fixed. The 12 Sep handoff explicitly listed those two under *do not
migrate*. Confirm the fallback, or exclude both routes.

## 1.3 The proxy cannot fix `session-route` for mobile

The app calls `api-geo` **directly** (`DIRECT_SESSION_ROUTE_URL`) and never routes through
Convex. A Convex proxy only helps the web. The endpoint still has to be built in Go (§2.1).

## 1.4 Scope widened on the follow-up fix

The doc describes the SV cancellation firing on **"postponed/followed up"**. The spec asked
for `follow_up` only. Cancelling a fixed SV on a *postpone* may well be right, but it was
not requested — confirm it is intentional.

## 1.5 The typecheck was abandoned, not completed

It was stopped after ~2 minutes; it completes in about **3**. Measured baseline on a clean
checkout:

```
pnpm exec tsc --noEmit -p convex/tsconfig.json
→ 27 "error TS" lines, ALL pre-existing (mostly convex/telecallerMissions.ts + a missing web-push module)
→ 0 in convex/marketing/clientPlaceVisits.ts
→ 0 in convex/jointCpVisits.test.ts
```

So the bar is exact: still **27** total, still **0** in the changed files. Anything new there
is theirs, not noise. Right now there is no typecheck at all on a change spanning eight files.

---

# 2. GeoTrack — "tracking is not working"

Measured against the geo repo's own rule:

> Preserve the published mobile and web HTTP contracts until compatibility routes are
> explicitly retired. — `AGENTS.md`

> **Why mobile wrote no Go:** `AGENTS.md` requires `make check` before committing, and there
> is no Go toolchain on the investigating machine — nothing could be compiled, vetted or
> tested. Pushing unverified Go into the service carrying live attendance tracking is not
> worth the risk. The diagnosis is also committed at `docs/MOBILE_CONTRACT_GAPS.md` on `kira`.

## 2.1 `/api/geotrack/session-route` is not implemented

The **only** endpoint in the app's direct-GeoTrack set with no handler; all fourteen others
match. Two screens 404: **GeoTrack Live** and the **attendance review route strip**. Both are
map views, so the symptom is "tracking shows nothing".

`GET /api/geotrack/session-route`

| Query | Type | Notes |
|---|---|---|
| `staffId` | string, optional | defaults to the authenticated staff |
| `dayStart` | int64 | Unix ms, required |
| `dayEnd` | int64 | Unix ms, required |
| `minStopMinutes` | int, optional | app sends `30` |

```jsonc
// {"success":true,"data": … }
{
  "session": { /* same shape as /api/tracking/sessions/current */ },
  "timeline": [ /* same shape as /api/geotrack/timeline */ ],
  "trips":    [ /* same shape as /api/tracking/trips */ ],
  "stops":    [ /* TripStop, flattened across the day's trips */ ],
  "routeStart": 1789193662184,
  "routeEnd":   1789222462184,
  "distanceMeters": 41230
}
```

**No new SQL needed** — every field already has a service method:

| Field | Source |
|---|---|
| `timeline` | `service.Timeline(ctx, staffID, start, end, limit)` |
| `trips` | `service.ListTrips(ctx, TripFilter{StaffID, From, To, Limit})` |
| `session` | `service.CurrentSession(ctx, staffID)` |
| `stops` | `service.GetTrip(ctx, tripID).Stops`, filtered by `minStopMinutes` |
| `distanceMeters` | sum of `trips[].DistanceMeters` |
| `routeStart`/`routeEnd` | min/max of timeline `RecordedAt`, falling back to the query range |

Mirror `GET /api/geotrack/timeline` (`internal/httpapi/server.go:623`) for guards, `int64Query`
parsing, `developmentStaffID` and `statusForTrackingError`. Register it beside the others so
it inherits the same auth middleware. **Cap the per-trip `GetTrip` fan-out** — a long day can
hold dozens of trips and this sits behind a map.

## 2.2 Timestamps and key names diverge from the published contract

Go marshals `time.Time` as RFC 3339. The published mobile contract is **epoch milliseconds**.
The decoder throws and **discards the entire response** — not one field, the whole payload.

| Type | Field | Mobile expects | Service sends |
|---|---|---|---|
| `LocationPoint` (`service.go:334`) | `recordedAt` | int64 ms | RFC 3339 |
| `Trip` (`web.go:17`) | id | key `_id` | key `tripId` |
| `Trip` (`web.go:20-21`) | `startedAt`, `endedAt` | int64 ms | RFC 3339 |
| `TripStop` (`web.go:52-53`) | `arrivedAt`, `departedAt` | int64 ms | RFC 3339 |
| `Session` (`service.go:70`) | id | key `_id` | key `sessionId` |
| `Session` (`service.go:75`) | state | key `sessionState` | key `state` |
| `Session` (`service.go:77-78`) | `startedAt`, `endedAt` | int64 ms | RFC 3339 |

**Android is mitigated** (shipped `4dff5204`): accepts either shape, either key spelling, Go's
variable-length fractional seconds, and keeps a `+05:30` offset at its true instant. 9 tests.

**iOS is NOT mitigated.** Its models still declare `startedAt: Double?` and `case id = "_id"`,
so iOS stays broken on trips/session data until this service emits the published contract.

### Recommended fix

Emit the published contract at the **HTTP seam only**, leaving internal Go types idiomatic,
so web consumers are untouched:

* `time.Time` → `t.UnixMilli()`; `*time.Time` → `omitempty` int64 pointer
* **add** `_id` alongside `tripId` / `sessionId`
* **add** `sessionState` alongside `state`

Adding keys rather than renaming keeps it backward compatible.

## 2.3 Verification

```bash
make check      # required by AGENTS.md
```

1. `session-route` returns timeline, trips, stops, and `distanceMeters` = sum of trips.
2. Missing/invalid `dayStart`/`dayEnd` → 400, matching the timeline handler's wording.
3. `minStopMinutes` filters short stops out.
4. No session that day → `session: null` with the rest populated, **not** a 404.
5. Every mobile-facing timestamp is an integer; `_id` present wherever `tripId`/`sessionId` is.
6. Staff with no data → empty arrays, `success: true`.

Then confirm on a device that Live and the attendance route strip draw a path.

---

# 3. Joint CP — legacy arrival-OTP fix (the missing half)

## 3.1 Why this is needed

The pending runtime fix normalizes roles in `loadJointCpWorkflowContext`, repairing the
**outcome** step. It does not repair the **arrival OTP** step, which happens FIRST.

> **Ship both halves in one release.** Shipping only the outcome half is *worse* than today:
> the workflow query starts reporting the junior as owner, so both apps show them the OTP
> screen, which the server then refuses.

## 3.2 Scope

Joint CP rows created before `840299a3` (2026-09-04). Their legs have `workflowRole` **absent**
and `isPrimary` on the **senior** — the reverse of today's rule. Rows after that commit
snapshot `workflowRole` at creation (`convex/marketing/lib/jointCp.ts:137`) and are fine.

## 3.3 The defect

`convex/hr/fieldVisitOtp.ts:277-288`, in `_readVisitForOtp`:

```ts
jointFieldVisitRole =
  fieldVisitLeg.workflowRole ??
  (fieldVisitLeg.isPrimary ? "outcome_owner" : "reviewer");   // ← inverts legacy rows
```

Two gates consume it, both rejecting the real owner:

| Gate | Line | Message |
|---|---|---|
| request arrival OTP | `:598-607` | `OTP_OWNER_ONLY: Only the Joint CP outcome owner can request arrival OTP.` |
| verify arrival OTP | `:1056-1065` | `OTP_OWNER_ONLY: Only the Joint CP outcome owner can verify arrival OTP.` |

## 3.4 The patch

```ts
import { resolveJointCpAuthorityByLevel } from "../marketing/lib/jointCpAuthority";
```

```ts
/**
 * Role for a leg that predates workflowRole snapshotting.
 *
 * Legacy legs carry no role and put `isPrimary` on the SENIOR participant,
 * the reverse of the current rule, so `isPrimary` cannot be trusted as a
 * fallback. Resolve from the same numeric-level authority that creation and
 * the admin repair tool use, so all three agree.
 *
 * Returns null — never throws — when the pair cannot be resolved (staff
 * deleted or deactivated, no numeric level, or a pair that has since drifted
 * to the same level). This runs on a READ path that gates arrival for a staff
 * member standing at a client's door; a data problem must degrade, not become
 * a hard failure.
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
      // Eligibility is a CREATION rule. A participant on a months-old visit
      // may since have moved department or changed designation; failing that
      // check here would block a legitimate arrival on an existing visit.
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

```ts
jointFieldVisitRole =
  // A stamped role always wins — never re-label a correctly created visit.
  fieldVisitLeg.workflowRole ??
  (await legacyJointRoleForStaff(ctx, legs, visit.staffId)) ??
  // Unchanged last resort, so this patch can only ever improve a row.
  (fieldVisitLeg.isPrimary ? "outcome_owner" : "reviewer");
```

## 3.5 Both halves must agree

If the OTP owner and the outcome owner disagree the visit **deadlocks**: one person can
verify arrival, a different person can file the outcome, neither can finish alone.

Apply the identical rule in `loadJointCpWorkflowContext`
(`convex/marketing/clientPlaceVisits.ts:612`) — normalize only when `workflowRole` is absent,
`requireEligibleStaff: false`, swallow the throw. **Best: extract one shared helper into
`convex/marketing/lib/jointCp.ts`** so a future third caller cannot reintroduce the split.

## 3.6 Rows this cannot fix

`resolveJointCpAuthorityByLevel` throws — and the helper returns null — when a participant is
missing/inactive, has no numeric level, or the pair now shares a level or template (someone
was promoted). Those rows keep today's behaviour; they are genuinely ambiguous and need the
admin repair tool with a human deciding. Log them so the tool gets a work list.

## 3.7 Tests — `convex/fieldVisitOtp.test.ts`

That suite has **14 tests and no Joint CP legacy coverage at all**, which is why the gap was
invisible. Add a legacy fixture (different numeric levels, both legs `workflowRole: undefined`,
`isPrimary: true` on the **senior**):

1. Lower-level staff `requestArrivalOtp` → succeeds, no `OTP_OWNER_ONLY`.
2. Lower-level staff `verifyArrivalOtp` → succeeds.
3. Senior calls either → still refused.
4. A row **with** `workflowRole` is untouched, even when it disagrees with current levels.
5. Participants now sharing a level → helper returns null, no throw, a role still returned.
6. End-to-end on one legacy row: the same staff passes the OTP gate **and** the outcome gate.

```bash
pnpm vitest run convex/fieldVisitOtp.test.ts convex/jointCpVisits.test.ts
```

Baseline: `fieldVisitOtp.test.ts` 14 passed, `jointCpVisits.test.ts` 31 passed.

## 3.8 Mobile

No app change. Both apps take the server's `outcomeOwnerStaffId` first and only fall back to
leg flags when the server omits it (`ui/home/CpCompletionContract.kt:123-140` + iOS
equivalent), so correcting the server propagates without a release.

**Until it is fixed**, affected staff see *"Joint CP role assignment is out of sync. Refresh
this visit or ask admin to repair it"*. That message is the app **deliberately failing
closed**: `verifiedJointCpWorkflowForActor` nulls the role and every action flag when the
server's declared role contradicts the owner/reviewer ids in the same response. It is the
symptom, not a separate bug — and there is no app-side workaround, because the existing
OTP-assist escape hatch needs an OTP that the junior cannot generate in the first place.

---

# 4. SV-cum-CP — SV skips "Fixed", and follow-up strands it

Both causes are backend-side; the mobile app needs no change. Its SV create screen is
standalone and never holds a CP id, and the Fixed/Confirmed split is driven entirely by the
`confirmationStatus` the server sends.

## 4.1 Intended behaviour (confirmed correct in code)

```
telecaller fixes SV for a CP   →  confirmationStatus = "pending"   →  shows in FIXED
field staff completes the CP   →  setOutcome("interested")         →  flips to "confirmed"
```

The app renders exactly this (`SiteVisitsFragment.kt:522-525`).

## 4.2 The SV is born "confirmed" and never passes through Fixed

`convertToSiteVisit` (`convex/marketing/clientPlaceVisits.ts:11123`, insert ~**11318**):

```ts
confirmationStatus: "confirmed",   // ← never pending, so it never appears in Fixed
confirmedAt: now,
```

This is the mutation the **mobile** CP→SV conversion calls. Two sibling paths get it right:
`spawnNewClientCpFromAster` (`:6865`) creates `pending`, and `siteVisits.create`
(`convex/marketing/siteVisits.ts:4999-5015`) branches on `clientPlaceVisitId`.

### Decide before patching

| Option | Change | Result | Risk |
|---|---|---|---|
| **A — pass through** (recommended) | create `pending`, then immediately stamp `confirmed` + `confirmedAt` + `confirmedByStaffId` in the same mutation | Row genuinely passes Fixed → Confirmed; staff still completes in one step | Very low — end state identical to today |
| **B — separate confirm** | create `pending` and stop | SV waits in Fixed for a confirmation | **High.** On this path the conversion IS the confirmation, so nobody confirms twice and these sit in Fixed forever |

Also check whether the **web** telecaller flow passes `clientPlaceVisitId` into
`siteVisits.create`. If not, the `else` branch fires and those SVs are born confirmed too.

## 4.3 Follow-up leaves the fixed SV stranded

Required: when a CP with a linked fixed (pending) SV closes as **Follow-up**, **cancel that
SV** and **create the follow-up CP**.

`closePendingSvCumCpSiteVisit` (`:5806`) already does the cancellation, but is reached only
from `clientMet === false` (`:9104`) and `outcome === "other"` (`:5874`). **`follow_up` is not
among them**, so the SV sits pending in Fixed with its CP already closed.

```ts
// A follow-up means the client is not ready. The SV fixed against this CP can
// never be confirmed now — the CP that would have confirmed it is closed — so
// release it instead of leaving it pending in Fixed forever.
if (
  completedVisit.clientMet === false ||
  completedVisit.outcome === "follow_up"
) {
  await closePendingSvCumCpSiteVisit(ctx, completedVisit, now, notes);
}
```

**Split this from the not-met effects.** Keep the strike counter and the `notMetReschedule`
stamp scoped to `clientMet === false` only — a follow-up is a *productive* visit and must not
increment the "client unavailable" count or trigger the 48-hour reactivation cron. They
currently share the same `if`.

**The follow-up CP itself:** confirm whether it is already created on this path (the app sends
`followUpDate`/`followUpTime` on `setOutcome`, and `CpRevisitInfo` machinery exists). If not,
spawn a new CP dated to the follow-up date. Do **not** reuse the not-met cron — it anchors on
`lastNotMetAt + 48h` and would silently override the date agreed with the client.

## 4.4 Tests

1. `convertToSiteVisit` passes through `pending` and ends `confirmed` with `confirmedAt` and
   `confirmedByStaffId` set (option A).
2. `siteVisits.create` **with** `clientPlaceVisitId` → `pending`.
3. Without it, routing `direct_sv` → no confirmation fields.
4. CP closed as `follow_up` with a linked pending SV → SV `cancelled`.
5. That close does **not** increment `consecutiveNotMetCount` nor stamp `notMetReschedule`.
6. CP closed as `follow_up` with **no** linked SV → no error.
7. CP closed as `interested` still confirms its linked SV (regression guard).

---

# 5. NEW — the reviewer is never told the outcome is waiting

Found 15 Sep while verifying the Joint CP handoff.

When the junior submits their outcome, the senior's screen updates **only if that trip screen
is open** — it polls every 5 seconds (`JOINT_WORKFLOW_POLL_MS`). There is **no push
notification** for the handoff.

So a senior with the app backgrounded, or on any other screen, has no idea they are needed.
The Joint CP sits in `pending_review` indefinitely, and neither participant gets credit until
the senior happens to open that visit.

**Suggested fix (backend):** send a push to the reviewer on `joint-submit-review`, reusing the
existing `pushNotifications.sendToStaff` path. The app already routes categories like
`geotrack-tamper-alert` and `on-duty-started` (`PushTokenManager.kt:83-89`), so a new
`joint-cp-review-pending` category needs no app release to be delivered — only to be
deep-linked, which can follow later.

Worth pairing with a reminder if the review is still pending after N hours, since this blocks
credit for **both** staff.

---

# 6. What mobile shipped since 12 Sep (context for testing)

| Change | Commit | Effect |
|---|---|---|
| GeoTrack wire tolerance | `4dff5204` | Android accepts RFC 3339 **and** epoch ms, `_id`/`tripId`/`sessionId`, `sessionState`/`state`. Works against either backend. |
| Trip↔context linkage | `d219c5ef` | Every point and heartbeat carries `contextType`/`contextId`, stamped at capture. Both platforms. |
| IAM-gated OTP reveal | `cabcaf71` | AVP/GM reveal for their reporting team. Inert until the two routes in the appendix ship. |
| Joint CP "Completed" bug | `e8c2b1ee` / iOS `3657bc8` | Card no longer reads Completed before review; shows **Pending Review** with a role-aware action. |

> iOS commits are **not compiled** — there is no Swift toolchain on the mobile machine. They
> need a Mac build before any TestFlight upload.

---

# Appendix — CP arrival-OTP reveal routes (still missing)

The Convex mutations and the IAM key already exist (`marketing.cpVisits.revealOtp`, default
for AVP, hierarchy-scoped and audited in `convex/hr/fieldVisitOtp.ts`). Only the mobile HTTP
routes are missing. Both apps are already built against them and stay inert until registered.

```
POST /api/marketing/cp-visits/reveal-otp
  body { sourceId, sourceType: "client_place_visit" }
  → api.hr.fieldVisitOtp.revealActiveOtpForSuperAdmin { sourceType, sourceId, sessionToken }

POST /api/marketing/cp-visits/reveal-otp/copied
  body { fieldVisitId }
  → api.hr.fieldVisitOtp.recordOtpAssistCopied { fieldVisitId, sessionToken }
```

Both: authenticate, forward `sessionToken`, add **no** permission logic of their own — the
mutations already gate themselves and must stay the only gate.

---

# Combined checklist

**Before anything (§1)**
- [ ] Push `codex/development-testing-publish-20260912` so the work can be reviewed.
- [ ] Confirm `assigned-places` / `today-visits` fall back to Convex, or exclude them from the proxy.
- [ ] Confirm the `postponed` trigger was intentional.
- [ ] Finish the typecheck: still 27 errors total, 0 in the changed files.

**GeoTrack (§2)**
- [ ] `/api/geotrack/session-route` implemented, registered, stops fan-out capped.
- [ ] Mobile-facing payloads emit int64 ms; `_id` and `sessionState` added alongside.
- [ ] `make check` green; verified on a device.

**Joint CP legacy OTP (§3)**
- [ ] `legacyJointRoleForStaff` in `_readVisitForOtp`; same rule in `loadJointCpWorkflowContext`.
- [ ] Stamped `workflowRole` still wins; unresolvable rows degrade and are logged.
- [ ] Six tests added; **both halves released together**.

**SV-cum-CP (§4)**
- [ ] Option A or B chosen; web `clientPlaceVisitId` confirmed.
- [ ] `follow_up` added to the cancellation trigger, **split** from the not-met effects.
- [ ] Follow-up CP creation confirmed; seven tests added.

**Reviewer notification (§5)**
- [ ] Push on `joint-submit-review` to the reviewer.
- [ ] Optional reminder while `pending_review` persists.
