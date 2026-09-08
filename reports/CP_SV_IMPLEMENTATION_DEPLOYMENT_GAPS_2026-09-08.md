# CP/SV Implementation Deployment Gaps

Date: 2026-09-08

## Result

The supplied `cp-sv-missing-backend-endpoints-implementation-2026-09-08.md`
describes a valid target implementation, but the Android production hosts do
not expose that implementation yet.

The safe unauthenticated contract audit was run against:

- MMS API: `https://api-mfpl.theairix.com/`
- Storage API: `https://mg.theairix.com/`

Result: **104 of 112 contracts passed**.

An authenticated follow-up used a valid staff bearer (session validation
returned HTTP `200`). Both `completed-count` and `completion-health` still
returned `404`, confirming the routes are absent rather than merely hidden by
an unauthenticated probe. No bearer value is stored in this report.

## Still Missing In Production

| Route | Expected | Observed |
|---|---:|---:|
| `GET /api/marketing/clientPlaceVisits/completed-count` | Protected structured `401` | `404` |
| `GET /api/marketing/clientPlaceVisits/completion-health` | Protected structured `401` | `404` |
| `POST /api/marketing/clientPlaceVisits/completion-repair/preview` | Protected structured `401` | `404` |
| `POST /api/marketing/clientPlaceVisits/completion-repair/apply` | Protected structured `401` | `404` |
| `POST /api/marketing/clientPlaceVisits/joint-participant-ready` | Protected structured `401` | `404` |
| `GET /api/storage/files/{storageId}` | Protected read route | `404` |

## Existing Routes Still Behaving Differently

| Route | Required behavior | Observed |
|---|---|---|
| `GET /api/mobile/dashboard` | Authenticate first and return structured `401` | Unauthenticated `200` |
| `GET /api/bookings/{id}` | Authenticate before ID validation | Unauthenticated malformed ID returns `500` |

## Required Backend Action

Deploy the implementation described in the supplied handoff to the production
hosts used above. Do not run completion repair until the deployment is visible
and a protected preview succeeds.

After deployment, run:

```powershell
node scripts/check-mobile-api.mjs cp-sv-contracts --timeout 60000
```

The safe gate must report `112/112`. Then use admin authentication to preview
the historical CP repair, review every blocked/repairable row, and apply only
the approved CP IDs. The mobile app must never invoke the admin repair routes.

## Mobile Compatibility Added

Android and iOS now:

- prefers server `effectiveStatus` while retaining old-response fallback;
- accepts participant readiness coordinates and field-visit IDs;
- accepts reviewer remarks and both top-level/workflow `creditedStaffIds`;
- validates an explicitly returned Joint CP credit list contains both roles;
- models the participant-aware completed-count response;
- does not call admin health or repair endpoints from staff workflows.

Both clients distinguish an omitted legacy `actorReady` from an explicit
`false`. Existing deployments can continue their established reviewer flow;
after the new backend is deployed, an explicit `false` requires the higher-level
reviewer to complete the no-OTP proximity swipe. iOS now calls the new
participant-ready route for that swipe and refreshes the latest workflow
revision immediately before final review completion.

## Mobile Verification

- Android clean compilation passed.
- Android full unit suite passed: `221` tests, `0` failures, `0` errors,
  `0` skipped.
- `SessionInvalidationPolicyTest` passed: `10/10`.
- Android debug APK assembly and emulator installation passed.
- A cold launch on `emulator-5554` reached `LoginActivity`, retained a live app
  process, and produced no crash-buffer entry. The connected OPPO was not
  modified.
- Android and iOS scoped diff checks passed apart from Windows line-ending
  notices.
- Native iOS compilation was not run because Swift/Xcode is unavailable on this
  Windows host; the iOS patch still requires a Mac build before release.

No production write or historical repair was performed during this check.
