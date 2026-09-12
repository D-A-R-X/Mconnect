# Review — Joint CP Role Inversion Runtime Fix

**Reviewing:** `JOINT_CP_ROLE_INVERSION_RUNTIME_FIX.md`
**Reviewed by:** mobile (Android `merge`, iOS `darx`) — read-only audit, no web/Convex/app file changed
**Date:** 2026-09-12
**Verdict:** approach is correct, but **the fix as described does not resolve the reported symptom** — one required half is missing.

---

## 0. Summary

| # | Finding | Action |
|---|---------|--------|
| 1 | The change is **not present on any branch** of `manjugroupsdev/manjusitedevelopment`. | Confirm it is committed and pushed. |
| 2 | **The arrival-OTP gate is a second, independent copy of the same inversion**, in a file the change does not touch. Arrival happens *before* the outcome, so the affected staff are still blocked at step 1. | **Must fix before this ships.** |
| 3 | The normalization is applied at one call site rather than at the shared helper every consumer already uses. | Recommended — fixes all paths at once. |
| 4 | `convex/fieldVisitOtp.test.ts` was not run; it covers exactly the half that is missing. | Add to the validation set. |
| 5 | The "pre-existing unrelated errors" claim is accurate, and now has a measurable bar (below). | Use the bar rather than eyeballing. |
| 6 | No mobile change is required — **confirmed against the app source**, not assumed. | Nothing to do. |

---

## 1. The change is not in the repository

Fetched every remote and searched all refs. On the current tip of `development-testing`
(and every other branch):

* `loadJointCpWorkflowContext` (`convex/marketing/clientPlaceVisits.ts:612-642`) still
  resolves roles with the unmodified fallback —

  ```ts
  const ownerLeg =
    legs.find((leg) => jointCpWorkflowRoleForLeg(leg) === "outcome_owner") ??
    legs.find((leg) => leg.isPrimary) ??      // ← legacy rows land here
    legs[0];
  ```

* `convex/jointCpVisits.test.ts` contains no legacy / inverted fixture (no leg without
  `workflowRole`, no senior-`isPrimary` case).
* The only commit that has ever touched `resolveJointCpAuthorityByLevel` in
  `clientPlaceVisits.ts` is `2162ec17` ("joint cp sv endpoint for mobile app",
  2026-09-08), which introduced its use at **creation** time (line 537) — not the
  read-time normalization this document describes.

So the work appears to be uncommitted locally. Nothing below assumes otherwise;
please confirm before treating it as delivered.

---

## 2. BLOCKER — the arrival-OTP gate still inverts legacy rows

`convex/hr/fieldVisitOtp.ts:277-288` derives the joint role **independently**, with
the identical vulnerable expression:

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
      (fieldVisitLeg.isPrimary ? "outcome_owner" : "reviewer");   // ← same bug
  }
}
```

Two gates consume it:

| Gate | Line | Error returned |
|---|---|---|
| request arrival OTP | `fieldVisitOtp.ts:598-607` | `OTP_OWNER_ONLY: Only the Joint CP outcome owner can request arrival OTP.` |
| verify arrival OTP | `fieldVisitOtp.ts:1056-1065` | `OTP_OWNER_ONLY: Only the Joint CP outcome owner can verify arrival OTP.` |

`convex/hr/fieldVisitOtp.ts` is **not** in the Files Changed list.

### Why this defeats the fix

On a legacy row the lower-level staff's leg has `workflowRole == null` and
`isPrimary == false`, so it resolves to `reviewer` and both OTP gates reject them.

The CP sequence is **arrival OTP → mark client met → outcome**. The described change
unblocks steps 2 and 3 while step 1 stays closed, so the affected staff member:

* still cannot request or verify the arrival OTP,
* never reaches the outcome form the fix repairs,
* now sees `OTP_OWNER_ONLY` where they previously saw `REVIEWER_ONLY`.

From the field this reads as "the bug is not fixed, only the message changed."

### Acceptance test for this half

A legacy-shape row (no `workflowRole` on either leg, senior `isPrimary`), with the
**lower-level** staff acting:

1. `requestArrivalOtp` → must succeed, **not** `OTP_OWNER_ONLY`.
2. `verifyArrivalOtp` → must succeed.
3. The **senior** staff calling either → must still be refused.

---

## 3. Recommended: normalize at the shared helper, not at one call site

All three read paths funnel through one expression:

| Location | Expression | Effect on a legacy row |
|---|---|---|
| `convex/marketing/lib/jointCp.ts:56` | `leg.workflowRole ?? (leg.isPrimary ? "outcome_owner" : "reviewer")` | **inverts** |
| `convex/hr/fieldVisitOtp.ts:285` | same expression, inlined | **inverts** |
| `convex/hr/fieldVisits.ts:454` | `jointLeg?.workflowRole ?? null` | reports **no role** |

Normalizing inside `loadJointCpWorkflowContext` fixes one of the three. Normalizing
where the legs are produced fixes all of them, and removes the possibility that a
fourth consumer added later re-introduces the same inversion.

Concretely: resolve once in `loadJointLegs` (or in `jointCpWorkflowRoleForLeg`'s
caller chain), using the helper already imported at `clientPlaceVisits.ts:29`:

```ts
// convex/marketing/lib/jointCpAuthority.ts:76
resolveJointCpAuthorityByLevel(ctx, staffIds)
  → { ownerStaffId, reviewerStaffId, participants }
```

Apply it **only when a leg's `workflowRole` is missing**, so rows that already carry
an explicit role keep it and nothing silently re-labels a correctly-stamped visit.
Keep the read-only behaviour the document describes — no backfill on read, admin
repair tool stays the permanent path. That part is right.

### One caution

`resolveJointCpAuthorityByLevel` **throws** `INVALID_JOINT_CP_ROLE_PAIR` when it is
not given exactly two staff (`jointCpAuthority.ts:88-92`), and it reads each staff
row. On a read path that runs for every workflow load, that means:

* a malformed legacy row (one leg, or a deleted staff) would start throwing where it
  previously degraded to `isPrimary` — wrap it and fall back rather than letting a
  data problem become a hard failure on an otherwise working screen;
* it adds per-leg `ctx.db.get` reads to a hot query — fine for a two-participant
  visit, worth noting if this helper is ever called in a list loop.

---

## 4. Validation

### Test suites

`pnpm vitest run convex/jointCpVisits.test.ts` alone does not cover the OTP gate.
Add:

```bash
pnpm vitest run convex/fieldVisitOtp.test.ts
pnpm vitest run convex/jointCpVisits.test.ts
```

`convex/fieldVisitOtp.test.ts` already exercises the OTP owner rules, so it is both
the regression net for §2 and the place the new legacy case belongs.

### The tsc baseline — measured, so it is not a judgement call

Ran on the clean checkout (`development-testing`, no local changes):

```
pnpm exec tsc --noEmit -p convex/tsconfig.json
→ 27 "error TS" lines
→ 0 of them in convex/marketing/clientPlaceVisits.ts
→ 0 of them in convex/jointCpVisits.test.ts
```

The claim that the remaining errors are pre-existing and unrelated is correct — they
are concentrated in `convex/telecallerMissions.ts`, plus a missing `web-push` module.

**So the bar is exact:** after the change, the total must still be **27**, and both
changed files must still contribute **0**. Any error appearing in those two files is
new, not noise. Please check it that way rather than by scanning output.

---

## 5. Mobile — nothing to do, and here is why

No app change is required. That is verified in the app source, not assumed from the
"no contract change" note:

Android resolves the Joint CP workflow in
`app/src/main/java/com/manjugroups/m_connect/ui/home/CpCompletionContract.kt:123-140`
(`resolvedJointCpWorkflowForActor`). It takes the server's explicit
`outcomeOwnerStaffId` / `reviewerStaffId` **first**, and only falls back to the
participant's `workflowRole` / `isPrimary` when the server omits them:

```kotlin
val owner = explicitOwner
    ?: participants.firstOrNull { it.workflowRole.equals("outcome_owner", true) }?.staffId…
    ?: joint?.leadStaffId…
    ?: participants.firstOrNull { it.isPrimary }?.staffId…
```

So the moment the workflow query reports the corrected owner, both apps follow with
no release. iOS mirrors the same precedence.

This is exactly what regression test 4 in the document ("workflow query reports the
lower-level staff as owner") pins down — that single assertion is what protects the
mobile clients, so it is worth keeping even if the rest of the suite changes.

Two consequences worth stating explicitly:

* If the workflow query is fixed but the OTP gate is not (§2), the apps will show the
  lower-level staff as the owner and offer them the OTP screen — which will then be
  refused by the server. That is a worse user experience than today, where the UI and
  the server at least agree.
* Both halves should therefore ship **together**, not sequentially.

---

## 6. What is right in the current design

Recorded so it is not lost in the rework:

* Read-time resolution with **no mutation on normal reads** — correct. Repair belongs
  in the admin tool, and a read path that rewrites rows would make the failure mode
  much harder to reason about.
* Reusing `resolveJointCpAuthorityByLevel` rather than a second seniority
  implementation — correct, and it keeps creation and repair consistent with reads.
* Logging mismatches for later backfill — correct, and it gives the repair tool a
  work list instead of a full-table scan.
* Leaving new Joint CP creation untouched — correct; that path already stamps
  `workflowRole` and is not implicated.
* No HTTP contract change — correct, and it is what keeps already-installed phones
  working without a release.

---

## 7. Checklist before merge

- [ ] Change is committed and pushed (§1).
- [ ] `convex/hr/fieldVisitOtp.ts` role derivation normalized the same way (§2).
- [ ] Legacy-row regression: lower-level staff can **request and verify** the arrival OTP.
- [ ] Legacy-row regression: senior staff is still refused on both OTP gates.
- [ ] Normalization applied where legs are produced, so no consumer is left behind (§3).
- [ ] Malformed legacy rows degrade instead of throwing `INVALID_JOINT_CP_ROLE_PAIR` on a read.
- [ ] `convex/fieldVisitOtp.test.ts` and `convex/jointCpVisits.test.ts` both pass.
- [ ] tsc: still 27 errors total, still 0 in the changed files (§4).
- [ ] Workflow-query owner assertion retained — it is the mobile contract (§5).
