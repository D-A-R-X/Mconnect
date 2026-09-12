# Joint CP — legacy arrival-OTP fix (the missing half)

**For:** backend / Convex admin
**From:** mobile — spec only, no web/Convex file edited
**Date:** 2026-09-12
**Companion to:** `JOINT_CP_ROLE_INVERSION_REVIEW.md` §2

---

## Why this is needed

The pending runtime fix (`JOINT_CP_ROLE_INVERSION_RUNTIME_FIX.md`) normalizes roles in
`loadJointCpWorkflowContext`, which repairs the **outcome** step for legacy Joint CPs.

It does not repair the **arrival OTP** step, which happens FIRST. Until both are fixed,
the affected staff member is stopped before they ever reach the outcome form — they just
see `OTP_OWNER_ONLY` where they used to see `REVIEWER_ONLY`, and will report the bug as
still open.

**Ship this together with the outcome fix, not after it.** Shipping only the outcome half
makes things worse: the workflow query starts reporting the junior as owner, so both apps
show them the OTP screen, which the server then refuses.

## Scope

Joint CP rows created before `840299a3` (2026-09-04, "feat(iam): add template-level
hierarchy and joint CP workflow roles"). Their participant legs have:

* `workflowRole` — **absent** on both legs, and
* `isPrimary` — on the **senior** participant, the reverse of today's rule.

Rows created after that commit snapshot `workflowRole` at creation
(`convex/marketing/lib/jointCp.ts:137`) and are unaffected.

---

## The defect

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

Consumed by two gates, both of which reject the real outcome owner on a legacy row:

| Gate | Line | Message |
|---|---|---|
| request arrival OTP | `:598-607` | `OTP_OWNER_ONLY: Only the Joint CP outcome owner can request arrival OTP.` |
| verify arrival OTP | `:1056-1065` | `OTP_OWNER_ONLY: Only the Joint CP outcome owner can verify arrival OTP.` |

---

## The patch

### 1. Import

```ts
import { resolveJointCpAuthorityByLevel } from "../marketing/lib/jointCpAuthority";
```

### 2. Helper — add next to the other internal helpers

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
 * problem must degrade, not become a hard failure. Those rows stay inverted
 * and belong to the admin repair tool — see "Rows this cannot fix" below.
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

### 3. Replace the derivation in `_readVisitForOtp`

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

---

## Must match the other half

Both halves have to reach the **same** answer for the same row, or the OTP owner and the
outcome owner disagree and the visit deadlocks: one person can verify arrival, a different
person can file the outcome, and neither can finish alone.

So apply the identical rule in `loadJointCpWorkflowContext`
(`convex/marketing/clientPlaceVisits.ts:612`), specifically:

* normalize **only when `workflowRole` is absent**,
* use `resolveJointCpAuthorityByLevel` with `requireEligibleStaff: false`,
* swallow the throw and fall back to `isPrimary`.

Best of all: extract the helper into `convex/marketing/lib/jointCp.ts` next to
`jointCpWorkflowRoleForLeg` and have both files call it, so a future third caller cannot
reintroduce the split.

---

## Rows this cannot fix

`resolveJointCpAuthorityByLevel` throws, and the helper returns null, when:

* a participant's staff row is missing or `status !== "active"`,
* a participant has no numeric designation/IAM level,
* the two participants now sit at the **same** level, or on the same IAM template
  (e.g. one of them was promoted since the visit was created).

Those rows keep today's inverted behaviour. They are genuinely ambiguous — the system has
no basis to pick an owner — and they need the existing admin repair tool with a human
deciding. Worth logging them (same mechanism the runtime fix already uses) so the repair
tool gets a work list rather than a full-table scan.

---

## Tests to add — `convex/fieldVisitOtp.test.ts`

That suite currently has **14 tests and no Joint CP legacy coverage at all**, which is why
this gap was invisible. Add a legacy fixture: two participants at different numeric levels,
both legs with `workflowRole: undefined`, `isPrimary: true` on the **senior**.

1. The **lower-level** staff calls `requestArrivalOtp` → succeeds, no `OTP_OWNER_ONLY`.
2. The **lower-level** staff calls `verifyArrivalOtp` → succeeds.
3. The **senior** staff calls either → still refused with `OTP_OWNER_ONLY`.
4. A row **with** `workflowRole` stamped is untouched — the stamped role wins even when it
   disagrees with current levels (guards against re-labelling correct visits).
5. A row whose participants now share a level → helper returns null, no throw, the read
   still returns a role rather than erroring.
6. End-to-end on one legacy row: same staff member passes the OTP gate **and** the outcome
   gate — this is the test that proves the two halves agree.

Run both suites together:

```bash
pnpm vitest run convex/fieldVisitOtp.test.ts convex/jointCpVisits.test.ts
```

Current baseline on a clean checkout: `fieldVisitOtp.test.ts` 14 passed,
`jointCpVisits.test.ts` 31 passed.

---

## Mobile

No app change. Both apps take the server's `outcomeOwnerStaffId` first and only fall back
to leg flags when the server omits it (`ui/home/CpCompletionContract.kt:123-140`, and the
iOS equivalent), so correcting the server propagates without a release.

The apps do not second-guess `OTP_OWNER_ONLY` either — it is surfaced verbatim from
`convex/http.ts:278-283`, so the moment the gate stops firing the OTP screen works.

---

## Checklist

- [ ] `legacyJointRoleForStaff` added, used in `_readVisitForOtp`.
- [ ] Same rule in `loadJointCpWorkflowContext` — ideally one shared helper.
- [ ] Stamped `workflowRole` still wins everywhere.
- [ ] Unresolvable rows degrade (no throw on a read path) and are logged for repair.
- [ ] Six tests above added; both suites green.
- [ ] tsc still 27 errors total, 0 in the changed files.
- [ ] Both halves released together.
