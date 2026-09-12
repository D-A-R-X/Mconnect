# Joint CP outcome-owner role inversion — backend fix handoff

**Status:** Investigated (read-only) | **Owner:** Web/Convex admin | **Android:** no source change required

## 1. Problem statement

On the MConnect Android app, the **lower-designation participant** — the staff who,
per product rules, must enter the arrival OTP and submit the Joint CP outcome —
intermittently receives the server error:

> "Reviewer can edit the Joint CP outcome only after it is submitted"
> (`REVIEWER_ONLY`, `convex/marketing/clientPlaceVisits.ts:1299-1304`)

The same staff is then blocked from recording/submitting the outcome even though
they are the intended `outcome_owner`.

## 2. Root cause

Roles are decided **server-side**. HTTP routes pass `actingStaffId = auth.user._id`
(`convex/http.ts:18716` for `setOutcome`, `:18646` for `markClientMet`, `:18791`
for `joint-workflow`), so the value the app sends is ignored.

The server derives a participant's role from their leg row:

```ts
// convex/marketing/lib/jointCp.ts:53-57
export function jointCpWorkflowRoleForLeg(leg) {
  return leg.workflowRole ?? (leg.isPrimary ? "outcome_owner" : "reviewer");
}
```

So the actor role is:
1. the leg's stored `workflowRole`, if present, otherwise
2. `isPrimary` fallback.

**The breakage is legacy row data.** Commit `840299a3`
("feat(iam): add template-level hierarchy and joint CP workflow roles",
2026-09-04) introduced numeric-level authority resolution:
`owner = lower template/designation level` (`convex/marketing/lib/jointCpAuthority.ts:145-147`)
and began snapshotting `workflowRole` onto each `clientPlaceVisitParticipants` leg
at creation (`jointCp.ts:130-144`).

Visits created **before that commit** have:
- **no** `workflowRole` on either leg, and
- `isPrimary` still marking the **senior** participant, because the older create
  path wrote the SENIOR into `assignedStaffId`
  (see the removed comment in `c61f7b7a`: *"the SENIOR of the two owns the arrival
  OTP"*).

Today's server then resolves those legacy legs via the `isPrimary` fallback:
- senior leg → `workflowRole = "outcome_owner"`
- **lower leg → `workflowRole = "reviewer"`**

When the lower staff submits, `saveJointCpOutcomeDraft` sees
`actorRole === "reviewer"` with `status !== "pending_review"` and throws
`REVIEWER_ONLY` — exactly the reported symptom. The visit's intended owner is
locked out of their own outcome until the senior submits first.

### Why new visits are fine
`createCpVisitRows` now resolves the pair by numeric level, rewrites
`args.assignedStaffId = resolvedPair.ownerStaffId` (`clientPlaceVisits.ts:6631`),
and `createJointLegs` snapshots correct `workflowRole` + `isPrimary`
(`clientPlaceVisits.ts:6797-6804`). New rows resolve lower→owner without issue.

## 3. Affected scope

- `clientPlaceVisits` with `cpType === "joint_cp"` created **before deployment of
  `840299a3`** (2026-09-04), i.e. legs with `workflowRole == null` and
  `isPrimary` on the senior (higher numeric level) participant.
- Any joint CP row where stored leg `workflowRole` contradicts the
  current numeric IAM/designation hierarchy.

Note: newer role-enforcement fixes on `origin/max` (`c61f7b7a`, `ede357a7`,
both 2026-09-09) are **NOT** on the deployed `development-testing` HEAD. Do not
assume they cover this; verify merge lineage before relying on them.

## 4. Fix plan (backend / Convex)

### Step A — Detection (preview before any mutation)
Use the **existing** admin repair tool — it already computes the authoritative
owner/reviewer from numeric levels and flags mismatches
(`adminCpVisitRepair.ts:1098` `jointCpRoleRepairPreview`,
`:1017` `applyJointCpRoleRepairCandidate`):

1. In the Convex dashboard, run `marketing` → `adminCpVisitRepair.jointCpRoleRepairPreview`
   with a `fromDate`/`toDate` covering the suspicious window (or explicit `cpVisitIds`).
2. Confirm candidates classify as `role_mapping_mismatch` /
   `joint_cp_role_mapping_does_not_match_numeric_levels` and that the repaired
   `assignedStaffId` becomes the **lower-level** staff.
3. Review the preview `before`/`after` and `_participantPatches` carefully.

### Step B — Apply (admin-supervised, dry-run style)
Run `jointCpRoleRepairApply` only after preview review. It patches:
- visit `assignedStaffId` → lower-level owner,
- each leg: `workflowRole`, `isPrimary`, `routeColor`, `templateId`, `templateName`,
  `templateLevel` (and `fieldVisitId` when the owner's leg changed),
- writes an audit record (`joint-cp-role-inversion-repaired`).

Operational guardrails to enforce:
- Only touch `joint_cp` rows.
- Skip terminal (`completed`/`cancelled`) rows unless explicitly intended.
- One batch at a time; verify counts before/after.

### Step C — Code hardening (prevent recurrence / self-heal reads)
Optional but recommended so stale stored roles can never lock an owner again.

1. In `loadJointCpWorkflowContext` (`clientPlaceVisits.ts:612-643`), when a leg's
   `workflowRole` is **missing** (null), resolve the pair authoritatively against
   current numeric levels instead of trusting `isPrimary`:
   - reuse `resolveJointCpAuthorityByLevel` (`jointCpAuthority.ts:76-166`);
   - map `ownerStaffId`/`reviewerStaffId` back to legs for `actorRole`.
2. As a safety net, treat "stored roles contradict numeric hierarchy" as a
   detected inconsistency: log + return `actorRole` from the authoritative
   resolution, and surface the row in the existing repair preview so it can be
   backfilled.
3. Do **not** change HTTP contracts or the app's request shape.

### Step D — Backfill for legacy rows (if Step A/B is deferred)
If you prefer a migration over the admin tool, keep it idempotent and audited:
- iterate affected `joint_cp` rows older than 2026-09-04,
- for each, run the same authority resolution and stamp `workflowRole` + `isPrimary`
  on legs and fix `assignedStaffId`,
- write the identical audit record, and guard against touching already-consistent rows.

## 5. Tests to add (Convex unit suite)

In `convex/jointCpVisits.test.ts` add regressions that reproduce **legacy-shape
rows** (legs created old-style: no `workflowRole`, senior `isPrimary`):

1. Legacy-shape legs + a call to `setOutcome` by the **lower** staff → must NOT
   throw `REVIEWER_ONLY`; draft saves with `actorRole === "outcome_owner"`.
2. `markClientMet` by the lower staff on the same legacy row → succeeds,
   `clientMet` saved on the draft.
3. `jointCpRoleRepairPreview` flags such a row as `role_mapping_mismatch`, and
   `jointCpRoleRepairApply` lands owner on the lower level and reverses
   `isPrimary`/`workflowRole`.
4. The workflow query returns lower-level → `outcomeOwnerStaffId` for that row.
5. Newly created joint CP rows still resolve correctly (guard against regression).

## 6. Validation

- Backend: run the Convex test suite (including `jointCpVisits.test.ts`) and a
  preview-then-apply of the repair tool on a staging/dry-run copy first.
- Mobile: `./gradlew :app:assembleDebug` (no Android change expected);
  confirm the affected staff can now see OTP + outcome controls and submit.

## 7. Acceptance criteria

- [ ] Lower-level participant on previously affected visits can enter OTP
  and submit the outcome (no `REVIEWER_ONLY`).
- [ ] Senior-level participant still sees the outcome and completes with remarks.
- [ ] Repair preview shows the correct owner (lower level) before apply.
- [ ] Audit records exist for every repaired visit.
- [ ] No HTTP/API contract change; Android/iOS request shapes untouched.
- [ ] Regression tests above pass.