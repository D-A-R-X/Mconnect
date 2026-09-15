# MMS web backend (Convex) — endpoints and changes needed

**For:** MMS web/Convex backend owner
**From:** mobile — no web or Convex file has been edited by mobile
**Date:** 2026-09-15
**Companion:** `GEO_SERVICE_ENDPOINTS_NEEDED.md` (the Go side, separate owner)

Ordered by urgency. §1 and §2 are breaking things in production today.

---

## 1. Stop the proxy calling geo routes that do not exist

**Symptom:** the web Geo Track Live page shows *"Live tracking is temporarily unavailable"*
with `{"success":false,"error":"Geo tracking service returned invalid JSON"}`, and the
Online / Offline / Geo-enabled tiles read 0.

**Cause, confirmed by probing the live service:** the geo service is healthy
(`/readyz` → postgres ok, redis ok) and `/api/geotrack/live-status` and `/api/tracking/live`
return real rows. But these return **404 with `Content-Type: text/plain`** and the body
`404 page not found`:

```
/api/geotrack/stats
/api/geotrack/bootstrap
/api/tracking/bootstrap
/api/geotrack/employee-detail
```

`requestGeoTrackingService` then runs `JSON.parse` on that plain text
(`convex/lib/geoTrackingService.ts:79-88`) and produces the "invalid JSON" error verbatim.

### What to change here

Either stop proxying those four paths, or **fall back to the existing Convex handler when
the upstream answers 404**. A proxy that hard-fails on a missing upstream route is worse
than no proxy — it takes down a page that used to work.

> The geo side has since been given a JSON 404 handler (`53b954f` on `kira`), so once that
> deploys the error will at least be legible. That does **not** fix the page: a 404 is still
> a 404. The fallback is what fixes it.

## 2. Do NOT proxy these two — they are Convex business reads

The in-progress branch lists these among proxied routes:

| Route | What it actually reads |
|---|---|
| `GET /api/geotrack/assigned-places` | Convex `clientPlaces` — `api.clientPlaces.listByStaff` (`http.ts:17969`) |
| `GET /api/geotrack/today-visits` | Convex `fieldVisits` — `api.hr.fieldVisits.listTodayByStaff` (`http.ts:17987`) |

The geo service implements **neither** — I grepped `kira` for `assigned-places`,
`today-visits`, `visit/start`, `visit/complete`, `on-duty`, `arrival-otp` and
`clientPlaceVisit`: all absent, and it has no table or concept for any of them.

If that proxy is unconditional, deploying it takes out the mobile CP/SV lists. Exclude both.

---

## 3. Two missing HTTP routes — CP arrival-OTP reveal

The mutations and the IAM key already exist (`marketing.cpVisits.revealOtp`, default for
AVP, hierarchy-scoped, audited on view and copy in `convex/hr/fieldVisitOtp.ts`). Only the
mobile HTTP routes are missing. **Both apps are already built against them** and stay inert
until these are registered.

```
POST /api/marketing/cp-visits/reveal-otp
  body { sourceId, sourceType: "client_place_visit" }
  → api.hr.fieldVisitOtp.revealActiveOtpForSuperAdmin
      { sourceType, sourceId, sessionToken }

POST /api/marketing/cp-visits/reveal-otp/copied
  body { fieldVisitId }
  → api.hr.fieldVisitOtp.recordOtpAssistCopied
      { fieldVisitId, sessionToken }
```

Both: authenticate, forward `sessionToken`, add **no** permission logic of their own — the
mutations already gate themselves and must stay the only gate.

Once live, an AVP/GM can read a team member's active arrival OTP back to them instead of the
staff escalating to the tech team.

---

## 4. Legacy Joint CP — the arrival-OTP half is still missing

The pending runtime fix normalizes roles in `loadJointCpWorkflowContext`, repairing the
**outcome** step. It does not repair the **arrival OTP** step, which happens first.

`convex/hr/fieldVisitOtp.ts:277-288` derives the role with the same vulnerable expression:

```ts
jointFieldVisitRole =
  fieldVisitLeg.workflowRole ??
  (fieldVisitLeg.isPrimary ? "outcome_owner" : "reviewer");   // inverts legacy rows
```

Consumed by both `requestArrivalOtp` (`:598-607`) and `verifyArrivalOtp` (`:1056-1065`).

Rows created before `840299a3` (2026-09-04) carry no `workflowRole` and put `isPrimary` on
the **senior**, the reverse of today's rule — so the real owner is rejected with
`OTP_OWNER_ONLY` and never reaches the outcome form the other fix repairs.

> **Ship both halves in one release.** Shipping only the outcome half is *worse* than today:
> the workflow query starts reporting the junior as owner, so both apps show them the OTP
> screen, which the server then refuses.

Fix: when `workflowRole` is absent, resolve from `resolveJointCpAuthorityByLevel` with
`requireEligibleStaff: false` (eligibility is a creation rule; a participant on a months-old
visit may have changed department since), swallowing the throw so a deleted staff member or
a pair that now shares a level degrades instead of failing a read that gates a staff member
standing at a client's door. **Use one shared helper** for both this and
`loadJointCpWorkflowContext` so a future third caller cannot reintroduce the split.

Full patch in `BACKEND_HANDOFF_2026-09-15.md` §2.

**Until this ships**, affected staff see *"Joint CP role assignment is out of sync"*. That is
the app deliberately failing closed when the server's declared role contradicts the
owner/reviewer ids it sent in the same response — not a separate bug, and there is no
app-side workaround.

---

## 5. SV-cum-CP — two lifecycle fixes

### 5.1 The SV is born "confirmed" and never passes through Fixed

`convertToSiteVisit` (`convex/marketing/clientPlaceVisits.ts:11123`, insert ~`11318`) stamps
`confirmationStatus: "confirmed"` and `confirmedAt` at creation, unconditionally. This is the
mutation the mobile CP→SV conversion calls, so those SVs skip the Fixed tab entirely.

Two sibling paths get it right: `spawnNewClientCpFromAster` (`:6865`) creates `pending`, and
`siteVisits.create` (`convex/marketing/siteVisits.ts:4999-5015`) branches on
`clientPlaceVisitId`.

**Recommended:** create as `pending`, then immediately stamp `confirmed` + `confirmedAt` +
`confirmedByStaffId` in the same mutation. The row genuinely passes Fixed → Confirmed and the
end state is identical to today. Do **not** simply create as `pending` and stop — on this
path the conversion *is* the confirmation, so nobody confirms a second time and those SVs
would sit in Fixed forever.

Also check whether the **web** telecaller flow passes `clientPlaceVisitId` into
`siteVisits.create`; if not, the `else` branch fires and those SVs are born confirmed too.

### 5.2 A follow-up close strands the fixed SV

`closePendingSvCumCpSiteVisit` (`:5806`) already performs the required cancellation, but is
reached only from `clientMet === false` (`:9104`) and `outcome === "other"` (`:5874`).
`follow_up` is not among them, so the SV sits pending in Fixed with its CP already closed and
nothing able to confirm it.

```ts
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

For the follow-up CP itself: confirm whether one is already created on this path (the app
sends `followUpDate` / `followUpTime` on `setOutcome`). If not, spawn a new CP dated to the
follow-up date. Do **not** reuse the not-met cron — it anchors on `lastNotMetAt + 48h` and
would silently override the date agreed with the client.

---

## 6. Stop reading `locationPoints` — it has been empty since 9 Sep

Convex still computes trip distance, travel allowance and CP distance by scanning its own
`locationPoints`, which current app builds stopped writing to on 2026-09-09 (`e54016d7`
repointed telemetry at the geo service).

| File | Line | Effect while the table is empty |
|---|---|---|
| `convex/geotrack/trips.ts` | 609 | `completeOnDutyTrip` — distance falls back to **straight line** |
| `convex/geotrack/trips.ts` | 329 | `buildDailyTrips` produces nothing |
| `convex/travelAllowance.ts` | 505 | polyline falls back to empty |
| `convex/marketing/clientPlaceVisits.ts` | 677, 983, 1224, 10788 | CP actual-distance → straight line |
| `convex/hr/fieldVisits.ts` | 1016 | field-visit trail |
| `convex/geotrack/monitoring.ts` | 268 | monitoring blind |
| `convex/geotrack/roads.ts` | 201, 337 | nothing to snap |
| `convex/adminCpVisitRepair.ts` | 2139 | repair reconstructs wrong distances |

**This costs money.** A staff member who drives a 40 km loop and ends near where they started
records ≈0 m, drops under the >1 km travel-allowance qualifier, and is not paid. No error, no
log — it looks like a short trip.

Once the geo service ships the metrics endpoints (its doc §2.2), replace each of these with a
call through the existing `requestGeoTrackingService()` helper. Prioritise `trips.ts:609` and
`clientPlaceVisits.ts:983` — those two write the wrong numbers.

Keep the straight-line fallback, but only when the service answers `source: "empty"`, and
stamp the row (`distanceSource: "straightline"`) so these become auditable instead of silent.

---

## 7. Report trip boundaries to the geo service

So the service can attribute points to a trip, call its segment endpoints from the handlers
that already own these lifecycles:

| Convex handler | Call |
|---|---|
| `api.geotrack.trips.startOnDutyTrip` | `POST /api/tracking/segments/start` with `segmentType: "on_duty"` |
| `POST /api/geotrack/visit/start` | same, `segmentType: "cp_trip"` |
| `api.geotrack.trips.completeOnDutyTrip` | `POST /api/tracking/segments/end` — **use the returned `distanceMeters`** |
| `POST /api/geotrack/visit/complete` | same |

`refSource` should be `convex.geoTrips` / `convex.fieldVisits` and `refId` the row id.

Doing this from Convex rather than the app is deliberate: it also fixes phones that are
never updated, which no app release can reach.

---

## 8. Raise the minimum app version

The tracking traffic still hitting `api-mfpl` (`geotrack/tamper/report` 16.4k,
`tracking/bootstrap` 4.3k, `tracking/device/sync` 4.3k in 24 h) is **legacy app builds**.
Nothing in the current Android app, iOS app or web calls those paths — they were removed by
`e54016d7` on 2026-09-09, versionCode 72.

Raise the floor via the existing `GET /api/mobile/app-version` gate (already called by the
app, ~1k req/24 h). Published versionCode is **83**; the safe floor is **72**.

**Do not delete those Convex routes first** — un-upgraded phones would lose tracking
silently. Let the traffic decay to zero, then retire them.

---

## Checklist

- [ ] Proxy falls back to Convex when the geo upstream 404s (§1).
- [ ] `assigned-places` and `today-visits` excluded from the proxy (§2).
- [ ] `POST /api/marketing/cp-visits/reveal-otp` and `/reveal-otp/copied` registered (§3).
- [ ] Legacy Joint CP role normalization in `_readVisitForOtp`, shipped **with** the outcome half (§4).
- [ ] `convertToSiteVisit` passes through pending → confirmed (§5.1).
- [ ] `follow_up` added to the SV cancellation trigger, split from the not-met effects (§5.2).
- [ ] `locationPoints` readers repointed at the geo metrics endpoints (§6).
- [ ] Segment start/end called from the CP and on-duty handlers (§7).
- [ ] Minimum app version raised to 72 (§8).
- [ ] `pnpm exec tsc --noEmit -p convex/tsconfig.json` — baseline is **27** pre-existing errors; anything new in a changed file is yours.
