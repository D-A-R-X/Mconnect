# GeoTrack + Joint CP: backend fixes still needed

**For:** web/Convex admin and geo-service admin
**From:** mobile. No web, Convex or Go source has been edited by mobile.
**Date:** 2026-09-17
**Replaces:**
- `BACKEND_HANDOFF_2026-09-15.md`
- `MMS_WEB_BACKEND_ENDPOINTS_NEEDED.md`
- `GEOTRACK_LIVE_STILL_BROKEN_2026-09-15.md`
- `ENDPOINT_CHECK_JOINT_CP_GEOTRACK_2026-09-17.md`

**Scope:** GeoTrack and Joint CP only. SV-cum-CP, travel allowance and Google keys are out of scope here.

Every status below was **probed live today** on prod and dev, and checked against:
- Convex `origin/development-testing` @ `f35f6f69`
- geo service `origin/main` @ `20778a9`

---

## Status board

| # | Fix | Owner | Prod | Dev | Priority |
|---|---|---|---|---|---|
| **B1** | Stop proxying 6 Convex routes to geo routes that don't exist | Convex | ? | on dev branch | **P0: breaks mobile CP/SV lists if deployed** |
| **A1** | Deploy geo `main`, which has `session-route` | Geo | 404 | 404 | **P0** |
| **C1** | Joint CP submit-review rejects old app builds | Convex | failing in field | same code | **P0** |
| **C2** | Register the CP arrival-OTP reveal routes | Convex | 404 | 404 | P1 |
| B2 | Proxy must fall back to Convex on a geo 404 / non-JSON | Convex | - | missing | P1 |
| A2 | Geo sends timestamps as epoch ms, adds `_id` / `sessionState` | Geo | not done | not done | P1 (iOS) |
| A3 | Turn geo authentication back on | Geo | **open** | - | P1 (security) |
| C3 | Legacy Joint CP roles (OTP + outcome) | Convex | verify | **done in code** | verify |
| C4 | Push to reviewer when outcome is sent | Convex | verify | **done in code** | verify |

---

# A. Geo tracking service (`api-geo`, `dev-api-geo`)

## A1. Deploy the merged build (fixes GeoTrack Live and the attendance route map)

`GET /api/geotrack/session-route` is merged on `main` (`internal/httpapi/server.go:664`) but **neither environment runs it**:

```
GET https://api-geo.theairix.com/api/geotrack/session-route?staffId=x&dayStart=…&dayEnd=…
  → 404 text/plain "404 page not found"
GET https://dev-api-geo.theairix.com/api/geotrack/session-route?staffId=x&dayStart=1&dayEnd=2
  → 404 text/plain "404 page not found"
```

The plain-text 404 proves the old binary is running. The new build answers unknown paths with a **JSON** 404.

**Mobile effect today:**
- Geo Track Live: pick a staff member → empty map, "Failed to load staff route".
- Attendance review map: no route; GPS points, trips and distance stay blank.

**Action:**
1. `make check` on `main`. It has never been run against `53b954f`.
2. Rebuild and redeploy both `dev-api-geo` and `api-geo`.
3. Confirm: the probe above returns **JSON** (200 or 400), not plain text.

## A2. Send the published wire format (iOS is still broken without it)

Live today, `/api/tracking/trips` sends:

```json
{ "tripId": "yh7d…", "startedAt": "2026-09-17T10:37:36.373Z", "endedAt": "2026-09-17T10:40:16.868Z" }
```

The mobile contract is **epoch milliseconds** and `_id`. Android already accepts both shapes. **iOS does not** and discards the whole response.

Fix it at the HTTP layer only, adding keys rather than renaming, so the web is untouched:

| Type | Change |
|---|---|
| every mobile-facing `time.Time` | `t.UnixMilli()`; `*time.Time` → `omitempty` int64 |
| `Trip` | add `_id` alongside `tripId` |
| `Session` | add `_id` alongside `sessionId`, and `sessionState` alongside `state` |
| `LocationPoint.recordedAt`, `TripStop.arrivedAt` / `departedAt` | epoch ms |

Routes affected: `tracking/trips`, `tracking/sessions/current`, `geotrack/timeline`, `geotrack/session-route`.

> Convex already converts these for the **web** (`geoDateFieldsToMillis` in `http.ts`). The apps call geo **directly**, so they never get that conversion.

## A3. Authentication is still off

`GET https://api-geo.theairix.com/api/tracking/live` with **no token** → 200 with live staff positions. `GEO_AUTH_DISABLED` is still set.

**Action:** turn it off once A1 is deployed and both apps are confirmed to send their bearer token. They already do.

---

# B. Convex GeoTrack proxy (`api-mfpl`, `dev-cvx-http-mg`)

## B1. CRITICAL: six routes proxied to geo handlers that don't exist

On `development-testing`, these Convex routes now call `geoServiceReadResponse(...)` instead of their Convex queries:

| Convex route | `http.ts` line | Exists in geo `main`? | Real data lives in |
|---|---|---|---|
| `GET /api/geotrack/assigned-places` | 18487 | **No** | Convex `clientPlaces` |
| `GET /api/geotrack/today-visits` | 18505 | **No** | Convex `fieldVisits` |
| `GET /api/geotrack/bootstrap` | 18270 | **No** | Convex `staff` + `projects` |
| `GET /api/geotrack/stats` | 18464 | **No** | Convex `geoTrips` |
| `GET /api/geotrack/employee-detail` | 18331 | **No** | Convex `staff` + location |
| `GET /api/tracking/bootstrap` | 17933 | **No** | Convex staff/settings |

Checked the geo service source: none of these six paths are registered on `main` or `kira`. Every one of them returns 404, and the proxy turns that into `{"success":false,"error":"Geo tracking service returned invalid JSON"}`.

**Deploying this branch as-is breaks:**
- **mobile CP and SV lists** (`assigned-places`, `today-visits`), the core field screens;
- the **web Geo Track Live** page, whose tiles read 0 and which shows "No staff found".

**Action:** restore the original Convex handler for all six. The geo service has no staff, projects, client places or field visits, so a geo deploy can never serve them.

| Route | Restore |
|---|---|
| `assigned-places` | `api.clientPlaces.listByStaff` |
| `today-visits` | `api.hr.fieldVisits.listTodayByStaff` |
| `geotrack/bootstrap` | `internal.geotrack.location.liveRoster` + `api.projects.liveTrackable` |
| `stats` | `api.geotrack.trips.stats` |
| `employee-detail` | `api.geotrack.location.employeeDetail` |
| `tracking/bootstrap` | its previous Convex handler |

Keep proxying routes the geo service **does** implement: `live-status`, `timeline`, `session-route` (after A1), `tracking/*` writes, `route`, `geocode-address`.

## B2. The proxy must not hard-fail on a missing upstream route

`geoServiceReadResponse` (`http.ts`) and `requestGeoTrackingService` (`convex/lib/geoTrackingService.ts:84-86`) turn an upstream 404 or a plain-text body into a user-facing error.

**Action:** when geo answers 404 or a non-JSON body, fall back to the Convex handler where one exists, or return a JSON 404. Never return "invalid JSON" to a user.

**Confirm after deploy:** web Geo Track Live shows non-zero Online / Offline / Geo-enabled tiles, and the mobile CP and SV lists load.

---

# C. Joint CP (Convex)

## C1. P0: submit-review rejects every Joint CP from older app builds

**Field symptom:** the junior enters OTP and outcome, and then:
- they see `ArgumentValidationError: Found ID "k97…"`;
- their card goes back to **Start**;
- the senior never sees the outcome.

**Cause:** these mutations validate `fieldVisitId` strictly:

```ts
// convex/marketing/clientPlaceVisits.ts
jointArrivalPreflight   (:10160)  fieldVisitId: v.optional(v.id("fieldVisits"))
jointParticipantReady   (:10222)  fieldVisitId: v.optional(v.id("fieldVisits"))
submitJointReview       (:10284)  fieldVisitId: v.optional(v.id("fieldVisits"))
```

When a staff member's leg has no field-visit id in the app yet, app builds up to today send the **CP id** in that field. Convex rejects the whole call before the handler runs. The value is only a cross-check: the handler already derives the real id with `ensureFieldVisitForJointLeg`.

**Mobile fixed it today** (the app omits the field), but every installed copy keeps failing until staff update.

**Action (all three mutations):**

```ts
fieldVisitId: v.optional(v.string()),
```

```ts
// Cross-check only. Old app builds send the CP id here; ignore anything that
// is not this participant's field visit rather than failing the request.
const claimed = args.fieldVisitId
  ? ctx.db.normalizeId("fieldVisits", args.fieldVisitId)
  : null;
if (claimed && String(claimed) !== String(ownerFieldVisitId)) {
  throwJointCpWorkflowError("OUTCOME_OWNER_ONLY", "Submit-review fieldVisitId must belong to the outcome owner");
}
```

Update the matching `http.ts` casts (`optionalString(body.fieldVisitId) as Id<"fieldVisits">`).

### C1b. Old builds also send a stale outcome revision

Saving the outcome creates a new draft revision (`saveJointCpOutcomeDraft`, revision + 1). Older apps submit the revision they held **before** saving, so after C1 they fail with `OUTCOME_REVISION_CONFLICT`.

Mobile fixed this today too (it re-reads the workflow first). For installed builds, accept the one known-safe case in `submitJointReview`:

```ts
const staleByOwnSave =
  draft.revision === args.expectedOutcomeRevision + 1 &&
  String(draft.editedByStaffId) === String(args.actorStaffId) &&
  !draft.submittedAt;
if (draft.revision !== args.expectedOutcomeRevision && !staleByOwnSave) {
  throwJointCpWorkflowError("OUTCOME_REVISION_CONFLICT", "Joint CP outcome changed. Reload and submit again");
}
```

The draft was written by the same owner and never submitted, so no one else's edit can be overwritten.

**Tests:**
1. `submitJointReview` with a CP id in `fieldVisitId` → succeeds.
2. With another participant's field-visit id → still `OUTCOME_OWNER_ONLY`.
3. Expected revision N, draft N+1 saved by the same owner → succeeds.
4. Draft N+1 saved by the **reviewer** → still `OUTCOME_REVISION_CONFLICT`.

## C2. Register the arrival-OTP reveal routes

```
POST https://api-mfpl.theairix.com/api/marketing/cp-visits/reveal-otp         → 404 "No matching routes found"
POST https://api-mfpl.theairix.com/api/marketing/cp-visits/reveal-otp/copied  → 404
POST https://dev-cvx-http-mg.theairix.com/api/marketing/cp-visits/reveal-otp  → 404
```

The mutations and the IAM key already exist (`marketing.cpVisits.revealOtp`, default for AVP, hierarchy-scoped and audited). Only the HTTP routes are missing:

```
POST /api/marketing/cp-visits/reveal-otp
  body { sourceId, sourceType: "client_place_visit" }
  → api.hr.fieldVisitOtp.revealActiveOtpForSuperAdmin { sourceType, sourceId, sessionToken }

POST /api/marketing/cp-visits/reveal-otp/copied
  body { fieldVisitId }
  → api.hr.fieldVisitOtp.recordOtpAssistCopied { fieldVisitId, sessionToken }
```

Authenticate the request and forward `sessionToken`. Add **no** permission logic here; the mutations already gate themselves.

**Confirm:** unauthenticated POST returns **401**, not 404. The apps show "Reveal OTP" to AVP/GM once it does.

## C3. Legacy Joint CP roles: done on `development-testing`, verify it ships

Rows created before `840299a3` (2026-09-04) put `isPrimary` on the senior and have no `workflowRole`.

`development-testing` now normalizes roles inside `loadJointLegs` (`convex/marketing/lib/jointCp.ts:67-124`, `requireEligibleStaff: false`, falls back without throwing). Both gates read through it:
- the **arrival OTP** gate (`_readVisitForOtp`, `fieldVisitOtp.ts:282-287`);
- the **outcome** gate.

**Action:** ship it. Confirm with a legacy row that:
1. the lower-level staff can request **and** verify the OTP;
2. the same staff can submit the outcome;
3. the senior can do neither.

Until it ships, affected staff see "Joint CP role assignment is out of sync".

## C4. Reviewer push: done on `development-testing`, verify it ships

`submitJointReview` calls `notifyJointCpReviewReady(...)` (`clientPlaceVisits.ts:10437`, helper `:1549`). It is not on `main`.

**Action:** ship it. Confirm the senior's phone gets a notification within seconds of the junior sending the outcome.

---

## Already fixed in the apps (no backend work)

| Issue | Fix |
|---|---|
| Card showed "Start Trip" after OTP + outcome | Status now "Pending Review" only after submit; trip screen has a Pending Review state |
| Card showed "Completed" before review | Pending Review until the reviewer completes |
| `fieldVisitId` sent as CP id | Omitted when not a real field-visit id |
| Stale outcome revision on submit | Re-reads the workflow before submitting |
| GeoTrack RFC 3339 / `tripId` / `state` | Android tolerant (iOS needs A2) |

---

## Verification (no login needed)

```bash
# A1: expect JSON, not "404 page not found"
curl -s "https://api-geo.theairix.com/api/geotrack/session-route?staffId=x&dayStart=1&dayEnd=2"

# A3: expect 401
curl -s -o /dev/null -w "%{http_code}\n" https://api-geo.theairix.com/api/tracking/live

# C2: expect 401, not 404
curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Content-Type: application/json" -d '{}' \
  https://api-mfpl.theairix.com/api/marketing/cp-visits/reveal-otp
```

B1, B2, C1, C3 and C4 need a logged-in check on the web and a phone.

---

## Checklist

**Geo service**
- [ ] A1: `make check` on `main`; deploy `dev-api-geo` and `api-geo`; `session-route` returns JSON.
- [ ] A2: mobile-facing timestamps in epoch ms; `_id` and `sessionState` added.
- [ ] A3: `GEO_AUTH_DISABLED` off; unauthenticated `tracking/live` → 401.

**Convex**
- [ ] B1: the six routes restored to their Convex handlers **before** `development-testing` is deployed.
- [ ] B2: proxy falls back on geo 404 / non-JSON.
- [ ] C1: `fieldVisitId` accepts any string in the 3 joint mutations; C1b stale-revision case; 4 tests.
- [ ] C2: `reveal-otp` + `reveal-otp/copied` registered.
- [ ] C3: legacy role normalization deployed; verified on a legacy row.
- [ ] C4: reviewer push deployed; verified on a phone.
- [ ] `pnpm exec tsc --noEmit -p convex/tsconfig.json`: 27 pre-existing errors, 0 new in changed files.
- [ ] `pnpm vitest run convex/fieldVisitOtp.test.ts convex/jointCpVisits.test.ts` green.
