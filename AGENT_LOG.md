# AGENT_LOG.md

> **⚠️ LOCAL ONLY — DO NOT COMMIT OR PUSH**
> This file is listed in `.gitignore` and must stay that way.
> It is a running logbook for AI agents (Antigravity / Claude / Gemini, etc.)
> working on this repository so each session picks up exactly where the last left off.

---

## READ FIRST: Reuse the Mconnect UI System

Before creating or changing Android UI, search for and reuse the app's existing
components, XML styles, drawables, semantic theme attributes, and interaction
patterns. This applies to buttons, form inputs, dropdowns, bottom sheets,
dialogs, chips, selectors, date/time controls, empty/loading/error states, and
list rows. Extend a shared component or style when the same pattern is needed
in more than one place; do not create a visually similar one-off control.

- Use Material button and text-field styles already present in the app.
- Use semantic colors such as `?attr/colorAccentPrimary`,
  `?attr/colorForegroundPrimary`, `?attr/colorSuccess`, `?attr/colorWarning`,
  and `?attr/colorError`; do not introduce hardcoded UI colors when a semantic
  token exists.
- Use shared fonts, dimensions, backgrounds, icons, and reusable dialogs such
  as the searchable selection component before building a replacement.
- Keep XML + ViewBinding and the surrounding screen's established behavior.
- When adding a genuinely reusable pattern, name and document the shared
  component/style so later agents use it instead of duplicating it.

---

## READ FIRST: Three-Repository Project Map

This product is split across **three separate Git repositories**. They share
Fleet and Travel Desk workflows, but they are not interchangeable. Before
editing, confirm the target repository, read its `AGENTS.md`, inspect its dirty
worktree, and preserve changes made by other agents/users.

### 1. Mconnect - Android App

| Detail | Value |
|--------|-------|
| Local path | `C:\Users\surya\Projects\Mconnect` |
| GitHub | `https://github.com/manjugroupsdev/Mconnect.git` |
| Current working branch (2026-07-27) | `merge` |
| Additional remote | `darx` -> `https://github.com/D-A-R-X/Mconnect.git` |
| Technology | Kotlin Android, XML layouts, ViewBinding, Retrofit |
| Build | `.\gradlew.bat :app:assembleDebug` |

**Owns:**
- The Mconnect Android application used by internal staff.
- The Travel Desk mobile experience for external agency admins, agency staff,
  and external drivers.
- Mobile CP/SV trip cards, Fleet operations, driver trip lifecycle, external
  agency Drivers/Vehicles/Trips/Staff/Settings screens.

**Does not own:**
- Convex schema or server-side authorization.
- The standalone Travel Desk browser portal.
- The MMS web Fleet dashboard.

### 2. MMS / Manju Site Development - Web + Shared Backend

| Detail | Value |
|--------|-------|
| Local path | `C:\Users\surya\Projects\manjusitedevelopment` |
| GitHub | `https://github.com/manjugroupsdev/manjusitedevelopment.git` |
| Current working branch (2026-07-27) | `max` |
| Technology | Next.js, React, TypeScript, Convex |
| Build | `npm run build` |

**Owns:**
- The MMS website, including Marketing -> Fleet, CP, and SV web screens.
- The shared Convex schema, queries, mutations, actions, IAM permissions, and
  HTTP endpoints consumed by both web clients and Mconnect.
- Server-side source of truth for Travel Desk authentication, agency scoping,
  vehicles, drivers, staff, settings, trips, expiry, pricing, and approvals.

**Important locations:**
- `convex/schema.ts` - shared database schema.
- `convex/http.ts` - HTTP routes used by Travel Desk web and Mconnect.
- `convex/travelDesk*.ts` - external Travel Desk backend.
- `convex/marketing/fleet.ts` - internal MMS Fleet backend.
- `features/fleet/` - MMS Fleet web UI.
- `features/marketing/` - CP/SV web UI.
- `lib/iam-model.ts` and `lib/iam-client.ts` - IAM permission definitions.

**Deployment rule:**
- A successful Next.js build does **not** deploy Convex.
- Convex schema/function changes require an administrator to run the approved
  deployment process. Do not claim a backend feature is live until deployment
  is confirmed.

### 3. Travel Desk - Standalone Web Portal

| Detail | Value |
|--------|-------|
| Local path | `C:\Users\surya\Projects\travel-desk` |
| GitHub | `https://github.com/manjugroupsdev/travel-desk.git` |
| Current working branch (2026-07-27) | `aizen` |
| Technology | Next.js, React, TypeScript |
| Build | `npm run build` |

**Owns:**
- The standalone browser portal for external fleet agencies, agency staff, and
  external drivers.
- Travel Desk web pages for Drivers, Vehicles, Trips, Staff, and Settings.
- Next.js proxy routes that forward authenticated requests to the MMS/Convex
  `/api/travel-desk/*` contract.

**Does not own:**
- The shared database schema or authoritative business rules.
- MMS internal Fleet screens.
- Android screens.

### Source-of-Truth Matrix

| Concern | Repository that owns it |
|---------|-------------------------|
| Convex tables and validators | MMS (`manjusitedevelopment`) |
| Travel Desk HTTP API contract | MMS (`manjusitedevelopment`) |
| IAM / Transport Manager permissions | MMS (`manjusitedevelopment`) |
| Internal MMS Fleet web UI | MMS (`manjusitedevelopment`) |
| CP and SV web UI | MMS (`manjusitedevelopment`) |
| External agency browser UI | Travel Desk (`travel-desk`) |
| Internal staff Android UI | Mconnect |
| External agency/driver Android UI | Mconnect |
| Cross-client session persistence | Backend in MMS; client handling in both Travel Desk and Mconnect |

### Required Cross-Repository Workflow

For a feature that must work on Travel Desk web and app:

1. Define or verify the schema, authorization, mutation/query, and HTTP route
   in MMS first.
2. Update the standalone Travel Desk web types, proxy route, and UI.
3. Update Mconnect Retrofit models/endpoints, session capability, and Android
   UI.
4. Build all affected repositories.
5. Record whether Convex deployment is still pending.

For a Fleet-only MMS feature, do not edit Travel Desk or Mconnect unless the
requested behavior must also appear on those clients.

### Terminology

- **MMS / web** means `manjusitedevelopment`, unless the user explicitly says
  "Travel Desk web".
- **TD / Travel Desk web** means the standalone `travel-desk` repository.
- **App / Mconnect** means the Android `Mconnect` repository.
- **Fleet module** usually means Marketing -> Fleet inside MMS.
- **Travel Desk app feature** means the external fleet experience inside
  Mconnect, not a separate Android repository.

### Safety Rules for All AI Agents

- Never run destructive Git commands or reset unrelated dirty changes.
- Never assume all three repositories are on the same branch.
- Never copy business logic independently into both clients when it belongs in
  the MMS backend.
- Keep client expiry, role, pricing, and status displays aligned with the
  backend contract.
- Update this log after cross-repository work with files, validation results,
  deployment status, and remaining provider/admin dependencies.

---

## What Is This File?

`AGENT_LOG.md` is the shared local AI session log for the three connected
repositories: Mconnect Android, MMS web/shared Convex backend, and the
standalone Travel Desk web portal.

### Why It Was Made

Multiple AI coding sessions (via Google Antigravity IDE) work on this repo across days and context
windows. Without a shared memory, each new session has to rediscover architecture, decisions, and
in-progress work from scratch. This file solves that by acting as:

- A **decision log** — why was something done a certain way?
- A **change summary** — what files changed and what do they now do?
- A **handoff doc** — what is still open / what should the next agent do first?

### How to Use It (for AI agents reading this)

1. **Read this file at the start of every session** before touching any code.
2. **Update the "Current State" and latest session block** at the end of your session.
3. Never delete old session entries — append only.
4. Keep entries concise but specific (file names, function names, mutation names).
5. If you introduce a breaking change or a new architectural pattern, call it out clearly.

---

## Repository Map (Quick Reference)

| Path | What It Is |
|------|-----------|
| `app/src/main/java/com/manjugroups/m_connect/` | Kotlin Android app |
| `app/src/main/java/.../network/GeoTrackApi.kt` | All Retrofit API interfaces + data classes |
| `app/src/main/java/.../ui/home/HomeFragment.kt` | Main home screen, today's trips card logic |
| `app/src/main/java/.../ui/marketing/SiteVisitsFragment.kt` | Site Visit list screen |
| `app/src/main/java/.../ui/marketing/SiteVisitOverviewFragment.kt` | SV detail bottom sheet (stepper + outcome) |
| `app/src/main/java/.../ui/home/CompleteCpVisitBottomSheet.kt` | Shared outcome sheet (CP + SV modes) |
| `C:\Users\surya\Projects\manjusitedevelopment\convex\` | Convex backend (TypeScript) |
| `convex/schema.ts` | Database schema for all tables |
| `convex/marketing/fleet.ts` | Fleet assignment mutations & queries |
| `convex/marketing/siteVisits.ts` | Site visit CRUD, mobile query |
| `lib/iam-model.ts` | IAM permission taxonomy (server) |
| `lib/iam-client.ts` | IAM permission labels (client) |
| `convex/marketing/lib/access.ts` | Per-feature permission union types |
| `features/fleet/tabs/assigned-tab.tsx` | Fleet → Assigned tab UI (web) |
| `features/fleet/use-fleet-assigned-controller.ts` | Fleet Assigned tab controller logic |
| `features/fleet/types.ts` | Fleet TypeScript type definitions |
| `features/marketing/pages/site-visit-detail-page.tsx` | SV detail page (web) |

---

## Current State (as of last session)

**Date:** 2026-07-27
**Agent:** Codex (continuing Antigravity handoff)

### What Is Working
- Full booking form field wiring between web and mobile (API → app fields).
- Home address / phone number fetching correctly.
- Recomplete / Offline-Completed flow fully implemented (see Session 4 below).
- External Fleet Drivers, Vehicles, Trips, Staff, Settings, 48-hour expiry,
  and extra-km approval flows are implemented across all three repositories.
- CP/SV web forms share the unified address parser, pincode lookup, and map
  fields.

### Open Items / Next Steps
- Convex changes from Sessions 12-15 still require administrator deployment.
- Driver WhatsApp assignment and inbound dashboard-photo/start-km ingestion
  still require an approved template and provider webhook contract.
- QA test: mark an expired fleet SV as "Completed" from the web Fleet dashboard and verify
  the Site Incharge sees the outcome prompt on both web detail page and mobile home card.

---

## Session Log

---

### Session 1 — Git / Branch Analysis
**Date:** 2026-06-26
**Agent:** Antigravity (Gemini)

**What was done:**
- Analysed the Git repository branches and remote connections.
- Produced [git_repository_analysis.md](git_repository_analysis.md) artifact with branch map.

**Key findings:**
- Single Android module `app/`.
- Backend lives in a separate repo: `C:\Users\surya\Projects\manjusitedevelopment` (Next.js + Convex).
- No Navigation Component, no Hilt, no Compose — manual Fragment transactions only.

---

### Session 2 — Booking Form API Wiring
**Date:** 2026-07-25 (earlier)
**Agent:** Antigravity (Gemini)

**Goal:** Connect the web booking form fields to the mobile new-booking form so all fields
populate correctly. Remove mobile-only fields that don't exist on the web.

**What was done:**
- Mapped web form fields → mobile form fields via the API.
- Fixed home address and phone number not fetching correctly (wrong field names in the Kotlin
  data classes).
- Confirmed the 3 remaining tabs and all fields now bind to the correct API response keys.

**Files changed (mobile):**
- `GeoTrackApi.kt` — corrected field name mappings in data classes.
- Relevant fragment(s) for new-booking form.

---

### Session 3 — Home Address / Phone Number Fix
**Date:** 2026-07-25 (mid-session)
**Agent:** Antigravity (Gemini)

**Goal:** Home address and phone number fields were still not populating after Session 2.

**What was done:**
- Traced the fetch path from API response → ViewModel → Fragment binding.
- Identified the correct nested property path for home address and phone number.
- Fixed binding so both fields display correctly across all 4 tabs.

---

### Session 4 — Recomplete / Offline-Completed Fleet Flow

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  AGENT: Antigravity (Google DeepMind)
  SESSION START — 2026-07-25T05:00:00+05:30
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```
**Date:** 2026-07-25
**Agent:** Antigravity (Gemini → Claude Sonnet 4.6 Thinking)

**Goal:** Add a "Completed" action button on expired fleet cab allocations so the fleet admin
can signal that the visit happened outside the system (recomplete). The Site Incharge then
fills the outcome, and only then does the visit move to the Completed state.

**Architecture Decision:**
- New `completedOffline: Boolean` field on `siteVisits` table (Convex schema).
- While `completedOffline == true && outcome == null` → visit lives in the **Assigned** tab (not
  Completed) with "Expired" + "Outcome Pending" badges.
- Once outcome is recorded → visit moves to Completed, expired badge gone.
- Gated behind new IAM permission: `marketing.fleet.completeOffline`.

**Files changed — Convex backend:**

| File | Change |
|------|--------|
| `convex/schema.ts` | Added `completedOffline: v.optional(v.boolean())` to `siteVisits` |
| `convex/marketing/lib/access.ts` | Added `"marketing.fleet.completeOffline"` to `FleetPermission` union |
| `lib/iam-model.ts` | Registered permission in `PERMISSIONS`, `PERMISSION_GROUPS`, `IAM_TAXONOMY` |
| `lib/iam-client.ts` | Added client-side label for new permission |
| `convex/marketing/fleet.ts` | Added `markExpiredTripOutcomePending` mutation; updated `listAssigned` subtab filter |
| `convex/marketing/siteVisits.ts` | `listForViewerAsMobileVisits` now returns `completedOffline` + `outcome` |

**Files changed — Web UI:**

| File | Change |
|------|--------|
| `features/fleet/types.ts` | Extended `AssignedFleetVisit` with `completedOffline?`, `outcome?` |
| `features/fleet/use-fleet-assigned-controller.ts` | `canCompleteOffline`, `handleRecomplete`, `recompleteTarget` state |
| `features/fleet/tabs/assigned-tab.tsx` | "Completed" button, "Outcome Pending" badge, confirmation dialog |
| `features/marketing/pages/site-visit-detail-page.tsx` | Outcome recording panel with Postpone + Not Interested dialogs |

**Files changed — Mobile (Kotlin):**

| File | Change |
|------|--------|
| `network/GeoTrackApi.kt` | `completedOffline`+`outcome` added to `TodayVisit`; `completedOffline` added to `CpVisitDetail` |
| `ui/marketing/SiteVisitOverviewFragment.kt` | `isCompletedOfflinePending` flag; outcome buttons unlock without needing on-site step |
| `ui/marketing/SiteVisitsFragment.kt` | Amber "Outcome Pending" status pill |
| `ui/home/HomeFragment.kt` | "Outcome Pending" home card + "Record Outcome" CTA → opens `CompleteCpVisitBottomSheet.forSiteVisit()` |

**Build result:** `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** ✅

**Key mutation to know:**
```
api.marketing.fleet.markExpiredTripOutcomePending({ id: <siteVisitId>, ...auditMeta })
```
Sets `completedOffline = true` on the SV. The `setOutcome` mutation on `siteVisits` clears the
pending state once the incharge records the outcome.

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  AGENT: Antigravity (Google DeepMind)
  SESSION END — 2026-07-25T06:20:00+05:30
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

### Session 5 — Fleet Complete Tab Badge Fix + Dialog Rename

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  AGENT: Antigravity (Google DeepMind)
  SESSION START — 2026-07-25T11:56:00+05:30
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Goal:** Two UI fixes in the Fleet Assigned → Complete subtab:
1. Completed rows were not showing a "Completed" badge (only expired rows showed "Expired").
2. The recomplete confirmation dialog title said "Mark completed offline" — renamed to "Complete Outcome".

**Root cause for missing badge:**
`visit.expired` is `false` for normally-completed trips — they were marked complete by the travel
desk through the normal flow, so they are never flagged `expired`. The Expired badge at line 225
already handled expired-but-not-recompleted rows. The Complete subtab had no badge for
non-expired completed rows at all.

**Fix — `features/fleet/tabs/assigned-tab.tsx`:**

| Line area | Change |
|-----------|--------|
| ~L225–232 | Added `{assignedSubtab === "complete" && !visit.expired && <Badge ...>Completed</Badge>}` — green badge shown on normally-completed rows in the Complete tab only. |
| L501 | Renamed `<DialogTitle>` from `"Mark completed offline"` → `"Complete Outcome"` |

**Badge logic (Complete subtab):**
- `visit.expired === true` → red **Expired** badge (unchanged)
- `visit.expired === false` AND `assignedSubtab === "complete"` → green **Completed** badge (new)
- `visit.completedOffline === true && !visit.outcome` → amber **Outcome pending** badge (from Session 4, unchanged)

**Files changed:**
- `features/fleet/tabs/assigned-tab.tsx`

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  AGENT: Antigravity (Google DeepMind)
  SESSION END — 2026-07-25T11:58:00+05:30
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

*— End of log. Append new sessions below this line. —*
 

---

### Session 6 - Continue Fleet Complete Outcome Flow

**Date:** 2026-07-25
**Agent:** Codex

**Goal:** Continue the interrupted Antigravity work. Verify and finish the fleet "Completed"
button flow without deploying Convex locally: web should reopen the SV outcome form correctly,
the fleet completion dialog should collect trip details, only package price should be required,
and the Android app should get the same completed/offline outcome behavior.

**Progress:**
- Read this log and confirmed Session 4/5 context.
- Checked current workspace state: web repo has in-progress fleet/offline-complete edits; Android
  repo has only local IDE/cache noise plus this log.
- Traced the interrupted flow: web Fleet Assigned > Complete currently calls `markExpiredTripOutcomePending` with only the site visit id, so trip billing/driver details are never saved.
- Confirmed the reopened SV outcome panel exists on `site-visit-detail-page.tsx` when `completedOffline === true && outcome == null`.
- Confirmed Android Admin Fleet puts expired rows in Completed but treats them as read-only; it needs the same Complete Outcome action.
- Added richer `markExpiredTripOutcomePending` args on the web backend: package amount required; distance, driver name/phone, beta, and toll optional. It stores package pricing-compatible `travelDesk*` fields while keeping `completedOffline` outcome-pending.
- Added `/api/mms-fleet/dispatch/complete-offline` so Android staff dispatch can call the same mutation after backend deploy.
- Began replacing the web Complete Outcome confirmation with a trip-details draft form in `use-fleet-assigned-controller.ts`.
- Finished the Android side of the same flow: Admin Fleet expired/completed rows now open a Complete Outcome sheet instead of being read-only; the sheet requires only package price and accepts optional distance, driver, driver phone, beta, and toll.
- Added Android network/request support for `/api/mms-fleet/dispatch/complete-offline` and included the newly saved trip fields in `TravelDeskTrip` mapping.
- Verified Android compile with `./gradlew.bat :app:assembleDebug` after setting Android Studio JBR as `JAVA_HOME`; build passed.

---

### Session 7 — Camera Crash Fix + External Fleet Complete-Offline + 1-Day Expiry Parity

**Date:** 2026-07-25
**Agent:** Codex (big-pickle)

**Goal:** Fix camera crash blocking external fleet drivers from starting trips; enable
complete-offline for external fleet trips on Android; add 1-day expiry grace period to
MMS web so all 3 repos agree.

**What was done:**

#### 1. Camera crash fix (Android)
The `AgencyDriverTripActionSheet` crashed immediately when opening the camera for
odometer photos. Root causes:
- `FileProvider.getUriForFile()` was unguarded — can throw `IllegalArgumentException`
  on certain OEM ROMs (SELinux policy edge cases). Wrapped in `runCatching`.
- `cameraLauncher` callback called `requireContext()` after process death — added
  `isAdded` guard.
- `permissionLauncher` callback same issue — added `isAdded` guard.
- `performSubmit()` catch block called `requireContext()` after the sheet could be
  detached — added `isAdded` guard.

Ported the same 3 guards to `DriverStartTripBottomSheet` and `DriverEndTripBottomSheet`
which had the identical bugs. `DriverEndTripBottomSheet` already had the `performSubmit`
guard (from a previous fix) but was missing the camera callback guards.

**Files changed (Android):**
| File | Change |
|------|--------|
| `AgencyDriverTripActionSheet.kt` | `isAdded` in camera/permission callbacks; `runCatching` on FileProvider; `isAdded` in performSubmit catch |
| `DriverStartTripBottomSheet.kt` | Same 3 fixes |
| `DriverEndTripBottomSheet.kt` | `isAdded` in camera/permission callbacks; `runCatching` on FileProvider |

#### 2. External fleet complete-offline (Android)
Removed the `!useMmsFleet` guard in `openCompleteOfflineSheet()` that blocked external
fleet trips from using the Complete Outcome flow. The backend endpoint
`/api/mms-fleet/dispatch/complete-offline` calls `fleet.markExpiredTripOutcomePending`
which is fleet-agnostic.

**File changed:**
| File | Change |
|------|--------|
| `AdminFleetTripsFragment.kt` | Removed `!useMmsFleet` block in `openCompleteOfflineSheet()` |

#### 3. 1-day expiry grace period — MMS web
`isExpiredAssignedVisit()` in `convex/marketing/fleet.ts` used `date < todayIST` with no
grace. App (`VisitExpiry.kt`) and travel-desk (`isExpiredTrip`) already had 1-day grace.
Added `ONE_DAY_MS` grace to MMS web so all 3 surfaces agree.

**File changed (MMS web):**
| File | Change |
|------|--------|
| `convex/marketing/fleet.ts` | Added 1-day grace to `isExpiredAssignedVisit()` |

#### External fleet progress actions
Verified that the full progress chain (reached → start → on-site → picked-from-site → end)
already works for both internal and external fleet on Android:
- `AgencyDriverTripDetailFragment` handles all intermediate steps via `markOnSite()` and
  `markPickedFromSite()` which branch on `internal` flag.
- `AgencyDriverTripActionSheet` handles start (arrive + start chained) and end.
- `AdminFleetTripsFragment.submitProgressAction()` handles admin-initiated progress for
  both fleet types.
- All mutations hit the correct backend (TravelDeskApi for external, GeoTrackApi for internal)
  and are reflected on both MMS web and travel-desk web.

**Build result:** `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** ✅

**Pushes:**
| Repo | Remote | Branch | Status |
|------|--------|--------|--------|
| Mconnect | origin (manjugroupsdev) | merge | ✅ `03a2287..1ed1d52` |
| Mconnect | darx (D-A-R-X) | merge | ✅ `03a2287..1ed1d52` |
| MMS website | origin (manjugroupsdev) | max | ✅ `da88ecdc..962319cc` |
| travel-desk | — | aizen | No changes needed (already had 1-day grace) |

---

### Session 7b — CompleteOfflineSheet Redesign: Fleet Type + Vehicle Picker

**Date:** 2026-07-25
**Agent:** Codex (big-pickle)

**Goal:** Redesign the Complete Offline (expired trip) form so fleet type is
explicitly selected (Internal/External) and the correct fields appear for each.

**What was done:**

#### Layout redesign
- Added a **Fleet type** spinner (Internal / External) defaulting from the trip's
  `external` flag.
- **Internal panel:** vehicle picker (SearchableSelectionDialog, matches the allocate
  sheet pattern), auto-displays default driver name/phone when a vehicle is selected.
  Package price (required) + distance (optional) below.
- **External panel:** agency name text (pre-filled from trip data), package price
  (required), distance (optional).
- Panels toggle visibility based on the spinner selection.

#### Data model change
`CompleteOfflineTripResult` replaced with a cleaner model:
| Field | Type | Purpose |
|-------|------|---------|
| `fleetType` | `"internal"` / `"external"` | Chosen fleet type |
| `vehicleId` | `String?` | Selected vehicle (internal only) |
| `vehicleLabel` | `String?` | Display label (internal only) |
| `driverName` | `String?` | From vehicle default driver (internal) |
| `driverPhone` | `String?` | From vehicle default driver (internal) |
| `agencyName` | `String?` | Agency name (external only) |
| `packageAmount` | `Double` | Required for both |
| `distanceKm` | `Double?` | Optional for both |

Removed `beta` / `tollAmount` from the result — the complete-offline form only
collects package + distance; beta/toll belong in the start/end trip capture sheets.

#### Call site updates
- `AdminFleetTripsFragment.openCompleteOfflineSheet()` now passes `vehicles` list.
- `HomeFragment` passes `emptyList()` (outcome-pending home cards don't need the
  vehicle picker — the trip was already assigned).

**Files changed:**
| File | Change |
|------|--------|
| `AdminFleetCompleteOfflineSheet.kt` | Full rewrite — fleet type spinner, vehicle picker, dual panels |
| `dialog_admin_fleet_complete_offline.xml` | Full rewrite — fleet type spinner, Internal/External panels |
| `AdminFleetTripsFragment.kt` | Pass `vehicles` to sheet; remove beta/toll from request |
| `HomeFragment.kt` | Pass `emptyList()` to sheet; remove beta/toll from request |

**Build result:** `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** ✅

**Pushes:**
| Repo | Remote | Branch | Status |
|------|--------|--------|--------|
| Mconnect | origin (manjugroupsdev) | merge | ✅ `1ed1d52..aa2b8a4` |
| Mconnect | darx (D-A-R-X) | merge | ✅ `1ed1d52..aa2b8a4` |

---

### Session 8 — Own Vehicle, FleetType in Server, IAM Gating, Chain to SV Outcome Form

**Date:** 2026-07-25
**Agent:** Codex (big-pickle)

**Goal:** Add "Own Vehicle" as third fleet type, persist fleetType in the backend,
gate complete-offline behind IAM, and chain the outcome form after completion.

**What was done:**

#### 1. Own Vehicle option (app)
- Added "Own Vehicle" as third option in fleet type spinner (Internal / External / Own Vehicle).
- Own Vehicle panel has just package price + distance — no vehicle/agency fields.
- `parseResult()` returns `fleetType = "own"` with null vehicleId/agencyName.

#### 2. FleetType persisted in backend (MMS web)
- **Schema:** Added `completedOfflineFleetType` field to `siteVisits` table
  (`v.union("internal", "external", "own")`).
- **Mutation:** `markExpiredTripOutcomePending` now accepts `fleetType`, `vehicleId`,
  `agencyName` args and stores fleetType on the visit.
- **HTTP route:** `/api/mms-fleet/dispatch/complete-offline` forwards the new fields.
- **Audit metadata:** fleetType, vehicleId, agencyName included.

#### 3. FleetType sent from app
- `CompleteOfflineTripRequest` now includes `fleetType`, `vehicleId`, `agencyName`.
- All 3 call sites updated: AdminFleetTripsFragment, HomeFragment (×2).

#### 4. IAM gating
- Added `canCompleteOfflineFleet()` to `SessionManager` (checks `marketing.fleet.completeOffline`).
- `AdminFleetTripsFragment`: "Completed" button hidden when user lacks permission.
- `AdminFleetTripsFragment.openCompleteOfflineSheet()`: shows toast + returns if no permission.
- `AdminTripsAdapter`: has `canCompleteOffline` flag set from session.
- `HomeFragment`: "Complete" button on expired fleet cards gated on `canCompleteOfflineFleet()`.

#### 5. Chain to SV outcome form
- After complete-offline succeeds, the app now opens `CompleteCpVisitBottomSheet.forSiteVisit()`
  which shows **Booking / Postpone / Not Interested** tabs.
- Both AdminFleetTripsFragment and HomeFragment paths chain to the outcome form.
- AdminFleetTripsFragment has a result listener to refresh when the outcome form completes.

#### 6. MMS web fleet type in form
- Added fleet type dropdown (Internal / External / Own Vehicle) to the complete-offline
  dialog in `assigned-tab.tsx`.
- `RecompleteTripDraft` type extended with `fleetType` field.
- Controller defaults fleetType based on `visit.travelAgency` presence.
- `handleRecomplete()` passes `fleetType` to the mutation.

#### 7. External fleet status sync (verified)
- All 5 driver mutations (markArrived, startTrip, endTrip, markOnSite, markPickedFromSite)
  already update the corresponding `travelDesk*At` fields on siteVisits.
- No changes needed — sync is working correctly.

**Files changed:**

| Repo | File | Change |
|------|------|--------|
| Mconnect | `AdminFleetCompleteOfflineSheet.kt` | Added Own Vehicle spinner option + layout |
| Mconnect | `dialog_admin_fleet_complete_offline.xml` | Added Own Vehicle panel (layoutOwnVehicle) |
| Mconnect | `TravelDeskModels.kt` | Added fleetType/vehicleId/agencyName to request |
| Mconnect | `AdminFleetTripsFragment.kt` | IAM gating, chain to outcome form, pass fleetType |
| Mconnect | `HomeFragment.kt` | Chain to outcome form, pass fleetType, IAM gate |
| Mconnect | `SessionManager.kt` | Added canCompleteOfflineFleet() |
| MMS web | `convex/schema.ts` | Added completedOfflineFleetType to siteVisits |
| MMS web | `convex/marketing/fleet.ts` | Accept + store fleetType in mutation |
| MMS web | `convex/http.ts` | Forward fleetType/vehicleId/agencyName in route |
| MMS web | `features/fleet/types.ts` | Added fleetType to RecompleteTripDraft |
| MMS web | `features/fleet/tabs/assigned-tab.tsx` | Fleet type dropdown in complete-offline form |
| MMS web | `features/fleet/use-fleet-assigned-controller.ts` | Pass fleetType to mutation |

**Build result:** `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** ✅

**Pushes:**
| Repo | Remote | Branch | Status |
|------|--------|--------|--------|
| Mconnect | origin (manjugroupsdev) | merge | ✅ `aa2b8a4..f9af402` |
| Mconnect | darx (D-A-R-X) | merge | ✅ `aa2b8a4..f9af402` |
| MMS web | origin (manjusitedevelopment) | max | ✅ `962319cc..4b9b77e9` |

---

### Session 9 — Gallery Upload Option for Fleet Trip Odometer Photos

**Date:** 2026-07-27
**Agent:** Codex (big-pickle)

**Goal:** Add "Choose from gallery" option alongside camera capture for speedometer/odometer
photos in all fleet trip action sheets (agency driver, start trip, end trip).

**What was done:**

#### Gallery upload pattern
Each of the 3 trip photo sheets now shows a source chooser dialog ("Take photo" / "Choose from
gallery") when the user taps the upload area. Gallery images are copied to a temp file in the
app's cache dir before use.

**Pattern (same in all 3 sheets):**
1. `galleryLauncher = registerForActivityResult(GetContent())` — picks image from device.
2. `showPhotoSourceChooser()` — AlertDialog with "Take photo" / "Choose from gallery".
3. `showCapturedPhoto(file)` — extracted from both camera and gallery paths to reuse.
4. `copyUriToTempFile(uri)` — copies gallery URI to a cache file.

**Files changed (Android):**

| File | Change |
|------|--------|
| `AgencyDriverTripActionSheet.kt` | Added `galleryLauncher`, `showPhotoSourceChooser()`, `showCapturedPhoto()`, `copyUriToTempFile()`; changed `btnUpload` click to show source chooser |
| `DriverStartTripBottomSheet.kt` | Same pattern — gallery launcher, source chooser, extracted helpers |
| `DriverEndTripBottomSheet.kt` | Same pattern — gallery launcher, source chooser, extracted helpers |
| `dialog_agency_trip_capture.xml` | Placeholder text "Capture photo" → "Capture or upload photo" |
| `dialog_driver_start_trip.xml` | Placeholder text "Upload Image" → "Capture or upload photo" |
| `dialog_driver_end_trip.xml` | Placeholder text "Upload Image" → "Capture or upload photo" |

**Build result:** `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** ✅

**Pushes:**
| Repo | Remote | Branch | Status |
|------|--------|--------|--------|
| Mconnect | origin (manjugroupsdev) | merge | ✅ `913ede8..62890a9` |
| Mconnect | darx (D-A-R-X) | merge | ✅ `913ede8..62890a9` |

---

### Session 10 — External Driver Travel-Desk Website Login + SV Flow + MMS Status Sync

**Date:** 2026-07-27
**Agent:** Codex (big-pickle)

**Goal:** Ensure external drivers can log into the travel-desk website with the same SV flow
(OTP, image upload, stats updates) as the app, and that their stats sync back to MMS so
internal site incharge staff can fill the outcome form properly.

**Problem identified:**
1. External driver mutations (`travelDeskDriverTrips.ts`) only wrote `travelDesk*At` timestamps —
   they did NOT advance the `status` field on siteVisits. MMS fleet driver mutations DID advance
   `status` (picked_up → on_site → picked_from_site → dropped). Result: internal MMS staff
   couldn't see external driver trip progress because status stayed "scheduled".
2. `listForDriver` query only fetched `status === "scheduled"` trips — once status advanced,
   in-progress trips disappeared from the driver's list entirely.
3. Travel-desk website was missing the "Picked from Site" step that the app had.

**What was done:**

#### 1. Backend — Status sync with MMS (`manjusitedevelopment`)
Updated `convex/travelDeskDriverTrips.ts` mutations to also advance the `status` field,
matching `mmsFleetDriverTrips.ts` behavior:

| Mutation | Status set | Additional fields |
|----------|-----------|-------------------|
| `startTrip` | `"picked_up"` | `pickedUpAt` |
| `markOnSite` | `"on_site"` | `arrivedSiteAt`, `clientArrived: true` |
| `markPickedFromSite` | `"picked_from_site"` | `pickedFromSiteAt` |
| `endTrip` | `"dropped"` | `droppedAt`, `pickedFromSiteAt` backfill |

#### 2. Backend — Fix `listForDriver` query
Changed from querying only `status === "scheduled"` to querying across all operational statuses
(`scheduled`, `picked_up`, `on_site`, `picked_from_site`, `dropped`) with deduplication by `_id`.
This prevents in-progress trips from vanishing after status advancement.

#### 3. Travel-desk website — "Picked from Site" step
- Added `markTravelDeskTripPickedFromSite` API function to `travel-desk-api.ts`.
- Created `/api/travel-desk/trips/picked-from-site` Next.js proxy route.
- Added `PickedFromSiteStep` component in `DriverTripsPanel` between "On Site" and "End Trip".
- Full driver lifecycle on web now matches the app: Arrive → Start → On Site → Picked from Site → End.

**Files changed:**

| Repo | File | Change |
|------|------|--------|
| MMS web (backend) | `convex/travelDeskDriverTrips.ts` | Status advancement in all mutations; multi-status `listForDriver` query |
| travel-desk | `src/lib/travel-desk-api.ts` | Added `markTravelDeskTripPickedFromSite()` |
| travel-desk | `src/components/driver-trips-panel.tsx` | Added `PickedFromSiteStep`, handler, and import; updated `TripActionModal` props |
| travel-desk | `src/app/api/travel-desk/trips/picked-from-site/route.ts` | New proxy route (created) |

**Build results:**
- `npx next build` (travel-desk) → **BUILD SUCCESSFUL** ✅
- `./gradlew :app:assembleDebug` (Mconnect) → **BUILD SUCCESSFUL** ✅

**Pushes:**
| Repo | Remote | Branch | Status |
|------|--------|--------|--------|
| MMS web | origin (manjugroupsdev) | max | ✅ `c6bfb397..373a84b8` |
| travel-desk | origin (manjugroupsdev) | aizen | ✅ `cb4c8b5..7190bd6` |
| Mconnect | — | merge | No changes (unchanged) |

### Session 11 - Expired Fleet Trips Open SV Outcome Flow

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Finish the expired-trip flow so Fleet can open the Site Incharge outcome form intentionally, keep the expired trip in an outcome-pending state, and show that opened state clearly on the SV detail page. The fleet completion sheet also needed date/time fields.

**What changed:**
- Added a confirmation step before the Fleet "Completed" button opens the trip completion sheet.
- Added completion date and time fields to the Fleet completion sheet and stored them as `completedOfflineOpenedAt`.
- Threaded `completedOfflineOpenedAt` through Convex schema, Fleet mutation, and the site-visit query used by the SV detail page.
- Updated the SV detail page to explicitly show "Outcome form opened for Site Incharge" with the opened timestamp when present.
- Kept the existing assigned/complete bucketing intact so outcome-pending expired trips still stay in the dispatcher queue until the Site Incharge records the outcome.
- Added a narrow Convex Workpool type shim plus one local narrowing in `reportQueue.ts` to restore the repo's Next.js type-check/build.

**Validation:**
- `npm run build` → **BUILD SUCCESSFUL** ✅

**Files changed:**
| File | Change |
|------|--------|
| `convex/schema.ts` | Added `completedOfflineOpenedAt` to site visits |
| `convex/marketing/fleet.ts` | Saved `completedOfflineOpenedAt` in the offline-complete mutation |
| `convex/http.ts` | Accepted `completedOfflineOpenedAt` in the POST route |
| `convex/marketing/siteVisits.ts` | Exposed `completedOfflineOpenedAt` in the mobile/site visit payload |
| `features/fleet/types.ts` | Added `completedOfflineOpenedAt` and completion date/time draft fields |
| `features/fleet/use-fleet-assigned-controller.ts` | Added confirm prompt, date/time draft, and timestamp parsing |
| `features/fleet/tabs/assigned-tab.tsx` | Added confirmation dialog, date/time inputs, and opened-form summary state |
| `features/marketing/pages/site-visit-detail-page.tsx` | Show opened-for-site-incharge state and timestamp |
| `convex/convex-config-modules.d.ts` | Added Workpool ambient declarations |
| `convex/reportQueue.ts` | Narrowed the report job lookup for type-checking |

---

### Session 12 - Internal Vehicle Fetch + External Fleet Offline Completion

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Make the expired-trip completion form fetch internal vehicles when
Internal is selected, and expose the same Completed flow to external travel
agencies in both the Mconnect app and Travel Desk website.

**What changed:**

#### 1. MMS web completion form
- Added a permission-scoped query that returns active MFPL/internal-agency
  vehicles for offline completion.
- Internal fleet selection now shows a required vehicle dropdown instead of
  editable driver fields.
- The selected vehicle ID is persisted on the site visit. Its default driver
  name/phone are resolved server-side from the vehicle record.
- The completion mutation validates that the selected vehicle is active and
  belongs to MFPL or an internal agency.

#### 2. External agency backend
- Added `travelDeskTrips.completeExpiredOffline`, authenticated with the agency
  session and restricted to trips allotted to that exact agency.
- Added `/api/travel-desk/trips/complete-offline`.
- Stores package price, optional distance/beta/toll, completion date/time,
  `completedOfflineFleetType = "external"`, and opens the Site Incharge outcome
  state.
- Expanded the agency assigned query across operational statuses so finished
  external trips remain visible in Travel Desk.

#### 3. Travel Desk website
- Expired rows in the Complete tab now show a `Completed` action.
- Added the confirmation prompt and completion form. Package price is the only
  required field; date/time, distance, beta and toll are optional.
- Added the Next.js proxy route and client API call for agency offline
  completion.
- Completed-offline summaries show `Outcome form opened for Site Incharge`.

#### 4. Mconnect app
- External agency accounts can now see the Completed action for their own
  expired trips and submit through the agency-auth endpoint.
- Internal Home completion now passes the fetched internal vehicle list into
  the form instead of an empty list.
- Internal completion requires a selected vehicle.
- External completion does not open the staff-only outcome form for the agency;
  it confirms that the form was opened for the Site Incharge.
- Pending/expired external rows are rebucketed correctly, and an already opened
  outcome displays `Outcome Pending` without offering Completed again.

**Validation:**
- MMS web: `npm run build` -> **SUCCESS**
- Travel Desk web: `npm run build` -> **SUCCESS**
- Mconnect Android: `./gradlew :app:assembleDebug` -> **SUCCESS**
- Targeted lint still reports pre-existing repository issues: legacy
  `no-explicit-any` findings in large Convex files and two existing
  `react-hooks/set-state-in-effect` findings in Travel Desk `trips/page.tsx`.

**Files changed in this session:**

| Repo | File | Change |
|------|------|--------|
| MMS web | `convex/marketing/fleet.ts` | Internal vehicle query, vehicle validation and persistence |
| MMS web | `convex/travelDeskTrips.ts` | Agency-scoped expired completion and multi-status listing |
| MMS web | `convex/http.ts` | Agency completion HTTP route and typed MMS vehicle ID |
| MMS web | `features/fleet/types.ts` | Vehicle ID in completion draft/visit type |
| MMS web | `features/fleet/use-fleet-assigned-controller.ts` | Load vehicles and submit selected vehicle |
| MMS web | `features/fleet/tabs/assigned-tab.tsx` | Internal vehicle dropdown; hide internal driver inputs |
| Travel Desk | `src/lib/travel-desk-api.ts` | Offline completion API and completed-offline fields |
| Travel Desk | `src/app/trips/page.tsx` | Completed action, confirmation, form and summary state |
| Travel Desk | `src/app/api/travel-desk/trips/complete-offline/route.ts` | Next.js proxy route |
| Mconnect | `TravelDeskApi.kt` | External agency completion endpoint |
| Mconnect | `SessionManager.kt` | Allow external agency completion capability |
| Mconnect | `AdminFleetTripsFragment.kt` | Agency submit path and outcome-pending bucketing |
| Mconnect | `AdminFleetCompleteOfflineSheet.kt` | Require internal vehicle selection |
| Mconnect | `HomeFragment.kt` | Pass fetched internal vehicles to completion sheet |

---

### Session 13 - Shared CP/SV Address Autofill and Map Form

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Reuse the working CP create address form in CP edit and Site Visit
create/edit, restore pincode-based location autofill, and prefill addresses
already captured by telecaller analysis or client-place records.

**What changed:**
- Added India Post pincode lookup to `UnifiedAddressFields`. A valid six-digit
  pincode now fills locality, city/district, and authoritative state data, then
  lets the existing geocoder position the map.
- Added a shared free-text-to-unified-address initializer so saved legacy or
  telecaller address strings are parsed into the seven structured fields.
- Replaced the legacy CP edit address form with the same unified component used
  by CP create. It now seeds from the saved client place first and falls back to
  the CP visit address, coordinates, and map link.
- Replaced the plain SV create pickup-address textarea and manual coordinate
  block with the unified component.
- SV create now hydrates the address from saved client-place data, latest call
  analysis, or the lead fallback address without overwriting later user edits.
- Replaced the SV detail edit textarea with the unified component and persists
  its joined address, coordinates, and Google Maps link.

**Validation:**
- MMS web: `npm run build` -> **SUCCESS**
- Focused CP edit lint: **SUCCESS**
- `git diff --check` -> **SUCCESS**
- Built server `/api/pincode?pin=600083` -> HTTP 200, `Success`, two offices,
  district `Chennai`, state `Tamil Nadu`
- Full focused lint still reports pre-existing findings in the large SV detail
  page and existing pin-dialog effects inside `UnifiedAddressFields`; no new
  pincode or SV-create lint finding was introduced.

**Files changed in this session:**

| Repo | File | Change |
|------|------|--------|
| MMS web | `components/unified-address-fields.tsx` | Pincode lookup, loader, and saved-address initializer |
| MMS web | `components/lead/edit-cp-visit-dialog.tsx` | Unified CP edit address/map form with saved-data prefill |
| MMS web | `features/marketing/pages/site-visits-list-page.tsx` | Unified SV create form and telecaller/client-place prefill |
| MMS web | `features/marketing/pages/site-visit-detail-page.tsx` | Unified SV edit form with coordinate/map persistence |

---

### Session 14 - External Fleet Contract Across MMS, Travel Desk, and Mconnect

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Implement the External Fleet requirements across the shared MMS
backend, Travel Desk web, and the Mconnect Travel Desk app while applying the
VP overrides.

**VP overrides applied:**
- Vehicle Model and Model Year remain present and are mandatory.
- Extra-kilometre claims remain pending until a Transport Manager approves or
  rejects them.
- Driver identity has first priority for OTP. Agency-administrator OTP is
  routed to an active Transport Manager instead of the agency contact.

**Shared MMS backend and Fleet module:**
- Restricted external vehicles to SUV, Sedan, and Hatchback with server-owned
  capacities (7, 5, and 5 respectively).
- Made vehicle number optional at the client/API boundary while retaining
  Model and Model Year validation.
- Added OLD/NEW driver category.
- Expanded agency settings with package, per-km, betta, permit, tax, standing
  charge/duration, waiting, cancellation, and toll values.
- Added agency staff records and `agency_staff` sessions. Staff can operate the
  same agency's trips, drivers, and vehicles; settings/staff administration is
  agency-admin only.
- Added assignment revision metadata so reassigned cards can be highlighted.
- Added extra-km claim calculation and pending/approved/rejected state.
  Approved claims alone are added to the trip total.
- Added `marketing.fleet.reviewExtraKm` to IAM and Transport Manager review
  controls in MMS Fleet.

**Travel Desk web:**
- Added the Staff page and role-based navigation guards.
- Added OLD/NEW driver category and expanded Settings.
- Added compact vehicle creation inside allocation/reassignment.
- Added reassignment highlighting and extra-km claim submission/status.
- Preserved scheduled date/time display and offline completion behavior.

**Mconnect app:**
- Added the agency Staff tab, create/edit/status flows, and `agency_staff`
  routing.
- Agency staff see Trips, Vehicles, and Drivers only.
- Added OLD/NEW driver category, expanded agency settings, and extra-km claim
  submission/status.
- Updated external vehicle create/edit rules to match Travel Desk web.

**Validation:**
- MMS web: `npm run build` -> **SUCCESS**
- Travel Desk web: `npm run build` -> **SUCCESS**
- Mconnect Android: `./gradlew :app:assembleDebug` -> **SUCCESS**

**Deployment note:**
- Convex was not deployed from this workspace. The shared schema/functions and
  HTTP routes must be deployed by the administrator before the new clients use
  the contract.
- WhatsApp assignment and inbound dashboard-photo/start-km ingestion remain
  pending because the approved driver template and provider webhook payload/
  verification contract are not present in the repository.

---

### Session 15 - 48-Hour Visit Expiry and Fast CP Pincode Lookup

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Give CP, SV, and Travel Desk trips a consistent 48-hour grace from
record creation, and remove the repeated slow pincode lookup in CP edit.

**What changed:**
- MMS Fleet and Travel Desk backend expiry now use
  `createdAt + 48 hours` for unstarted trips.
- Travel Desk web uses the same creation-based expiry rule.
- Mconnect's shared `VisitExpiry` accepts the Convex creation timestamp and is
  used by CP, SV, internal Fleet, agency Fleet, and driver trip screens.
- Older payloads without creation time retain a 48-hour scheduled-slot
  fallback until the backend update is deployed.
- The pincode proxy now uses HTTPS keep-alive, a six-second upstream timeout,
  in-flight request deduplication, and a 24-hour server cache.
- Pincode responses now carry browser/CDN cache headers, and the shared client
  helper retains results for 24 hours. Reopening a CP no longer repeats the
  same India Post request.

**Validation:**
- MMS web: targeted pincode proxy/helper ESLint -> **SUCCESS**
- MMS web: `npm run build` -> **SUCCESS**
- Travel Desk web: `npm run build` -> **SUCCESS**
- Mconnect Android: `./gradlew :app:assembleDebug` -> **SUCCESS**
- Full `UnifiedAddressFields` lint still reports the two pre-existing
  pin-dialog synchronous-effect findings; the changed proxy/helper files pass.

---

### Session 16 - CP Address Approximate Map Focus Before Pin Drop

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Center CP create/edit maps on the best approximate result from a
searched or pasted address, then let staff refine the exact point by dragging
or dropping the pin.

**What changed:**
- Promoted the existing map-service address search from the pin dialog into
  the shared address form's automatic forward-geocoding flow.
- Pasted/typed structured addresses now try the map search first, then Google
  Geocoder, then the existing OSM fallback.
- Successful approximate coordinates immediately update the inline preview
  and become the initial center of the Drop pin dialog.
- Added per-address coordinate caching, request cancellation, and stale-result
  guards so an older lookup cannot move the map away from the latest address.
- Async coordinate updates now merge into the latest form value instead of a
  stale render snapshot.

**Validation:**
- MMS web: `npm run build` -> **SUCCESS**
- Full component lint still reports the two pre-existing pin-dialog
  synchronous-effect findings; no new lint category was introduced.

---

### Session 17 - CP Map Marker Crash and Suggestion Dismissal

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Fix the Google Maps `AdvancedMarker` `getRootNode` crash after
selecting an address and ensure suggestions close after selection.

**What changed:**
- Replaced `AdvancedMarker` with the stable `Marker` component for the shared
  address preview and draggable pin dialog.
- Selecting a suggestion now closes the suggestion popup, clears its results,
  and stops the search loader before recentering the map.
- Kept the selected result's coordinates as the draggable starting point.

**Validation:**
- MMS web: `npm run build` -> **SUCCESS**

---

### Session 18 - Assigned-BDO QR Consulting Status and Fleet Backfill

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Add a Consulting stage after On Site for every Site Visit travel
category. Let only the BDO assigned to the SV confirm Consulting by scanning
the client's QR. When an internal/external fleet missed pickup and reached-site
updates, let the responsible fleet backfill the actual start with a dashboard
image, start kilometer, and start date/time.

**Backend / MMS shared contract:**
- Added `consulting` to the `siteVisits.status` schema and validators.
- Added `consultingAt` and `consultingVerifiedByStaffId`.
- Added authenticated `markConsultingFromQr`; it strictly verifies the signed-in
  staff id against `siteVisit.bdoStaffId`.
- The QR transition records only the verified Consulting time. It does not
  invent pickup or fleet timestamps.
- Added internal and external fleet backfill mutations. They require a dashboard
  image, non-negative start kilometer, and actual start date/time before the
  Consulting scan. Pickup uses the entered time; reached-site uses the verified
  QR scan time.
- Consulting visits are not treated as expired while fleet backfill is pending.
- Added the external Travel Desk HTTP/proxy contract for the same backfill.

**MMS web:**
- Added Consulting to Site Visit list badges and both progress strips.
- Own vehicle progression is Scheduled -> Client Departure -> Onsite ->
  Consulting -> Completed, with no fleet-pending state.
- Internal/external cab progress shows missed Picked from CP and On Site stages
  as pending after a Consulting scan until fleet backfill is saved.
- Added the internal Fleet "Fill pending trip" form.
- Added a Client QR dialog on the SV detail page. It renders the canonical
  `/site-visit/consulting/<siteVisitId>` QR and supports copying its link.

**Travel Desk web:**
- Added the external-agency "Fill pending trip" form and upload flow.
- Added `/api/travel-desk/trips/consulting-start-backfill`.
- Reused the existing authenticated Travel Desk photo uploader.

**Mconnect app:**
- Replaced the unused "Site Visit" header label with a Scan action.
- Added a dedicated CameraX + ML Kit SV scanner.
- Scanner accepts only the currently opened SV's client QR and relies on the
  backend for the final assigned-BDO authorization.
- Added Consulting to the app status badge, list badge, cab stepper, and own
  vehicle stepper.
- Cab scans leave missing fleet pickup/on-site stages pending; own-vehicle scans
  show Consulting without fleet pending.

**Validation:**
- MMS web: `npm run build` -> **SUCCESS** (167 routes/pages)
- MMS TypeScript: `npx tsc --noEmit` -> **SUCCESS**
- Travel Desk web: `npm run build` -> **SUCCESS**
- Mconnect: `:app:assembleDebug` -> **SUCCESS**
- Focused Convex tests: 23 passed, 1 existing reassignment-contract mismatch.
  `fleet.test.ts` expects a second `assignVehicle` call to reassign, while the
  implementation rejects already-assigned cabs and uses the dedicated
  reassignment path. No Consulting test failed.

**Deployment / provider dependency:**
- Convex schema, mutations, and HTTP routes in `manjusitedevelopment` must be
  deployed by an administrator before the app and Travel Desk can use this flow.
- The MMS page can generate/copy the client QR now. Automatic WhatsApp delivery
  is not claimed as complete because the approved client-QR template/provider
  payload is not present in the repositories.

---

### Session 19 - CP Pin Dialog Blank Map Regression

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Restore the Google base map in the shared CP address Drop pin dialog
after the marker crash fix left the map canvas blank.

**What changed:**
- Removed the custom cloud `mapId` from both maps in the shared
  `UnifiedAddressFields` component.
- The shared address preview and Drop pin dialog now use the standard Google
  base map, which is compatible with the stable legacy `Marker` introduced in
  Session 17.
- Kept address search, suggestion dismissal, map recentering, click-to-drop,
  draggable marker, and reverse-geocoding behavior unchanged.

**Validation:**
- Confirmed the configured Google Maps browser script remains reachable for
  the localhost origin.
- MMS web: `npm run build` -> **SUCCESS** (167 routes/pages).

---

### Session 20 - 12-Hour Time Entry Across MMS, Travel Desk, and Mconnect

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Make every editable time-setting control use an explicit 12-hour
hour/minute/AM-PM experience without changing the backend time contracts.

**MMS web:**
- Upgraded the shared `Input` primitive so all existing `type="time"` usages
  render hour, minute, and AM/PM selectors.
- Kept controlled values, change events, named form values, and API payloads
  normalized as 24-hour `HH:mm`.
- Migrated the three legacy Telecaller flow time fields that used raw HTML
  inputs to the shared component.
- This covers the existing time controls across Attendance, Marketing CP/SV,
  Fleet, Telecaller, HR, Frontdesk, MOM, Land Procurement, Shifts, Task
  Manager, Handoffs, and Project Management.
- Added focused component tests for 24-hour-to-12-hour rendering, AM/PM
  conversion, and uncontrolled form submission.

**Travel Desk web:**
- Added a reusable 12-hour time input with separate hour, minute, and AM/PM
  selectors.
- Replaced the four editable trip fields: allocation pickup, reassignment
  pickup, Consulting start, and offline trip completion time.
- Travel Desk API values remain `HH:mm`.

**Mconnect Android:**
- Added `EditableTimeFormat` as the shared conversion helper.
- All active `TimePickerDialog` controls now open in 12-hour mode.
- CP visit creation, SV time selection, vehicle pickup allocation, permission
  duration, and attendance correction show AM/PM labels.
- Internal selected values remain `HH:mm`; attendance submission continues to
  use the existing ISO contract.

**Validation:**
- MMS focused time-input tests: **3 passed**
- MMS TypeScript: `npx tsc --noEmit` -> **SUCCESS**
- MMS web: `npm run build` -> **SUCCESS** (167 routes/pages)
- Travel Desk web: `npm run build` -> **SUCCESS**
- Mconnect Android: `.\gradlew.bat :app:assembleDebug --no-daemon` ->
  **SUCCESS**

---

### Session 21 - Travel Desk Staff 404 / Invalid JSON Handling

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Stop the Travel Desk Agency Staff page from hanging and repeatedly
throwing `Unexpected token 'N'` when the configured Convex deployment returns
plain-text `No matching routes found`.

**Root cause:**
- The local Next routes for staff list/create/update exist and compile.
- The configured Convex production deployment does not yet contain
  `/api/travel-desk/staff`, `/staff/create`, or `/staff/update`.
- Convex therefore returns a plain-text 404. The proxy incorrectly labelled
  that text as JSON, and the client called `response.json()`, causing an
  unhandled syntax error and leaving the page in `Loading staff...`.

**What changed:**
- Added a shared Travel Desk Convex proxy-response helper.
- Staff list/create/update proxies now preserve valid upstream JSON but convert
  missing Convex actions into valid JSON `503 Service Unavailable` responses
  with `backendDeploymentRequired: true`.
- Hardened the shared Travel Desk response parser so non-JSON server responses
  become stable `{ success: false, error }` results instead of rejected
  promises.
- The Staff page now exits its loading state and shows the deployment-specific
  error instead of generating repeated console exceptions.

**Validation:**
- Local staff list/create/update endpoints each return valid JSON and correctly
  report the missing backend deployment.
- Travel Desk web: `npm run build` -> **SUCCESS** (10 pages/routes plus API
  routes).

**Required admin action:**
- Deploy the pending Convex source from `manjusitedevelopment` using the
  approved production process. Until that deployment occurs, Agency Staff
  records cannot be listed, created, updated, or used for authentication.

---

### Session 22 - SV Consulting QR Access for BDO and Site Incharge

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Allow either staff member assigned to an SV as its BDO or Site
Incharge to scan the client QR and move the visit to Consulting.

**What changed:**
- Updated `markConsultingFromQr` in the MMS/Convex backend to authorize the
  authenticated staff member when their staff ID matches either
  `bdoStaffId` or `inchargeStaffId` on that exact site visit.
- Unrelated staff remain blocked with HTTP 403; terminal SV statuses remain
  protected by the existing lifecycle validation.
- The scanner continues to record the actual scanning staff member in
  `consultingVerifiedByStaffId`.
- No Android UI change was required. The SV overview already exposes the QR
  scanner action without a BDO-only visibility condition and calls the same
  authenticated endpoint.
- Added focused Convex authorization tests for assigned BDO access, assigned
  Site Incharge access, and unrelated-staff rejection.

**Validation:**
- Convex focused tests: **3 passed**
- MMS web: `npm run build` -> **SUCCESS** (TypeScript + 167 routes/pages)

**Required admin action:**
- Deploy the pending Convex source from `manjusitedevelopment` using the
  approved production process. Until that deployment occurs, the live backend
  will continue enforcing its previously deployed BDO-only rule.

---

### Session 23 - Shared QR Scanner for SV Consulting with IAM

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Use the app's existing QR Scanner for both Front Desk invitations and
SV Consulting instead of maintaining a second scanner inside each SV.

**What changed in Mconnect:**
- The existing Home/App Library QR Scanner now recognizes both supported URL
  formats:
  - `/frontdesk/invite/<token>` keeps the existing visitor workflow.
  - `/site-visit/consulting/<siteVisitId>` confirms SV Consulting.
- The shared scanner entry is visible when staff have a Front Desk scanner
  permission or the new `marketing.siteVisits.scanConsulting` permission.
- Removed the dedicated SV Consulting scanner fragment, layout, SV-detail
  button, and result listener.
- Restored the SV overview header label to `Site Visit`.
- Renamed scanner-facing text from `Front Desk Scanner` to the generic
  `QR Scanner`.

**IAM and backend enforcement:**
- Added `marketing.siteVisits.scanConsulting` to the MMS IAM permission model,
  client permission descriptions, Site Visits taxonomy, and permission matrix.
- `markConsultingFromQr` now requires that IAM permission and still requires
  the authenticated staff member to be either the exact assigned BDO or exact
  assigned Site Incharge for the scanned SV.
- Missing IAM permission and unrelated-staff attempts both return HTTP 403.
- Updated the MMS client-QR description to name both assigned roles and the
  IAM requirement.

**Validation:**
- Convex + IAM focused tests: **10 passed**
- Mconnect Android: `:app:assembleDebug` -> **SUCCESS**
- MMS web: `npm run build` -> **SUCCESS** (TypeScript + 167 routes/pages)
- Confirmed no dedicated SV scanner references remain in the Android source.

**Required admin action:**
- Deploy the pending Convex/MMS source from `manjusitedevelopment`.
- Grant `marketing.siteVisits.scanConsulting` in IAM to the BDO and Site
  Incharge staff/designations that should use the scanner. Assignment to the SV
  remains mandatory even when the permission is granted.

---

### Session 24 - SV QR Counselling, Follow Up Outcome, and Real Postpone

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Align the Android SV scanner and outcome flow with the manager-provided
web endpoints and keep outcome Follow Up separate from rescheduling an SV.

**What changed in Mconnect:**
- The shared QR Scanner accepts both `SV:<siteVisitId>` and the legacy
  `/site-visit/consulting/<siteVisitId>` URL format.
- Scanning now calls the read-only
  `POST /api/marketing/siteVisits/scanQr` endpoint first. The app no longer
  changes the SV status as a side effect of reading the QR.
- A bottom sheet shows the validated SV and asks the assigned user to confirm
  `Start counselling`.
- Confirmation calls
  `POST /api/marketing/siteVisits/markOnCounselling`; only after that succeeds
  does the shared SV outcome form open.
- In pure-SV mode, the old `Postpone` outcome is now displayed and submitted as
  `Follow up` / `follow_up`. CP outcome behavior remains unchanged and still
  uses `postponed`.
- Added a separate `Postpone SV` action in the SV overview for staff with
  `marketing.siteVisits.edit`. Its sheet collects a required new date plus an
  optional 12-hour time and reason, then calls
  `POST /api/marketing/siteVisits/postpone`.
- Real postpone therefore closes the current SV and lets the backend create the
  replacement SV for the chosen date; it is not stored as an outcome.

**Validation:**
- Confirmed there are no remaining Android references to the obsolete
  `markConsultingFromQr` endpoint.
- `:app:compileDebugKotlin` -> **SUCCESS**
- `:app:assembleDebug` -> **SUCCESS**
- The build needed a one-off 4 GB Gradle/Kotlin heap because parallel stale
  compiler daemons exhausted the default 2 GB heap; no project memory setting
  was changed.

**Backend dependency:**
- These mobile paths require the manager-provided `scanQr`,
  `markOnCounselling`, `setOutcome` follow-up support, and `postpone` routes to
  be deployed on the configured MMS/Convex backend.

---

### Session 25 - Travel Desk Deferred Trip Evidence

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Let an external Travel Desk operator finish every trip status even
when dashboard images or odometer readings are unavailable, while keeping the
completed trip pending until all required evidence is supplied.

**What changed in the standalone Travel Desk web repo:**
- After `Mark Reached`, the existing start form now asks for the start
  dashboard image and start km but permits continuing without either.
- The Dropped/end form likewise asks for the end dashboard image and end km but
  permits completing without either.
- Missing proof shows a clear warning before each lifecycle action.
- Completed trips with missing proof show `Pending verification`, list the
  exact missing items, and provide inputs to upload them later.
- Added the Next.js proxy and client API for
  `POST /api/travel-desk/trips/evidence`.

**What changed in MMS / shared Convex:**
- External driver, agency admin, and agency staff sessions can operate an
  allocated trip belonging to their agency.
- Start/end lifecycle mutations accept deferred images and km readings and set
  the existing proof-required marker when evidence is incomplete.
- Verification requires all four items:
  - start dashboard image
  - start km
  - end dashboard image
  - end km
- Added `travelDeskDriverTrips.submitEvidence` and the
  `/api/travel-desk/trips/evidence` HTTP endpoint.
- Evidence submission fills only missing proof, validates non-negative km and
  `endKm >= startKm`, recalculates the trip amount when possible, and clears
  Pending verification only when all four items exist.
- Legacy completed trips without the deferred-proof marker retain their prior
  completion behavior.

**Validation:**
- Travel Desk `npm run build` -> **SUCCESS** (10 pages/routes).
- MMS `npm run build` -> **SUCCESS** (167 pages/routes).
- Focused proof-state tests -> **3 passed**.
- No Convex deployment was performed.

**Required admin action:**
- Deploy the pending MMS/Convex source before testing deferred proof against the
  live Travel Desk portal.

---

### Session 26 - Read-Only SV QR Details and Protected Counselling Start

**Date:** 2026-07-27
**Agent:** Codex

**Goal:** Fix the SV QR 404 for non-assigned scanners, show useful visit
details to every authenticated scanner, and change status only after an
authorised user explicitly starts counselling.

**Root cause:**
- `getByQrPayload` reused the normal SV list scope and returned `null` for staff
  outside the SV assignment, which the HTTP route translated to 404.
- Android also blocked the scan before calling the read-only endpoint unless
  the user had `marketing.siteVisits.scanConsulting`.

**What changed in MMS / shared Convex:**
- QR lookup no longer applies the normal assignment visibility filter.
  Authenticated staff can read the scanned visit details.
- The scan response now includes server-derived `canStartCounselling`.
- Start permission is granted when the scanner is:
  - the assigned BDO
  - the assigned Site Incharge
  - superadmin/admin
  - explicitly granted `marketing.siteVisits.scanConsulting`
- Added `markOnCounsellingFromQr`, which independently enforces the same rule.
  The mobile HTTP route now uses this protected mutation and returns HTTP 403
  for unauthorised attempts.
- QR counselling can intentionally recover from `scheduled`,
  `client_started`, `picked_up`, or `on_site` to `on_counselling`. This keeps
  the previously requested fallback behavior when fleet stages were missed.
- Merely scanning remains read-only and never changes the SV status.

**What changed in Mconnect:**
- Removed the permission pre-block from QR reading.
- The scan sheet now highlights the client name and separately shows project,
  BDO, Site Incharge, and schedule.
- Unauthorised scanners see a read-only access explanation and only a Close
  button.
- The Start counselling button is rendered only when the server returns
  `canStartCounselling=true`; the server still rechecks on click.
- Staff with standard Site Visit view permissions can open the shared scanner,
  allowing assigned BDO/Site Incharge users to use it without needing a
  separate Front Desk permission.

**Validation:**
- Android `:app:assembleDebug` -> **SUCCESS**.
- MMS `npm run build` -> **SUCCESS** (167 pages/routes).
- Focused QR access test -> **1 passed**:
  unrelated staff can read details but cannot mutate; BDO, Site Incharge,
  admin, and IAM-granted staff receive action access.

**Required admin action:**
- Deploy the pending MMS/Convex source. Until deployment, the configured live
  Convex site can still return HTTP 404 because it is running the older QR
  route/query.

---

### Session 27 - Rich SV QR Client Details and Shared UI Rule

**Date:** 2026-07-28
**Agent:** Codex

**Goal:** Expand the read-only SV QR confirmation sheet with the client details
needed by site staff while preserving the protected counselling action.

**What changed in MMS / shared Convex:**
- The enriched SV lead/client payload now exposes the display mobile number and
  profession.
- Profession resolution prefers the lead's manual profile, then the canonical
  client profile, then the latest linked call analysis.
- Lead temperature resolves only to a real `hot`, `warm`, or `cold` value from
  the canonical lead temperature, AI lead status, or linked call analysis.
- The existing SV attendee list, expected attendee count, and food preferences
  remain the source of truth for visit-party details.
- Extended the focused QR contract test to cover mobile number, profession,
  temperature, attendees, and food preferences.

**What changed in Mconnect:**
- The QR counselling sheet now shows mobile number, occupation, additional
  visitors, food preferences, BDO, Site Incharge, project, and schedule.
- A Hot/Warm/Cold lead badge appears at the top right only when the backend
  provides a canonical temperature; the UI does not invent a fallback status.
- Additional visitor rows show available name, relation, and age, with a
  count-only fallback when only expected headcount was captured.
- The sheet is scroll-safe and continues to use the existing Material button,
  bottom-sheet, semantic color, font, and drawable system.
- Added reusable `SiteVisitQrDetailLabel` and `SiteVisitQrDetailValue` styles.
- Added the repository-level UI rule near the top of this log: future agents
  must reuse existing Mconnect components/styles for buttons, forms,
  dropdowns, dialogs, chips, selectors, and common states.

**Validation:**
- Android clean `:app:assembleDebug` -> **SUCCESS**.
- MMS `npm run build` -> **SUCCESS** (167 routes).
- Focused SV QR contract test -> **1 passed**.

**Required admin action:**
- Deploy the updated MMS/Convex source before expecting the new profession and
  temperature fields in the live mobile QR response.

---

### Session 28 - External Travel Desk Live Proof and Return Pickup Details

**Date:** 2026-07-28
**Agent:** Codex

**Goal:** Keep start/end dashboard proof in the external live-trip progress UI
and give Picked from Site its own action with optional standing details.

**What changed in Travel Desk web:**
- The agency trip detail page keeps start km + start dashboard upload inside
  the Reached client/start step of the `Mark progress` card.
- End km + end dashboard upload remain inside the Dropped step; the final
  button is now labelled `Mark Dropped`.
- Split the previous combined Picked from Site / Dropped action into two real
  lifecycle steps. A trip can no longer be ended directly from On Site through
  either external web UI.
- Picked from Site now accepts optional standing time in whole minutes and a
  binary `Standing with AC` checkbox; unchecked means without AC.
- Applied the same return-pickup form and stage gating to the external driver
  trip modal, not only the agency-admin trip detail page.
- Removed stale driver-modal validation that required a dashboard image. Both
  external roles may continue without an image or km reading; OCR is attempted
  only when an image is supplied, and missing proof stays Pending verification.
- Completed driver summaries show captured standing minutes and AC mode.

**What changed in MMS / shared Convex:**
- Added `travelDeskStandingTimeMinutes` and
  `travelDeskStandingWithAc` to `siteVisits`.
- Extended the protected external `markPickedFromSite` mutation and HTTP route
  to validate and store those fields.
- Standing time is optional, non-negative, and must be a whole number.
- Start/end proof remains deferable. Missing dashboard images or km readings
  keep a completed trip in Pending verification until evidence is supplied.
- Updated lifecycle coverage to verify standing/AC persistence and the current
  complete-now, submit-evidence-later behavior.

**Validation:**
- Travel Desk `npm run build` -> **SUCCESS** (10 pages/routes).
- MMS `npm run build` -> **SUCCESS** (167 routes).
- Focused lifecycle/QR/proof tests -> **11 passed** across 3 test files.

**Required admin action:**
- Deploy MMS/Convex before testing standing time and AC persistence against the
  live Travel Desk portal.

---

### Session 29 - SV QR Redirect and Lead Temperature Badge

**Date:** 2026-07-28
**Agent:** Codex

**Goal:** Open the normal SV overview/outcome screen after counselling starts
from the shared QR scanner and replace the unused Scan label with lead status.

**What changed in Mconnect:**
- Successful `markOnCounselling` now closes the scanner and opens the existing
  `SiteVisitOverviewFragment`, where the authorised staff can record the SV
  outcome.
- The scanned lead temperature is carried into the overview immediately, then
  refreshed from the enriched SV detail response.
- Removed the top-right location icon and `Scan` text from the SV overview.
- The same position now shows `HOT LEAD`, `WARM LEAD`, or `COLD LEAD` using the
  existing semantic status drawables. It stays hidden when no real temperature
  exists.

**Validation:**
- Android `:app:assembleDebug` -> **SUCCESS**.

---

### Session 30 - CP Completion Proof Enforcement

**Date:** 2026-07-28
**Agent:** Codex

**Goal:** Prevent CP visits from reaching Completed without both arrival OTP
verification and the client selfie/proof upload.

**Root cause:**
- `clientPlaceVisits.setOutcome` converted every non-postponed outcome directly
  to `completed` without reading the linked `fieldVisits` proof.
- `fieldVisits.completeVisit` also allowed CP-linked trips to close without
  checking `arrivalVerifiedAt` or `arrivalPhotoStorageId`.
- The CP detail page called any one of photo, OTP, or GPS `Captured`, which hid
  partial and invalid completion states.

**What changed in MMS / shared Convex:**
- Added one shared CP proof rule requiring both `arrivalVerifiedAt` and a
  non-empty `arrivalPhotoStorageId`.
- Enforced it in CP outcome completion, booking conversion, SV conversion,
  financial rejection, and CP-linked field-trip completion.
- Kept postponement non-terminal, so postponing does not require completion
  proof.
- Added `repairUnverifiedCompletionsBatch` for existing unconverted bad rows.
  It restores them to `in_progress`, clears the false terminal outcome/time,
  corrects rollups, and reports converted booking/SV rows for manual review.
- The web detail and list now show `Proof missing` for legacy completed rows
  missing either requirement. Partial proof is labelled `Incomplete proof`;
  only OTP + selfie together is labelled `Captured`.
- Added focused proof-contract coverage.

**Validation:**
- Focused CP completion proof test -> **1 passed**.
- MMS `npm run build` -> **SUCCESS** (167 routes).

**Required admin action:**
- Deploy MMS/Convex.
- Run `marketing/clientPlaceVisits:repairUnverifiedCompletionsBatch` repeatedly
  with `confirm: "REPAIR_UNVERIFIED_CP_COMPLETIONS"` and the returned cursor
  until `isDone` is true. Review rows counted under `manualReview` rather than
  automatically undoing linked bookings or site visits.

---

### Session 31 - Assignment Push Notifications

**Date:** 2026-07-28
**Agent:** Codex

**Goal:** Send Android push notifications when staff or drivers are assigned a
CP visit, SV, land inspection, or Fleet trip.

**Existing infrastructure confirmed:**
- Mconnect already requests `POST_NOTIFICATIONS`, registers/refreshed FCM
  tokens after login, declares `MconnectFirebaseMessagingService`, and handles
  notification taps.
- MMS/Convex already owns the shared FCM/APNS delivery actions. Assignment
  mutations must schedule those actions; do not add a second mobile push stack.

**What changed in MMS / shared Convex:**
- CP assignments now include a canonical entity ID, reference type, CP detail
  action URL, and Home target in the push payload.
- SV create and reassignment notifications now include the SV detail action URL
  and Home target.
- Land inspection assignment notifications now use the dedicated
  `land-inspection-assigned` type and send push as well as the inbox
  notification. This covers both direct assignment and VP review assignment.
- The post-MD inspection workflow notification now also reaches the assigned
  inspectors by push instead of inbox only.
- Internal Fleet staff-driver assignment pushes now include Fleet routing.
- External Travel Desk driver assignment pushes now include string-safe trip
  routing metadata.
- Fleet allocator alerts now include canonical entity/reference fields and the
  Home target.
- Exported the shared push-data builder and added a contract test covering CP,
  SV, inspection, and Fleet assignment payloads.

**What changed in Mconnect:**
- `land-inspection-assigned` and `fleet-trip-assigned` now use the operational
  Visits notification channel instead of the generic fallback channel.
- Assignment pushes target the Home operational screen when opened. Existing
  notification permission and token-registration flows remain unchanged.

**Validation:**
- Assignment payload contract tests -> **5 passed**.
- MMS `npm run build` -> **SUCCESS** (167 routes).
- Android `:app:assembleDebug` -> **SUCCESS**.
- `git diff --check` -> no whitespace errors.

**Required admin action:**
- Deploy MMS/Convex before live assignment pushes can use the new send paths
  and routing payloads. Codex must not deploy Convex.

---

### Session 32 - External Driver No-Login Trip Link

**Date:** 2026-07-28
**Agent:** Codex

**Goal:** Give an externally allocated Fleet driver one WhatsApp-delivered
Travel Desk link that runs the full trip without asking the driver to sign in,
while allowing the external agency admin to open or copy that same link.

**Existing security model reused:**
- Every allocated SV already receives a random 128-bit
  `driverAccessToken`, indexed on `siteVisits`.
- Unassigning rotates that token, so an already shared link immediately stops
  resolving.
- The new flow extends this bearer-token model; it does not create a second
  token or expose an agency session.

**What changed in MMS / shared Convex:**
- Added a token-scoped Travel Desk trip query for allocated external trips.
- Travel Desk lifecycle mutations now accept either the existing authenticated
  agency/driver session or the exact access token belonging to that one trip.
- The access token cannot operate another trip and is rejected for internal or
  own-vehicle work.
- Dashboard proof upload accepts a valid trip token, while still accepting
  existing Travel Desk sessions.
- Allocation accepts the real Travel Desk request origin and returns the exact
  `/driver/trips/{token}` URL.
- External driver push data now carries the same public trip URL.
- Allocation schedules the `driver_trip_assigned` WhatsApp utility template to
  the selected driver's WhatsApp number. If the selected driver differs from
  the vehicle default, it correctly uses the selected driver's phone instead
  of sending to the old default driver.
- The WhatsApp body parameters are driver name, project, visit date, pickup
  time, pickup location, and the Travel Desk trip URL.

**What changed in Travel Desk web:**
- Added public `/driver/trips/[token]`; it is exempt from the login redirect
  and does not render the authenticated sidebar.
- The page supports Reached client -> Start trip -> Reached site -> Picked
  from site -> Dropped.
- Start captures client OTP plus optional dashboard image/start kilometer.
- Return pickup captures optional standing minutes and with/without AC.
- Drop captures optional dashboard image/end kilometer, toll, and beta.
  Missing evidence continues through the existing Pending verification flow.
- Assigned-trip cards and the trip detail page show `Open driver link` and
  `Copy link` for the external agency admin.
- The allocation proxy derives the link origin from the actual Travel Desk
  host, so localhost, preview, and production do not accidentally point to MMS.

**Validation:**
- Driver token + cab lifecycle suites -> **16 passed** across 2 test files.
- Travel Desk `npm run build` -> **SUCCESS** (public driver route included).
- MMS `npm run build` -> **SUCCESS** (167 routes).
- Direct local request to the public driver route -> **HTTP 200**, no login
  redirect.
- `git diff --check` -> no whitespace errors.
- A visual browser screenshot was not completed because the in-app browser
  connection was unavailable; compilation and direct route checks passed.

**Required admin action:**
- Approve/publish the WhatsApp utility template named
  `driver_trip_assigned` with six body parameters in the order documented
  above.
- Deploy MMS/Convex, then deploy Travel Desk. Convex must be deployed first so
  the new public query and lifecycle authorization exist when the page opens.

---

### Session 33 - External Agency Fleet UI Recovery

**Date:** 2026-07-28
**Agent:** Codex

**Goal:** Fix the external agency Trips screen collapsing into a short empty
panel, exposing the blue hero underneath and making the fleet bottom navigation
disappear.

**Root cause:**
- The sticky fleet hero calculates its spacer after window insets are applied.
  `NestedScrollView` could retain that programmatic layout movement as an
  initial scroll offset, clipping the `Todays Trips` title and filters.
- The same non-user scroll was treated as a downward gesture and animated the
  fleet navigation off-screen.
- With no rows, the rounded trips panel used only `wrap_content`, so it ended
  immediately after the empty message instead of covering the viewport.

**What changed in Mconnect:**
- The trips scroll surface now owns initial touch focus and is reset to the top
  while the sticky hero completes its first layout.
- Fleet navigation auto-hide now responds only to an actual touch gesture, not
  header measurement or data refresh movement.
- The rounded trips content panel receives a responsive minimum height based on
  the current viewport and hero spacer, keeping empty tabs full-height across
  device sizes.
- Existing agency trip filters, allocation cards, APIs, and other roles were
  left unchanged.

**Validation:**
- Android `:app:assembleDebug` -> **SUCCESS**.
- Debug APK installed successfully on the connected agency test device.
- Physical-device screenshots verified the complete hero, trip filters,
  pending/completed cards, and five-item fleet navigation.
- `git diff --check` -> no whitespace errors.

---

### Session 34 - External Agency Completion Form Cleanup

**Date:** 2026-07-28
**Agent:** Codex

**Goal:** Simplify the expired-trip completion sheet for external agencies,
make its numeric fields usable with the keyboard open, capture standing
details, and remove the extra-kilometer claim controls from the agency app.

**What changed in Mconnect:**
- External agency sessions no longer see or choose `Fleet type`; the sheet is
  forced to the external fleet form using the authenticated principal.
- Added optional standing time in whole minutes and a `Standing with AC`
  checkbox. Unchecked means without AC, and the checkbox stays disabled until
  standing time is entered.
- Package price remains the only required amount. Distance and standing details
  remain optional.
- Added IME next/done actions, `adjustResize`, and focus-aware sheet scrolling
  so the active field remains visible above the numeric keyboard.
- Removed the extra-kilometer claim section from Android agency trip cards.
  Existing backend/web claim and Transport Manager approval data was not
  deleted.
- Fixed initialization ordering so hiding the fleet selector cannot leave all
  form bodies hidden.

**What changed in MMS / shared Convex:**
- The Travel Desk expired-trip completion endpoint now accepts, validates, and
  stores `standingTimeMinutes` and `standingWithAc`.
- Standing time must be a non-negative whole number; without a checked AC box,
  it is stored as without AC.

**Validation:**
- Android `:app:assembleDebug` -> **SUCCESS** after the final visibility fix.
- MMS `npm run build` -> **SUCCESS** (167 routes).
- Connected agency-device check confirmed the extra-kilometer section is gone.
- The device disconnected before the final APK could be reinstalled for a
  second form screenshot; the final form/resource state is compile-verified.
- `git diff --check` -> no whitespace errors in both repositories.

**Required admin action:**
- Deploy MMS/Convex before standing details submitted by the new app form can
  persist in production. Codex did not deploy Convex.

---

### Session 35 - Non-Expiring Fleet In-Progress Lifecycle

**Date:** 2026-07-28
**Agent:** Codex

**Goal:** Remove fleet trip expiry, add an In Progress tab across fleet
surfaces, separate Ongoing work from Pending proof/details, and ensure fleet
recovery never opens or changes the Site Incharge SV outcome.

**Lifecycle contract:**
- `Assigned`: allocated, but no fleet lifecycle action has started.
- `In Progress / Ongoing`: reached, started, on-site, or return pickup has
  started and the fleet task is not complete.
- `In Progress / Pending`: an older untouched allocation or a trip missing
  required start/end dashboard proof or kilometre readings.
- `Completed`: the trip has ended and, when proof is required, both dashboard
  images and both kilometre readings exist.
- Trip age no longer creates an Expired terminal state.

**What changed in MMS / shared Convex:**
- Added the `in_progress` assigned subtab and server-derived
  `fleetProgressState` (`assigned`, `ongoing`, `pending`, `completed`).
- Completed lists now use only `isTravelDeskTaskComplete`; old untouched trips
  stay recoverable under In Progress / Pending.
- The compatibility `markExpiredTripOutcomePending` mutation now saves
  internal fleet details only. It requires start/end km and dashboard images,
  records completion/proof timestamps, pricing, standing minutes and AC mode,
  and explicitly does not set an SV outcome-opening state.
- MMS mobile dispatch now returns Assigned, In Progress, and Completed rows.
- Removed the external Travel Desk mutation's expiry gate and outcome-opening
  `completedOffline` behavior.
- No Convex deployment was performed.

**What changed in MMS web:**
- Added `In progress` between Assigned and Complete.
- Added `Ongoing` and `Pending` tags.
- Internal In Progress rows expose Complete; external rows do not.
- The Complete form has no fleet type selector and captures package price,
  completion date/time, start/end km, start/end dashboard images, standing
  minutes, with/without AC, beta, and toll.
- Saving the form updates only trip details and never opens the SV outcome.

**What changed in Travel Desk web:**
- Disabled the Expired classification.
- Added the In Progress tab and Ongoing/Pending tags.
- Only proof-complete trips enter Complete.

**What changed in Mconnect:**
- Added the fourth In Progress tab to the fleet trips screen.
- Removed `VisitExpiry` from fleet-admin bucketing and uses the API progress
  state/proof status instead.
- Removed the duplicate date-expiry bucketing from the legacy internal fleet
  cards in `HomeFragment`; only proof-complete rows enter its Completed view.
- Added Ongoing and Pending details card tags.
- Internal Pending details rows expose Complete; external rows do not.
- The internal completion sheet hides fleet type and captures start/end km,
  start/end dashboard images, standing minutes, and AC mode.
- Images upload through the existing `StorageUploader`; the request sends the
  resulting storage IDs.
- Successful fleet detail completion refreshes the list and does not launch
  `CompleteCpVisitBottomSheet`.

**Validation:**
- Mconnect `:app:compileDebugKotlin --offline` -> **SUCCESS**.
- Mconnect `:app:assembleDebug --offline` -> **SUCCESS**.
- Travel Desk `npx tsc --noEmit --pretty false` -> **SUCCESS**.
- MMS fleet frontend targeted ESLint -> **SUCCESS**.
- `convex/travelDeskProof.test.ts` -> **3 tests passed**.
- Full MMS TypeScript reaches one pre-existing unrelated error in
  `convex/lib/attendanceMobilePunchEdit.test.ts:124` (`totalMinutes` missing);
  no fleet type errors remain.

**Required admin action:**
- Deploy MMS/Convex before testing the new API classification and proof save.
- Then deploy Travel Desk and ship the updated Android APK.

---

### Session 36 - External Agency Proof Completion Ownership

**Date:** 2026-07-28
**Agent:** Codex

**Correction to Session 35:** The internal-only completion rule applies to the
MMS internal admin: they must not fill an external agency's trip details. The
external agency admin must be able to complete its own trip from Travel Desk
web or the external-agency Mconnect session.

**What changed:**
- External Travel Desk completion now requires start dashboard image, start
  kilometer, end dashboard image, and end kilometer.
- External web and Android forms also capture package price, completion
  date/time where available, standing minutes, with/without AC, beta, and toll.
- External proof uploads use the existing agency-authenticated Travel Desk
  upload route. Internal uploads continue to use the existing MMS
  `StorageUploader`.
- The external Convex mutation validates agency ownership, validates both
  odometer readings and both image IDs, stores the proof, and marks the Travel
  Desk task ended without opening or changing the SV outcome.
- An external trip remains In Progress / Pending for both external and internal
  admins until the external agency submits complete evidence.
- MMS internal admins still cannot open Complete for an external agency row.
- External agency admins can open Complete for their own Pending row on both
  app and web.

**Validation:**
- Mconnect `:app:assembleDebug --offline` -> **SUCCESS**.

---

### Session 39 - MMS Fleet Tab Order

**Date:** 2026-07-28
**Agent:** Codex

**What changed:**
- Reordered the MMS Fleet tabs to follow the trip lifecycle:
  `Assigned` -> `In progress` -> `Complete`.
- Filtering and the default `Assigned` selection remain unchanged.

**Validation:**
- Targeted ESLint for `features/fleet/tabs/assigned-tab.tsx` -> **SUCCESS**.
- `git diff --check` for the touched web file -> no whitespace errors.

---

### Session 40 - Read-only SV QR Lifecycle Details

**Date:** 2026-07-28
**Agent:** Codex

**What changed:**
- The mobile SV QR model now consumes the existing `status`, `outcome`, and
  outcome `notes` returned by the MMS scan endpoint.
- An `on_counselling` scan shows an `ONGOING` status and visit details with no
  action buttons.
- A completed scan shows `COMPLETED`, the human-readable outcome, and outcome
  notes when present, with no action buttons.
- Scheduled visits preserve the existing access-controlled Start counselling
  action. Scanning remains read-only; status changes only after confirmation.

**Validation:**
- Mconnect `:app:assembleDebug --offline` -> **SUCCESS**.
- `git diff --check` for all touched Android files -> no whitespace errors.

---

### Session 41 - Fleet Start OTP Compatibility

**Date:** 2026-07-28
**Agent:** Codex

**What changed:**
- External fleet trip start now accepts the original OTP configured through
  `TRAVEL_DESK_CLIENT_OTP`; it falls back to the existing `0000` code when no
  value is configured.
- Fleet also accepts the CP-style `1111` bypass when
  `DEV_OTP_BYPASS=true`. The bypass is not enabled as an unconditional
  production master code.
- Updated the Travel Desk agency panel, unauthenticated driver trip page, and
  Mconnect fleet capture sheet to identify `1111` as the bypass code.
- No login, CP verification, or unrelated OTP behavior was changed.

**Validation:**
- `siteVisitCabLifecycleOverride.test.ts` -> **10 tests passed**, including
  configured-original and `1111` bypass coverage.
- Travel Desk `npx tsc --noEmit --pretty false` -> **SUCCESS**.
- Mconnect `:app:assembleDebug --offline` -> **SUCCESS**.
- Targeted Travel Desk ESLint still reports only the existing
  `react-hooks/set-state-in-effect` findings in the two edited screens.
- `git diff --check` -> no whitespace errors in the touched files.

**Required admin action:**
- Set `TRAVEL_DESK_CLIENT_OTP` when a custom original fleet OTP is required.
- Keep `DEV_OTP_BYPASS=true` only in environments where the `1111` QA bypass
  is intentionally allowed, then deploy MMS/Convex and the Travel Desk UI.
- Travel Desk `npx tsc --noEmit --pretty false` -> **SUCCESS**.
- Full MMS TypeScript still reports only the pre-existing unrelated
  `attendanceMobilePunchEdit.test.ts:124` `totalMinutes` type error.

**Required admin action:**
- Deploy MMS/Convex first, then Travel Desk, then distribute the updated APK.

---

### Session 37 - Assigned Fleet Timeout Fix

**Date:** 2026-07-28
**Agent:** Codex

**Symptom:** In the external agency app, Pending and fleet summary data loaded
but the Assigned tab displayed "No internet connection."

**Root cause:**
- The external Assigned Convex query scanned up to 400 rows for each of seven
  statuses (up to 2,800 historical SV rows), retained 400, then enriched every
  row with several additional database reads.
- The MMS mobile Assigned route separately ran the full shared query once for
  Assigned, once for In Progress, and once for Completed.
- Both paths could cross the Android client's 30-second read timeout. The app
  mislabeled every socket timeout as no internet even though the host was
  reachable and other endpoints were healthy.

**What changed:**
- External agency Assigned now reads the newest 80 rows per visible status,
  includes `on_counselling`, sorts them, and enriches at most 160 recent rows.
- The MMS mobile dispatch route now requests one `all` result from the shared
  query instead of executing three full scans. Each returned row still carries
  `fleetProgressState`, so Android can bucket it locally.
- Android's Travel Desk read timeout is 60 seconds as a fallback for slower
  mobile networks.
- A socket timeout now says "Assigned trips took too long to load. Pull to
  retry." `No internet` is reserved for DNS and connection failures.

**Validation:**
- Mconnect `:app:assembleDebug --offline` -> **SUCCESS**.
- Travel Desk `npx tsc --noEmit --pretty false` -> **SUCCESS**.
- `convex/travelDeskProof.test.ts` -> **3 tests passed**.
- `git diff --check` -> no whitespace errors in the touched files.

**Required admin action:**
- Deploy MMS/Convex for the query optimization to affect live Assigned data.
- Then install the new Android APK for the corrected timeout handling.

---

### Session 38 - Duplicate Expired Badge Cleanup

**Date:** 2026-07-28
**Agent:** Codex

**Symptom:** Completed external-fleet cards displayed `Expired` twice: once in
the top-right trip status badge and again in the compact progress row.

**What changed:**
- Kept the top-right `Expired` badge as the single trip status indicator.
- The compact lower badge is now reserved for actual trip progress and is
  hidden for expired rows.
- Visibility is reset while binding each card so recycled rows render
  correctly.

**Validation:**
- Mconnect `:app:assembleDebug --offline` -> **SUCCESS**.

---

### Session 42 - External Agency Staff Website Access

**Date:** 2026-07-28
**Agent:** Codex

**What changed:**
- A mobile number explicitly added by an external agency admin as agency staff
  now logs in to Travel Desk as `agency_staff`, even when the same number also
  exists in the agency's driver list.
- Staff lookup supports both normalized numbers and legacy formatted numbers,
  so existing staff records continue to authenticate.
- Logged-in agency staff can use the existing Travel Desk website screens to
  add, edit, and view their agency's drivers and vehicles, view and manage
  trips, and assign or unassign agency vehicles and drivers.
- Vehicle creation and updates are restricted to the authenticated staff
  member's own external agency. Cross-agency vehicle access is rejected.
- Agency staff and settings administration remain restricted to the external
  agency admin.
- The login error now tells unregistered users to ask the agency admin to add
  their mobile number as staff or a driver.
- The Travel Desk login page now explicitly identifies external agency staff
  as supported users of their agency-registered mobile number.

**Validation:**
- New end-to-end agency staff access test -> **PASSED**.
- Travel Desk focused suite -> **4 tests passed**.
- Travel Desk `npx tsc --noEmit --pretty false` -> **SUCCESS**.
- MMS `git diff --check` -> no whitespace errors.

**Required admin action:**
- Deploy MMS/Convex for the updated phone authentication precedence and agency
  ownership enforcement to take effect on the Travel Desk website.

---

### Session 43 - Fleet Source Cascade And External Driver WhatsApp

**Date:** 2026-07-28
**Agent:** Codex

**What changed:**
- The MMS Fleet assignment dialog now starts with `External` and `Internal`
  choices instead of `Travel agency` and `MFPL`.
- External keeps the existing travel-agency allotment flow. The agency assigns
  its driver and vehicle later in Travel Desk.
- Internal now requires a company, then shows only that company's active
  branches, then shows only active vehicles owned by the selected branch.
- Changing company or branch clears stale branch and vehicle selections.
- When an external agency allocates a driver and vehicle in Travel Desk, the
  driver receives the approved `sv_driver_trip_allotted` WhatsApp template.
- The WhatsApp body contains driver, agency, vehicle, client, project, visit
  schedule, pickup, and site contact details. The URL button receives only the
  secure unauthenticated trip token as its dynamic suffix.
- The WhatsApp call uses `https://api-whatsapp.theairix.com/api/v1/messages`,
  the configured Airix account, bearer API key, and an assignment-revision
  idempotency key.
- Internal MMS vehicle assignments do not invoke this driver-link WhatsApp
  template.

**Validation:**
- Fleet assignment dialog component suite -> **7 tests passed**.
- WhatsApp, external agency access, and trip-proof suites -> **7 tests passed**.
- Targeted ESLint for the dialog and WhatsApp helpers -> **SUCCESS**.
- Full MMS TypeScript reaches only the pre-existing unrelated
  `attendanceMobilePunchEdit.test.ts:124` `totalMinutes` type error.
- `git diff --check` -> no whitespace errors.

**Required admin action:**
- Ensure the Meta template `sv_driver_trip_allotted` is approved with 11 body
  variables and URL button index `0`.
- Set `WHATSAPP_API_KEY` (or `AIRIX_WHATSAPP_TOKEN`) and
  `TRAVEL_DESK_PUBLIC_URL`, then deploy MMS/Convex.

---

### Session 44 - CP Map Loading Performance

**Date:** 2026-07-28
**Agent:** Codex

**What changed:**
- The shared MMS CP address component now starts Google geocoding and the
  same-origin map-service lookup in parallel and uses the first valid result.
- Removed the duplicate mount-time geocoder probe and the unused Google Places
  library load from this flow.
- Reduced address-input debounce from 700 ms to 350 ms.
- Added short client-side request limits so an unavailable provider cannot
  hold the map preview for many seconds before fallback begins.
- Reduced the map-search proxy upstream timeout from 15 seconds to 4 seconds.
- Added five-minute shared caching with stale-while-revalidate for repeated
  address searches.

**Validation:**
- Live local address-search request returned one result in 1.825 seconds on
  the first request and 143 ms on the cached repeat.
- TypeScript transpile syntax checks passed for the component and API route.
- Targeted ESLint reported only two pre-existing state-in-effect findings in
  the pin-dialog reset/search effects; no new lint failures were introduced.
- `git diff --check` -> no whitespace errors.
- The authenticated CP dialog could not be browser-clicked because the
  connected in-app browser session was unavailable.

**Required admin action:**
- Deploy the MMS website so the faster shared CP map flow is available in
  create and edit forms.

---

### Session 45 - Direct Internal Fleet Completion Form

**Date:** 2026-07-28
**Agent:** Codex

**What changed:**
- Removed the intermediate `Complete fleet details?` confirmation dialog from
  the MMS Fleet in-progress completion action.
- Clicking the internal completion action now opens the existing trip-details
  form directly.
- The existing behavior remains unchanged: saving updates only the internal
  trip record and does not open or modify the Site Incharge outcome form.

**Validation:**
- Confirmed the removed prompt state, handlers, and dialog have no remaining
  references.
- Targeted ESLint for the Fleet assigned tab and controller -> **SUCCESS**.
- `git diff --check` -> no whitespace errors.

---

### Session 46 - Optional Fleet Photos And Calculated Trip Distance

**Date:** 2026-07-28
**Agent:** Codex

**What changed:**
- Dashboard images are now optional in the internal MMS completion form, the
  external Travel Desk completion form, and the Android admin completion sheet.
- Dashboard images are also optional during the live internal/external driver
  start and end actions; choosing an image still uploads and preserves it.
- Start and end kilometre readings remain required and are validated so the
  end reading cannot be lower than the start reading.
- Each completion form now shows a read-only `Total km travelled` field,
  calculated as `end km - start km`.
- The calculated distance is submitted with the trip details and the trip
  outcome summaries now display `Total km travelled`.
- Deferred fleet verification now depends on the required odometer readings;
  missing optional dashboard images no longer keep a trip in progress.
- Existing uploaded dashboard images are preserved and new images are still
  uploaded when the user chooses them.

**Validation:**
- MMS targeted ESLint -> **SUCCESS**.
- Travel Desk proof-state and cab-lifecycle suites -> **13 tests passed**.
- Travel Desk edited files -> TypeScript syntax transpilation passed.
- Travel Desk targeted ESLint reached only four pre-existing
  `react-hooks/set-state-in-effect` findings.
- Mconnect `:app:assembleDebug` -> **SUCCESS** using Android Studio's bundled
  JDK.
- `git diff --check` -> no whitespace errors in all three repositories.

**Required admin action:**
- Deploy MMS/Convex and the Travel Desk website together so the optional-photo
  contract and client forms stay synchronized.

---

### Session 47 - Outcome-Gated Fleet Completion

**Date:** 2026-07-28
**Agent:** Codex

**What changed:**
- Removed the Agency name field from the Android external-agency trip
  completion sheet. The logged-in agency remains the source of ownership.
- Fleet completion is now unlocked only after the Site Visit outcome has been
  recorded, for both internal and external fleets.
- Trips without an SV outcome remain in Assigned or In progress according to
  their real Travel Desk timestamps. Opening the card continues to show the
  live trip status and available progress actions.
- An outcome-recorded trip with missing fleet details is shown as
  `Pending details` and exposes the Complete action to the responsible fleet.
- A trip moves to Completed only when both the SV outcome and the required
  Travel Desk trip details are complete.
- Added the same lifecycle classification to MMS, the Travel Desk website,
  and the Android app, with server-side outcome guards in Convex so clients
  cannot bypass the rule with a direct completion request.

**Validation:**
- Mconnect `:app:assembleDebug` -> **SUCCESS**.
- MMS fleet, Travel Desk proof, and cab lifecycle suites ->
  **29 tests passed**.
- Travel Desk edited files -> TypeScript syntax transpilation passed.
- Confirmed the external Android completion sheet has no Agency name field or
  stale binding references.
- `git diff --check` -> no whitespace errors in all three repositories.

**Required admin action:**
- Deploy MMS/Convex and the Travel Desk website together, then distribute the
  rebuilt Mconnect app so every client uses the same outcome-gated lifecycle.

---

### Session 48 - Unified Fleet Tab Lifecycle

**Date:** 2026-07-28
**Agent:** Codex

**Canonical tab rules:**
- `Assigned`: a vehicle or external agency is assigned, but no trip progress
  timestamp exists and no SV outcome has been recorded.
- `In progress / Ongoing`: at least one Travel Desk progress timestamp exists,
  while the SV outcome has not yet been recorded.
- `In progress / Pending details`: the SV outcome is recorded, but the required
  fleet trip details are not complete. This applies even when the fleet did not
  update any trip status.
- `Complete`: the SV outcome is recorded and the Travel Desk task is fully
  complete. An SV `completed` status alone must never place a fleet trip here.

**What changed:**
- Added the missing In progress tab to the internal fleet dispatcher on the
  Mconnect home screen while retaining the existing four-tab external-agency
  fleet screen.
- Removed the remaining timestamp-only and expired-date completion
  classification from the Mconnect home dispatcher.
- Updated Mconnect, MMS, and Travel Desk labels to show `Ongoing` or
  `Pending details` in the In progress tab.
- Fixed MMS and external Travel Desk queries so an outcome-recorded visit with
  no fleet timestamps remains visible as Pending details instead of being
  excluded because the SV status changed to `completed`.
- Prevented the Travel Desk website from treating the business SV status as
  proof that the fleet trip itself is complete.
- Internal admins no longer receive the external-agency completion action;
  external trip details remain the responsibility of the assigned agency.
- Saving internal fleet details no longer reopens the SV outcome form because
  the outcome is now a prerequisite.

**Validation:**
- Mconnect `:app:assembleDebug` -> **SUCCESS**.
- MMS fleet, Travel Desk proof, and cab lifecycle suites ->
  **30 tests passed**, including the new no-progress/outcome-recorded case.
- Travel Desk edited files -> TypeScript syntax transpilation passed.
- `git diff --check` -> no whitespace errors in all three repositories.

**Required admin action:**
- Deploy MMS/Convex and Travel Desk together, then distribute the rebuilt
  Mconnect app so every surface uses the same tab lifecycle.

---

### Session 49 - External Driver Trip Link And Public Trip Page

**Date:** 2026-07-28
**Agent:** Codex

**Repositories:**
- Travel Desk: `C:\Users\surya\Projects\travel-desk` (`aizen`)
- MMS / Convex backend: `C:\Users\surya\Projects\manjusitedevelopment` (`max`)

**What changed:**
- External trip allocation now uses the configured `TRAVEL_DESK_PUBLIC_URL`,
  with `https://traveldesk.aivida.in` as the production fallback, when
  generating the driver's secure trip link.
- Added a compatibility redirect for legacy links that reach the MMS domain at
  `/driver/trips/<token>`. Valid links are redirected to
  `https://traveldesk.aivida.in/driver/trips/<token>`.
- Polished the public driver trip page with a responsive six-stage progress
  view, clearer assignment details, 12-hour pickup time, status guidance,
  clickable client phone number, and stronger completed/waiting states.
- The driver can begin the trip at any time on the scheduled calendar date.
  The scheduled pickup time is informational and does not block starting.
- Added a regression test proving a trip scheduled for `23:59` can still be
  operated earlier on the same India date.

**WhatsApp template contract:**
- The backend sends only the secure token as the dynamic URL-button parameter
  for the `sv_driver_trip_allotted` template.
- The approved WhatsApp template must therefore use this fixed button URL:
  `https://traveldesk.aivida.in/driver/trips/{{1}}`.
- If the template is configured with `manjugroups.com`, an administrator must
  update and re-approve that template URL. Application code cannot replace a
  fixed URL prefix stored in Meta's approved template.

**Validation:**
- Travel Desk production build -> **SUCCESS**.
- Travel Desk targeted ESLint -> **SUCCESS**.
- Driver assignment and WhatsApp helper tests -> **5 tests passed**.
- New MMS route and regression test targeted ESLint -> **SUCCESS**.
- `git diff --check` -> no whitespace errors in either repository.
- MMS full TypeScript check remains blocked by the pre-existing unrelated
  `attendanceMobilePunchEdit.test.ts` `totalMinutes` type error.
- Local visual browser attachment failed before connecting to the running
  preview; the page was validated through build, lint, and source inspection.

**Required admin action:**
- Deploy MMS/Convex and Travel Desk together.

---

### Session 57 - External Driver WhatsApp Delivery Recovery

**Date:** 2026-07-29
**Agent:** Codex

**Repositories:**
- MMS web and Convex backend:
  `C:\Users\surya\Projects\manjusitedevelopment` (`max`)
- Travel Desk web:
  `C:\Users\surya\Projects\travel-desk` (`aizen`)

**Root cause:**
- External trip allocation did not consistently use the selected roster
  driver's saved WhatsApp number. It could fall back to the ordinary mobile
  number even when a separate WhatsApp number existed.
- WhatsApp dispatch ran asynchronously and swallowed provider/configuration
  failures, so the allocation appeared successful with no delivery state or
  recovery action.

**What changed:**
- External allocation now selects the recipient in this order: selected
  driver's saved WhatsApp, matching vehicle default-driver WhatsApp, then
  driver mobile.
- Driver message delivery is stored on the SV as Pending, Sent, Failed, or
  Not sent, including recipient, provider message ID, failure detail, attempt
  time, and accepted time.
- Provider sends retry up to three times with the same idempotency key.
- Delivery updates are assignment-revision guarded so a late response from an
  old assignment cannot overwrite the current driver's state.
- Added an authenticated resend endpoint and a `Resend WhatsApp` action on the
  Travel Desk trip detail page. Manual resend uses a fresh idempotency key and
  keeps the existing secure driver-trip URL.
- The operator UI shows the recipient and delivery/error state instead of
  silently treating allocation as message delivery.

**Validation:**
- Public driver trip URL returned HTTP 200 before the change.
- MMS/Convex production build with TypeScript -> **SUCCESS**.
- Travel Desk production build with TypeScript -> **SUCCESS**.
- WhatsApp template helper tests -> **3 passed**.
- `git diff --check` -> no whitespace errors.

**Required admin action:**
- Deploy MMS/Convex and Travel Desk together.
- Confirm `WHATSAPP_API_KEY` (or `AIRIX_WHATSAPP_TOKEN`) is configured in the
  production Convex environment.
- Open the assigned trip in Travel Desk and use `Resend WhatsApp` for any
  assignment that was created before this deployment.

---

### Session 58 - Dropped Trip Billing Finalization

**Date:** 2026-07-29
**Agent:** Codex

**Repositories:**
- MMS web and Convex backend:
  `C:\Users\surya\Projects\manjusitedevelopment` (`max`)
- Travel Desk web:
  `C:\Users\surya\Projects\travel-desk` (`aizen`)

**What changed:**
- Separated operational `Dropped` state from external-agency billing
  completion.
- A dropped external trip now stays in In progress with a `Pending details`
  tag and a `Complete` action until agency billing is finalized.
- The billing form requires start and end odometer readings, calculates total
  kilometers as end minus start, and includes package/per-km base pricing,
  toll, beta, standing, hill, outstation, permit, and permit-tax charges.
- The form calculates and previews the final billing total before submission.
- Existing start/end dashboard images are previewed and open full-size.
  Replacement or missing image uploads remain optional.
- The driver and agency end-trip screens now show the recorded start
  kilometer and start dashboard image while end details are being entered.
- Missing required kilometer readings are identified explicitly. Only a
  dropped trip with valid readings and finalized agency billing moves to the
  Completed tab.
- Added one authenticated `finalize-billing` API shared by Travel Desk and the
  Convex fleet state.

**Validation:**
- Travel Desk production build with TypeScript -> **SUCCESS**.
- MMS/Convex production build with TypeScript -> **SUCCESS**.
- Travel Desk proof and completion-state tests -> **6 passed**.

**Required admin action:**
- Deploy MMS/Convex and Travel Desk together.

---

### Session 56 - Driver Link Gallery And Camera Proof

**Date:** 2026-07-29
**Agent:** Codex

**Repository:**
- Travel Desk web:
  `C:\Users\surya\Projects\travel-desk` (`aizen`)

**What changed:**
- Replaced the single camera-oriented file input on the public driver trip
  link with explicit `Gallery / files` and `Camera` controls.
- Applied the control to both start-dashboard and end-dashboard proof.
- The selected image filename is shown below the controls.
- Native Mconnect driver completion already provides `Take photo` and
  `Choose from gallery`, so no Android change was required for this request.

**Validation:**
- Travel Desk targeted ESLint -> **SUCCESS**.
- Travel Desk production build with TypeScript -> **SUCCESS**.

---

### Session 55 - Odometer Sanity And Incorrect Fare Prevention

**Date:** 2026-07-29
**Agent:** Codex

**Repositories:**
- MMS web and Convex backend:
  `C:\Users\surya\Projects\manjusitedevelopment` (`max`)
- Travel Desk web:
  `C:\Users\surya\Projects\travel-desk` (`aizen`)
- Mconnect Android:
  `C:\Users\surya\Projects\Mconnect` (`merge`)

**Root cause:**
- The subtraction formula was already `end km - start km`.
- The failing row contained start `233453` and end `568878`, which
  mathematically produces `335425 km`.
- Any two non-negative readings were previously accepted as complete, so a
  mistyped odometer value could create and display an impossible fare.

**What changed:**
- Added one authoritative maximum same-trip distance of 5,000 km.
- New internal, external, driver, recovery, and deferred-proof submissions
  reject impossible or reversed odometer readings before saving or pricing.
- Legacy rows with impossible readings are no longer considered complete;
  they return to Pending details so the readings can be corrected.
- Correcting a kilometer-priced legacy trip preserves kilometer pricing and
  recalculates the fare from the corrected distance instead of converting it
  to package pricing.
- MMS and Travel Desk summaries show `Check odometer readings` and suppress
  the invalid kilometer price and total.
- Mconnect internal/external completion, driver end-trip, and trip summaries
  apply the same validation.

**Regression examples:**
- `200050 - 200020 = 30 km` is accepted.
- `568878 - 233453 = 335425 km` is rejected and remains pending correction.

**Validation:**
- Convex proof and fleet lifecycle suites -> **16 tests passed**.
- Focused legacy-invalid-row proof suite -> **5 tests passed**.
- MMS production build with TypeScript, 167 routes -> **SUCCESS**.
- Travel Desk ESLint and production build -> **SUCCESS**.
- Mconnect `:app:assembleDebug --no-daemon` -> **SUCCESS**.

---

### Session 54 - Fleet Backend Release-Readiness Audit

**Date:** 2026-07-28
**Agent:** Codex

**Repositories:**
- MMS web and Convex backend:
  `C:\Users\surya\Projects\manjusitedevelopment` (`max`)
- Travel Desk web:
  `C:\Users\surya\Projects\travel-desk` (`aizen`)
- Mconnect Android:
  `C:\Users\surya\Projects\Mconnect` (`merge`)

**Verified integration contract:**
- MMS internal fleet completion calls
  `/api/mms-fleet/dispatch/complete-offline` with staff bearer auth.
- Travel Desk external completion calls
  `/api/travel-desk/trips/complete-offline` with agency bearer auth.
- Mconnect selects the matching internal or external endpoint and sends the
  same completion payload.
- Both paths persist to the same `siteVisits` fleet fields:
  start/end odometer readings, optional dashboard storage IDs, standing
  details, and optional package/beta/toll/hill/outstation/permit charges.
- Total kilometers is derived as `end km - start km`.
- Start and end kilometer readings are required for final fleet completion;
  dashboard images remain optional.
- Travel Desk, MMS, and Mconnect all target the production HTTP API
  `https://api-mfpl.theairix.com`; MMS uses the paired Convex client endpoint
  `https://convex-mfpl.theairix.com`.
- Agency staff can manage operational fleet records but cannot read or update
  administrator-only charge settings.
- No unresolved merge markers or `git diff --check` errors were found.

**Validation:**
- Travel Desk targeted ESLint -> **SUCCESS**.
- Travel Desk production build with TypeScript -> **SUCCESS**.
- MMS production build with TypeScript, 167 routes -> **SUCCESS**.
- Convex fleet regression suites -> **18 tests passed**.
- Mconnect `:app:assembleDebug --no-daemon` -> **SUCCESS** using Android
  Studio JBR.

**Test-environment notes:**
- WhatsApp sending is skipped in tests when no API key is present.
- `convex-test` logs a scheduled push transaction warning after the test
  transaction closes; all lifecycle assertions pass.

**Live deployment gate:**
- This workstation's Convex CLI credential is malformed/unauthorized, so the
  production environment and deployment could not be inspected or changed.
- Before deployment, an authorized admin must confirm:
  `WHATSAPP_API_KEY` or `AIRIX_WHATSAPP_TOKEN`,
  `TRAVEL_DESK_PUBLIC_URL=https://traveldesk.aivida.in`, the WhatsApp utility
  account ID, and the approved `sv_driver_trip_allotted` template URL button.
- Deployment order: deploy MMS/Convex first, deploy Travel Desk second, then
  distribute the rebuilt Mconnect app. Perform one authenticated internal trip
  and one external driver-link trip smoke test after deployment.

---

### Session 53 - Internal Fleet Dashboard Proof Sources

**Date:** 2026-07-28
**Agent:** Codex

**Repositories:**
- MMS web: `C:\Users\surya\Projects\manjusitedevelopment` (`max`)
- Mconnect Android: `C:\Users\surya\Projects\Mconnect`

**What changed:**
- The MMS internal fleet completion form now presents separate
  `Gallery / files` and `Camera` controls for both start and end dashboard
  images.
- Each MMS proof field shows the selected filename and remains optional, as
  previously requested.
- Confirmed MMS uploads selected files to storage and sends the resulting
  start/end storage IDs with the internal trip completion mutation.
- Confirmed Mconnect internal fleet completion asks separately for start and
  end dashboard images.
- Mconnect presents an explicit `Take photo` or `Choose from gallery` dialog
  for each proof field.
- Confirmed every Mconnect internal completion entry point uploads selected
  images and submits the resulting start/end storage IDs.

**Validation:**
- MMS targeted ESLint -> **SUCCESS**.
- MMS production build -> **SUCCESS**.
- Mconnect `:app:assembleDebug` -> **SUCCESS**.

**Required admin action:**
- Deploy MMS/Convex and distribute the rebuilt Mconnect app.

---

### Session 52 - External Agency Staff Access Boundary

**Date:** 2026-07-28
**Agent:** Codex

**Repositories:**
- Travel Desk: `C:\Users\surya\Projects\travel-desk` (`aizen`)
- MMS / Convex backend: `C:\Users\surya\Projects\manjusitedevelopment` (`max`)

**What changed:**
- External agency staff retain operational access to Drivers, Vehicles, Trips,
  and Staff management.
- Settings is included only in the external agency administrator navigation.
- Direct `/settings` access shows an administrator-only message for staff.
- Staff sessions can list, create, and update agency staff records for their
  own external agency.
- Fleet charge settings remain protected by the administrator-only backend
  session guard for both read and update operations.
- Added access tests proving staff management succeeds while settings read and
  update are rejected.

**Validation:**
- Travel Desk targeted ESLint -> **SUCCESS**.
- Travel Desk production build -> **SUCCESS**.
- Travel Desk agency staff access suite -> **2 tests passed**.

**Required admin action:**
- Deploy MMS/Convex and Travel Desk together.
- Confirm the WhatsApp template URL button is
  `https://traveldesk.aivida.in/driver/trips/{{1}}`.

---

### Session 50 - Inline External Allocation And Fleet Completion Details

**Date:** 2026-07-28
**Agent:** Codex

**Repositories:**
- Travel Desk: `C:\Users\surya\Projects\travel-desk` (`aizen`)
- MMS / Convex backend: `C:\Users\surya\Projects\manjusitedevelopment` (`max`)
- Mconnect Android: `C:\Users\surya\Projects\Mconnect`

**External allocation flow:**
- The allocation form now places Driver before Vehicle and no longer opens
  separate driver or vehicle creation dialogs.
- Driver suggestions show the driver's name, phone, default vehicle number,
  and vehicle model. The dropdown can also be searched by vehicle number or
  model.
- Selecting a roster driver automatically selects the vehicle linked through
  its default driver details. Selecting a vehicle can populate its linked
  roster driver.
- A newly typed driver expands the required phone field inline. A newly typed
  vehicle expands model, model year, and vehicle type inline.
- Allocate atomically creates any missing driver/vehicle, links them as
  defaults, and assigns the trip. A focused Convex test covers this contract.

**Fleet completion flow:**
- Start and end odometer readings are the only required completion fields.
- Total kilometers is always derived as `end km - start km`; dashboard
  odometer values are never added together.
- Package price, dashboard images, standing details, beta, toll, hill charge,
  outstation charge, permit charge, and permit tax are optional.
- Travel Desk and MMS web forms show the calculated total kilometers and
  support file/camera image capture.
- Mconnect supports camera or gallery proof selection and now uploads optional
  dashboard proof from every home/admin completion path.
- Trip summaries show client name, total kilometers, and the additional charge
  breakdown where applicable.

**Validation:**
- Travel Desk targeted ESLint -> **SUCCESS**.
- Travel Desk production build -> **SUCCESS**.
- MMS fleet lifecycle, proof, driver assignment, and agency staff suites ->
  **18 tests passed**.
- Mconnect `:app:assembleDebug` -> **SUCCESS**.
- `git diff --check` -> no whitespace errors in all three repositories.

**Required admin action:**
- Deploy MMS/Convex and Travel Desk together, then distribute the rebuilt
  Mconnect app.

---

### Session 51 - 12-Hour Times And Full Inline Fleet Creation

**Date:** 2026-07-28
**Agent:** Codex

**Repositories:**
- Travel Desk: `C:\Users\surya\Projects\travel-desk` (`aizen`)
- MMS / Convex backend: `C:\Users\surya\Projects\manjusitedevelopment` (`max`)

**What changed:**
- Scheduled and pickup times on Travel Desk trip cards/details now use
  12-hour display with AM/PM. Stored/API time values remain `HH:mm`.
- A new typed driver in the allotment form now exposes the same roster fields:
  mobile, WhatsApp with same-as-mobile option, category, and address.
- Inline allocation forwards those driver details to Convex and stores them
  atomically when the driver is created.
- A new typed vehicle now exposes Brand, Model, model year, vehicle type, and
  derived seating capacity in the allotment form.
- Renamed the misleading standalone `Vehicle model` make field to `Brand`.
- Added one shared searchable Indian vehicle catalog used by both Vehicles and
  allotment forms. Selecting a known model suggests its vehicle type and
  capacity; type remains manually selectable.
- Custom brands and models remain supported through the searchable Add option.

**Validation:**
- Travel Desk targeted ESLint -> **SUCCESS**.
- Travel Desk production build -> **SUCCESS**.
- Travel Desk agency allocation and driver assignment suites ->
  **4 tests passed**.
- `git diff --check` -> no whitespace errors.

**Required admin action:**
- Deploy MMS/Convex and Travel Desk together.
