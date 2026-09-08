# Joint CP Role Inversion Handoff Verification Gaps

## Verified mobile behavior

Android and iOS now both:

- reject the same staff or equal numeric IAM levels for Joint CP creation;
- order `jointStaffIds` as lower-level outcome owner, then higher-level reviewer;
- send the lower-level outcome owner as compatibility `assignedStaffId`;
- allow OTP/photo/outcome only for the bearer whose staff ID matches
  `outcomeOwnerStaffId`;
- allow remarks/review/final completion only for the bearer whose staff ID
  matches `reviewerStaffId`;
- disable all Joint CP mutations if `actorRole` contradicts those explicit
  owner/reviewer IDs.

## Verified production route surface

The safe unauthenticated contract probe returned structured HTTP 401 for:

```text
POST /api/marketing/clientPlaceVisits/create
GET  /api/marketing/clientPlaceVisits/joint-workflow
POST /api/marketing/clientPlaceVisits/joint-arrival-preflight
POST /api/marketing/clientPlaceVisits/joint-participant-ready
POST /api/marketing/clientPlaceVisits/joint-submit-review
POST /api/marketing/clientPlaceVisits/joint-complete-review
```

This proves the routes are deployed and protected. It does not prove the
authenticated BDO/Senior Manager success flow or historical repair results.

## Backend checkout mismatch

The available local backend checkout does not contain the handoff's named:

```text
convex/marketing/lib/jointCpAuthority.ts
convex/adminCpVisitRepair.ts
```

It also does not contain the `joint-participant-ready` route source even though
that route is reachable in production. The local checkout therefore does not
match the deployed/admin implementation described by the handoff.

The available older local authority implementation and tests still pass 21/21,
including lower-level `assignedStaffId`, equal-level rejection, and missing-
level rejection. That result cannot certify the separate repair module named in
the handoff.

## Required verification before repair

1. Sync or provide the exact backend commit deployed to production.
2. Confirm the two named repair functions exist in that revision.
3. Run `jointCpRoleRepairPreview` with a Super Admin token and inspect every
   proposed owner/reviewer change.
4. Apply only the returned preview token to the approved IDs.
5. Run preview again and require zero repairable rows.
6. Test one disposable Joint CP in both picker orders using separate lower- and
   higher-level accounts.
7. Confirm the lower account receives OTP/outcome only, the higher account
   receives review/remarks only, and both receive one completion credit.

No additional mobile endpoint is requested by this gap report.
