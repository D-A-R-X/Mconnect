# SV-cum-CP — Site Visit skips "Fixed", and follow-up leaves it stranded

**For:** backend / Convex admin
**From:** mobile — investigation only, no web/Convex file edited
**Date:** 2026-09-12

Two reported problems, both backend-side. The mobile app has no part to change: its SV
create screen is standalone and never holds a CP id, and the Fixed/Confirmed split it
renders is driven entirely by the `confirmationStatus` the server sends.

---

## Intended behaviour (confirmed correct in code)

```
telecaller fixes SV for a CP   →  confirmationStatus = "pending"   →  shows in FIXED
field staff completes the CP   →  setOutcome("interested")         →  flips to "confirmed"
                                                                    →  leaves FIXED
```

The app renders exactly this and needs no change
(`SiteVisitsFragment.kt:522-525`: Fixed = scheduled state **and** `confirmationStatus == "pending"`).

---

## Problem 1 — the SV is born "confirmed" and never passes through Fixed

### Root cause

`convertToSiteVisit` (`convex/marketing/clientPlaceVisits.ts:11123`, insert at **~11318**)
stamps the new SV as confirmed at creation, unconditionally:

```ts
routing: "same_area",
confirmationStatus: "confirmed",     // ← never pending, so it never appears in Fixed
confirmationRequiredBy: "cp",
confirmedAt: now,
```

This is the mutation the **mobile** CP→SV conversion calls
(`/api/marketing/clientPlaceVisits/convertToSiteVisit`, reached from the app's
`persistSiteVisit()` path). So every SV created this way skips Fixed entirely — matching
the report.

Compare `spawnNewClientCpFromAster` (`:6865`), which gets it right for the same situation:

```ts
// Stays pending until the CP completes "interested"/converted; the
// setOutcome flip then stamps confirmed/confirmedAt/confirmedByStaffId.
confirmationStatus: "pending",
confirmationRequiredBy: "cp",
```

And `siteVisits.create` (`convex/marketing/siteVisits.ts:4999-5015`), which is also correct:

```ts
const confirmationFields = args.clientPlaceVisitId
  ? { confirmationStatus: "pending", confirmationRequiredBy: "cp", … }   // SV-cum-CP
  : args.routing === "direct_sv"
    ? {}
    : { confirmationStatus: "confirmed", confirmedAt: now, … };
```

So two of the three creation paths are right and `convertToSiteVisit` is the outlier.

### The judgement call — please decide before patching

`convertToSiteVisit` runs when the **field staff converts the CP at the client's door**.
The verification has, arguably, just happened. So there are two defensible fixes:

| Option | Change | Result | Risk |
|---|---|---|---|
| **A — pass through** (recommended) | create as `pending`, then immediately stamp `confirmed` + `confirmedAt` + `confirmedByStaffId` in the same mutation | The row genuinely passes Fixed → Confirmed, history shows both, and the staff still sees it complete in one step. Nothing in the UI changes for them. | Very low. End state identical to today. |
| **B — require a separate confirm** | create as `pending` and stop | The SV sits in Fixed until someone confirms it | **High.** The conversion IS the confirmation on this path — nobody is going to confirm it a second time, so these SVs would sit in Fixed forever. This recreates the exact "stuck in Fixed" bug from the other direction. |

Take **A** unless you specifically want a second human approval step for field-converted SVs.
If you do want B, it also needs a way to confirm from the web, or those rows strand.

### Also check

Whether the **web** telecaller flow passes `clientPlaceVisitId` into `siteVisits.create`
when fixing an SV against a CP. If it does not, the `else` branch fires and those SVs are
born confirmed too — same symptom, different entry point.

> **Not a mobile gap:** the app's SV create screen (`CreateSiteVisitBottomSheet`) is the
> standalone "fix a Site Visit" flow and never carries a CP id, so it has nothing to send.
> The mobile route's arg builder (`mobileSiteVisitCreateArgs`, `convex/http.ts:20795-20809`)
> also does not read `clientPlaceVisitId` — worth adding only if you ever want mobile to
> fix an SV against a CP directly.

---

## Problem 2 — CP closed as Follow-up leaves the fixed SV stranded

### Required behaviour

> When a CP that has a linked fixed (pending) SV is closed as **Follow-up**:
> **cancel that SV**, and **create the follow-up CP**.

### What happens today

`closePendingSvCumCpSiteVisit` (`convex/marketing/clientPlaceVisits.ts:5806`) already does
exactly the cancellation half — it patches the linked pending SV to
`status: "cancelled"` with `confirmationStatus: "confirmed"` (the schema's resolved value)
plus a cancellation marker.

But it is only reached from two places:

| Caller | Line | Condition |
|---|---|---|
| `applyCpCompletionEffects` | `:9104` | `completedVisit.clientMet === false` — client not met |
| outcome sweep | `:5874` | `outcome === "other"` and the visit is completed |

**`follow_up` is not among them.** So a Follow-up close leaves the SV `pending` and it sits
in the Fixed tab indefinitely, with the CP already closed and nothing left to confirm it.

### The change

In `applyCpCompletionEffects`, extend the cancellation trigger to cover a follow-up close:

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

Keep the existing `clientMet === false` branch's other effects (the not-met strike counter
and `notMetReschedule` stamp) **scoped to `clientMet === false` only** — a follow-up is a
productive visit and must not increment the "client unavailable" strike count or trigger
the 48-hour not-met reactivation cron. Those two behaviours are different and share the
current `if`, so split them rather than widening the whole block.

### The follow-up CP itself

Confirm whether the follow-up CP is already being created on this path. The CP outcome flow
carries a follow-up date (the app sends `followUpDate` / `followUpTime` on
`setOutcome`), and there is existing revisit machinery (`CpRevisitInfo` /
`pendingCpRevisit` on the mobile side). If a CP is already spawned for the follow-up date,
nothing more is needed. If not, spawn it the same way the not-met reactivation does — a
**new** CP row dated to the follow-up date, leaving the closed one closed.

Do **not** reuse the not-met reactivation cron for this: that one is anchored on
`lastNotMetAt + 48h`, whereas a follow-up has an explicit date the staff member chose with
the client, and silently overriding it would be worse than not creating one.

---

## Tests to add

`convex/marketing/siteVisits.test.ts` / `convex/jointCpVisits.test.ts` as appropriate:

1. An SV created by `convertToSiteVisit` passes through `pending` and ends `confirmed`
   (option A), with `confirmedAt` and `confirmedByStaffId` set.
2. An SV created by `siteVisits.create` **with** `clientPlaceVisitId` is `pending`.
3. An SV created **without** it, routing `direct_sv`, has no confirmation fields.
4. A CP closed as `follow_up` with a linked pending SV → the SV is `cancelled`.
5. That same close does **not** increment `consecutiveNotMetCount` and does **not** stamp
   `notMetReschedule` — a follow-up is not a strike.
6. A CP closed as `follow_up` with **no** linked SV → no error, nothing cancelled.
7. A CP closed as `interested` still flips its linked SV to `confirmed` (regression guard
   on the working path).

---

## Mobile

No change on Android or iOS. The Fixed/Confirmed split is read straight from the server's
`confirmationStatus`, and the app already handles all three values correctly — `"pending"`
→ Fixed, anything else → Scheduled, `null` → Scheduled (the pre-deploy fallback noted in
`SiteVisitsFragment.kt:519-521`).

Once the server sends `"pending"` on these rows, they will appear in the Fixed tab with no
app release.
