# Play Store device-binding rollout verification

Date: 2026-09-08

## Verdict

The mobile client is ready for a staged rollout, and current production remains
compatible with APK users. The admin handoff is **not yet verified as deployed**
and must not be treated as final production state.

Do not run the fleet reset or globally require build 71 until a real
Play-installed pilot has passed. Existing APK users must remain on the current
compatibility behavior during the pilot.

## Verified mobile state

- Android application ID is `com.manjugroups.mconnect`.
- Android version code is 71.
- Employee-ID login, OTP request, and OTP verification send `appVersion` and
  `appBuild` along with the same `ANDROID_ID`-based login device identity.
- Normal logout clears session storage but does not clear the dedicated login
  device identity.
- Employee-ID login preserves structured backend errors in the minified release
  instead of collapsing them to `Unable to sign in`.
- Android unit tests and the minified release APK/AAB build passed before this
  backend handoff review.

## Production endpoint results

Safe probes used empty credentials or an invalid bearer token. No OTP, session,
binding, reset, upload, or business-data mutation was created.

| Contract | Result |
| --- | --- |
| CP/Joint CP protected routes | PASS: 9/9 returned structured HTTP 401 |
| Device login validation, build 71 | PASS: 3/3 returned structured HTTP 400 validation |
| Device recovery validation | PASS: 3/3 returned structured HTTP 400 validation |
| App version, Android build 70 | LIVE VALUE: minimum build 43, update not required |
| App version, iOS build 70 | LIVE VALUE: minimum build 0, update not required, update URL missing |
| Auth build gate, Android build 42 | NOT DEPLOYED: 3/3 returned field validation, not HTTP 426 |
| Compatibility rollout, Android | PASS: all 12 safe probes |
| Compatibility rollout, iOS | PASS: all 12 safe probes |
| Direct storage create/complete/delete | PASS: structured HTTP 401 |
| Direct storage file read | FAIL: `/api/storage/files/contract-probe` returned HTTP 404 instead of authenticated-route HTTP 401 |

The minimum-build result means current APK users remain operational, which is
the required compatibility phase. It also proves the global build-71 gate from
the supplied rollout handoff is not active in production yet.

## Admin handoff discrepancies

The supplied `PLAYSTORE_DEVICE_BINDING_ROLLOUT.md` says production should use
minimum build 71 immediately and then reset every active staff binding. That
conflicts with the approved requirement to keep APK users working until the
Play build is verified.

The locally available backend source also does not yet match several claims in
that handoff:

1. Auth HTTP handlers do not currently parse or enforce `appVersion` and
   `appBuild`.
2. `resetActiveStaffDeviceBindingsBatch` has no actor, reason, deployment
   confirmation, or audit-row arguments.
3. The reset reads at most 10 binding rows per staff, so it does not prove that
   every duplicate is removed.
4. The staff security query still returns the full device ID instead of only a
   redacted suffix/hash prefix.
5. Production app-version settings still report Android minimum build 43.
6. Production app-version settings report iOS minimum build 0 and no update
   URL, not build 71 as claimed by the handoff.

Do not configure iOS minimum build 71 merely to mirror Android. It must match
the approved TestFlight `CFBundleVersion` and have a valid iOS update URL or
equivalent App Store/TestFlight delivery behavior.

The focused local backend auth/device-binding suite passes 30/30 tests, but
those tests cover the currently checked-out implementation, not the additional
rollout behavior claimed in the handoff.

## Required rollout decision

### Current phase: compatibility

- Keep global minimum build at 70 or lower.
- Do not run the active-staff fleet reset.
- Deploy only backward-compatible endpoint parsing.
- Preserve sessions and bindings for users on the existing APK.

### Pilot phase

- Select disposable or approved pilot staff accounts.
- Require build 71 only for those pilot accounts.
- Reset only those pilot bindings through the authorized per-staff reset.
- Install from the real Play track and verify first login, same-device relogin,
  app restart, API loading, and second-device rejection.

### Enforced phase

Only after the Play pilot passes:

- globally require Android build 71;
- dry-run and review every fleet-reset page;
- run confirmed reset pages to completion;
- verify no active-staff binding rows or stale mobile sessions remain;
- allow each corrected Play install to create exactly one new binding.

## Verification commands

Compatibility phase:

```powershell
node scripts/check-mobile-api.mjs contracts
node scripts/check-mobile-api.mjs storage-contracts
node scripts/check-mobile-api.mjs device-login-contracts
node scripts/check-mobile-api.mjs device-rollout-contracts --rollout-mode compatibility
node scripts/check-mobile-api.mjs auth-recovery-contracts
```

After the global build-71 gate is deliberately enabled:

```powershell
node scripts/check-mobile-api.mjs device-rollout-contracts --rollout-mode enforced
```

Do not approve the fleet reset based only on the handoff document. Approval
requires the enforced checker to pass and a successful real Play pilot.
