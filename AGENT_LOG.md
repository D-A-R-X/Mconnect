# AGENT_LOG.md

> **⚠️ LOCAL ONLY — DO NOT COMMIT OR PUSH**
> This file is listed in `.gitignore` and must stay that way.
> It is a running logbook for AI agents (Antigravity / Claude / Gemini, etc.)
> working on this repository so each session picks up exactly where the last left off.

## Mandatory Update Rule

Every AI must update this file on **every response/turn** and after every
meaningful change. This applies even when the response only reports status,
answers a question, performs an investigation, or makes no file changes.

Each turn's entry must state:

- what was requested or investigated;
- every file, behavior, API, schema, UI, configuration, Git state, or document
  changed;
- validation run and its result;
- failures, unresolved risks, deployment requirements, or follow-up work;
- explicitly that no project files changed when the turn was read-only.

Update entries incrementally during long tasks. Before any final response,
verify that the current turn is logged accurately. A task is not complete
until its log entry exists. This file must remain local-only and must never be
committed or pushed.

### Fork / parallel-session rule

This project is sometimes worked on by **multiple concurrent Claude sessions**:
a **main chat** plus one or more **forked chats** (a fork branches off the main
chat and shares the same on-disk repos — Mconnect, manjusitedevelopment,
travel-desk). Both the main chat AND every fork MUST log their changes here,
tagged with which session they are, e.g. `**Session:** fork (branched from main)`
or `**Session:** main`.

Because forks and the main chat edit the **same working trees**, uncommitted
changes can clobber each other (last write wins). Therefore every session must:

- Note in its log entry that it is a fork/main and which files it touched, so the
  other session can see what changed underneath it.
- Before overwriting a shared feature file (e.g. `travel-desk` settings/trip
  pages, `manjusitedevelopment/convex/*`), check `git status` for edits it did
  not make — those may be the other session's in-flight work; do not silently
  revert them.
- Keep one coherent vertical (e.g. a whole backend+web+app feature) inside a
  single session where possible, rather than splitting it across fork + main.

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

### Session 61 - Mandatory Per-Response Agent Logging Rule

**Date:** 2026-07-29
**Agent:** Codex

**Request:**
- Require every AI to update `AGENT_LOG.md` on every response and after every
  change.

**Changes:**
- Added a repository-level Mandatory Agent Log Protocol to `AGENTS.md`.
- Added the same rule near the beginning of `AGENT_LOG.md` so it is visible
  before project and session history.
- The rule covers implementation turns, status-only responses, investigations,
  read-only answers, validation, failures, deployment notes, and no-change
  turns.
- The rule requires incremental updates during long work and a final log check
  before an AI sends its response.
- Reaffirmed that `AGENT_LOG.md` is local-only and must not be committed or
  pushed.

**Validation:**
- Confirmed both instruction files contain the mandatory rule.
- `git diff --check` -> no whitespace errors.

**Project behavior:**
- No application code, API, schema, or runtime behavior changed in this turn.

### Session 60 - Agency-Owned Completed Trip Billing And Proof Review

**Date:** 2026-07-29
**Agent:** Codex

**Repositories updated:**
- MMS / Convex backend: `C:\Users\surya\Projects\manjusitedevelopment`
  (`max`, pulled through `origin/max` at `3e93214b`)
- Mconnect Android: `C:\Users\surya\Projects\Mconnect`
  (`merge`, pulled through `origin/merge` at `b684127`)
- Travel Desk: `C:\Users\surya\Projects\travel-desk` (`aizen`)

**Completed-trip lifecycle:**
- A dropped external trip remains in In progress / Pending details until its
  own agency submits required start and end odometer readings.
- Dashboard images remain optional and can be selected from camera or gallery.
- External billing stores package or kilometer pricing, beta, toll, hill,
  outstation, permit, permit tax, standing duration/AC, and standing charge.
- Total kilometers is derived as `end odometer - start odometer`; the stored
  billing total includes every submitted charge.
- After submission, the same persisted values and proof images appear in the
  Travel Desk Completed view, MMS Complete view, and Mconnect completed detail.

**Completed-record editing and ownership:**
- External completed records are editable only by a logged-in staff/admin
  session belonging to that exact travel agency.
- External edits can correct the vehicle sent, driver name/phone, odometer
  readings, optional proof images, and all billing charges.
- MMS does not expose edit controls for external-agency completed records.
- Internal completed records can be corrected only from the MMS fleet module
  by an authorized internal fleet admin.
- External summaries show the travel agency before driver and odometer data.

**Live trip proof and OTP UI:**
- Once a trip is Picked from CP, Travel Desk shows the recorded start
  kilometer and dashboard image preview in the live progress card.
- The public driver link now displays four single-digit OTP boxes with
  automatic focus movement and paste support.

**Pull/conflict notes:**
- The MMS pull restored an autostash conflict in `convex/whatsappTemplates.ts`.
  It was resolved by retaining both upstream WhatsApp audit logging and the
  external-driver per-trip delivery status/retry tracking.
- Generated report files under `outputs/` are excluded from Next.js type
  checking; they are artifacts, not production application source.

**Validation:**
- Travel Desk production build -> **SUCCESS**.
- MMS production build -> **SUCCESS**.
- MMS focused fleet/WhatsApp tests -> **10 passed**.
- Mconnect `:app:assembleDebug` -> **SUCCESS**.
- `git diff --check` -> no whitespace errors in all three repositories.

**Required admin action:**
- Deploy MMS/Convex and Travel Desk together, then distribute the rebuilt
  Mconnect app. The Convex deployment is required before the new completion
  payload and ownership checks are available in production.

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

### Session 59 - Overdue Trip Lifecycle Availability

**Date:** 2026-07-29
**Agent:** Codex

**Repositories:**
- MMS web and Convex backend:
  `C:\Users\surya\Projects\manjusitedevelopment` (`max`)
- Travel Desk web:
  `C:\Users\surya\Projects\travel-desk` (`aizen`)

**What changed:**
- Replaced the exact-date fleet action gate with an available-from-date rule.
- Future trips remain locked until their scheduled date.
- Same-day trips can start at any time, including before the scheduled pickup
  time.
- Previous-day and older unfinished trips remain actionable so fleet staff can
  continue the MMS lifecycle, backfill missing stages, and complete the trip.
- Applied the same shared backend rule to external Travel Desk drivers and
  internal MMS fleet drivers.
- Updated Travel Desk messaging so overdue trips say `Available now` rather
  than incorrectly waiting for a date that already passed.

**Validation:**
- Assignment-date and proof-state tests -> **10 passed**.
- Travel Desk production build with TypeScript -> **SUCCESS**.
- MMS/Convex production build with TypeScript -> **SUCCESS**.

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

---

### Session 62 - Read Log, Awaiting Task

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)

**Request:**
- User asked to read `AGENT_LOG.md` and then wait for the next task.

**What was done:**
- Read the full `AGENT_LOG.md` (all 2886 lines), the mandatory-logging rule,
  the UI-reuse rule, and the three-repository map.
- Confirmed prior in-flight items from the immediately preceding user message
  are already covered by earlier sessions:
  - 12-hour time entry across MMS, Travel Desk, and Mconnect (Sessions 20/51).
  - Vehicle Model/Model Year mandatory, vehicle number optional, and
    SUV/Sedan/Hatchback capacities per the VP override (Session 14).
  - Camera/FileProvider crash guards on the fleet capture sheets
    (Sessions 7/32).
- Noted many Convex/MMS changes across sessions still require administrator
  deployment before they are live.

**Project behavior:**
- Read-only turn. No project files, APIs, schema, layouts, or runtime behavior
  changed. No builds run, no Git operations performed.

**Next:**
- Awaiting the user's next task.

---

### Session 63 - Beta 2 charge, Unused-App gate, SV-QR outcome button

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)

**Repositories:**
- Travel Desk: `C:\Users\surya\Projects\travel-desk` (`aizen`)
- MMS / Convex: `C:\Users\surya\Projects\manjusitedevelopment` (`max`)
- Mconnect: `C:\Users\surya\Projects\Mconnect` (`merge`)

**1. "Beta 2 (optional)" billing charge (external agency completion).**
- Travel Desk `src/app/trips/[id]/page.tsx`: `beta2` state, prefill, billing-total,
  finalize-billing payload, and a "Beta 2 (optional)" input after Beta.
- Travel Desk `src/lib/travel-desk-api.ts`: `travelDeskBeta2` on the row types and
  `beta2` on the complete-offline + finalize-billing request types.
- MMS `convex/schema.ts`: `travelDeskBeta2` on siteVisits.
- MMS `convex/travelDeskTrips.ts`: `beta2` arg + validation + total + store in BOTH
  `completeExpiredOffline` and `finalizeBilling`. Row exposes it via the existing
  `...visit` spread in `enrichTrip`.
- MMS `convex/http.ts`: `beta2` forwarded on the two Travel Desk routes only
  (complete-offline, finalize-billing); internal mms-fleet route left unchanged.
- Validation: Travel Desk `tsc --noEmit` SUCCESS; MMS `tsc -p convex` SUCCESS.
- **App parity NOT done:** `AdminFleetCompleteOfflineSheet` + `TravelDeskModels` also
  carry `beta`; `beta2` still needs mirroring there (data class, layout `etBeta2`,
  collect, both fragment result→request maps, `CompleteOfflineTripRequest`,
  `TravelDeskTrip` row). Flagged as follow-up.

**2. "Manage app if unused" is now a blocking gate step.**
- `BackgroundPermissionsGateDialog.kt`: added `unusedAppSatisfied` (async-resolved).
  `recheckAndMaybeDismiss` now also requires it; `refreshUnusedAppRow` dismisses once
  satisfied + all granted; `showIfNeeded` now also surfaces the gate when all runtime
  perms are granted but the unused-app restriction is still ON. Users can no longer
  skip past it without disabling it. Row comment updated (was "Non-blocking").

**3. SV-QR: ongoing visits get an outcome-page button for authorised viewers.**
- Root cause of earlier "nothing navigates": `canStartCounselling` is false for
  `on_counselling`, so an already-ongoing visit offered no action.
- MMS `convex/marketing/siteVisits.ts`: extracted `isQrOutcomeAuthorized`
  (BDO / Site Incharge / admin / `scanConsulting`), and `getByQrPayload` now returns
  `canRecordOutcome` (= authorised AND status `on_counselling`). scanQr HTTP route
  passes it through via the existing `visit` spread.
- Mconnect: `ScannedSiteVisit.canRecordOutcome`; `QrScannerFragment` forwards it and
  now listens for a new `RESULT_KEY_OPEN_OUTCOME`, navigating straight to
  `SiteVisitOverviewFragment.forScannedVisit` (no re-mark). `SiteVisitCounsellingConfirmBottomSheet`
  shows a "Go to outcome page" button on ongoing + `canRecordOutcome`.

**Validation:**
- Mconnect `:app:assembleDebug` -> **SUCCESS**.
- MMS `tsc -p convex/tsconfig.json` -> **SUCCESS** (no new errors).
- Physical device install blocked by signature mismatch (device runs a differently
  signed build); changes are compile-verified only.

**Deployment note:**
- `beta2` persistence/total and `canRecordOutcome` require the MMS/Convex deploy
  before they work against the live backend. Codex/Claude do not deploy Convex.

**Still queued (not started this turn):**
- Travel Desk trip-detail + public driver-link web UI: move Open-driver-link /
  Copy-link / Resend-WhatsApp INSIDE the Driver WhatsApp container and hide them when
  completed; client details on top; add "Open in map" and "Call client" (tel:/maps
  links to the device default apps).
- App-side `beta2` parity (item 1 above).

**Read-only note:** also explained the "Driver WhatsApp: Pending" badge
(`travelDeskDriverWhatsAppStatus` not yet sent/failed/skipped → provider/deploy
config pending); no code changed for that question.

---

### Session 64 - Driver-link buttons relocation + availability/driver-default triage

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)

**Repository:** Travel Desk `C:\Users\surya\Projects\travel-desk` (`aizen`).

**Change made:**
- `src/app/trips/[id]/page.tsx`: moved "Open driver link", "Copy link", and
  "Resend WhatsApp" INSIDE the Driver WhatsApp box, and hid them when the trip
  is completed (`travelDeskBillingCompletedAt != null`). The outer action row
  now only carries the expired/started/Reassign/Remove controls.
- Travel Desk `tsc --noEmit` -> **SUCCESS**.

**Investigations (no code change — deploy-gated or already-correct):**
- "Open/Copy link missing on a trip": those buttons still gate on
  `trip.driverAccessToken`; a trip without a driver-link token shows none. Token
  comes from the external allocation flow (backend, deploy-gated).
- Driver-link "This trip will become available on its assigned date" for a
  PAST date: the shared helper `convex/lib/driverTripAssignment.ts`
  `canOperateTripToday` already returns `assignedDate <= today(IST)` (past +
  same-day allowed). The live behavior is prod running the pre-Session-59 rule
  -> requires MMS/Convex deploy. No code change needed.
- "Completion form shows the vehicle's default driver": backend `enrichTrip`
  spreads `...visit`, so the row `driverName`/`driverPhone` is exactly what the
  SV stored at allocation (no vehicle-default fallback). If it reads as the
  default, the allocation stored the vehicle's default driver (Session 50 links
  vehicle->default driver). Needs product clarification on where a distinct
  "assigned driver" should live before changing allocation.

**Still queued:** client details on top (trip detail + driver-link pages),
"Open in map" + "Call client" buttons, and app-side `beta2` parity.

---

### Session 65 - Client-on-top / map / call, app beta2 parity, driver-default triage

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)

**1. Travel Desk client card + Open-in-map + Call client.**
- `src/app/trips/[id]/page.tsx`: added a Client card at the TOP (client name,
  pickup address, "Call client" -> `tel:`, "Open in map" -> Google Maps search
  URL to the device default app).
- `src/app/driver/trips/[token]/page.tsx`: added "Open in map" to the pickup
  address (the client phone was already a `tel:` Call link near the top).
- Travel Desk `tsc --noEmit` -> **SUCCESS**.

**2. App-side `beta2` parity (completes the earlier web+backend beta2).**
- `AdminFleetTripsFragment.AdminTrip`: added `var beta2`.
- `AdminFleetCompleteOfflineSheet`: `CompleteOfflineTripResult.beta2`, prefill
  `etBeta2`, reset list, `parseResult` collect, and both result builds.
- `dialog_admin_fleet_complete_offline.xml`: `etBeta2` input ("Beta 2").
- `TravelDeskModels`: `travelDeskBeta2` on the row + `beta2` on
  `CompleteOfflineTripRequest`.
- Fragment maps: `AdminFleetTripsFragment` (request `beta2 = result.beta2`,
  prefill `beta2 = trip.travelDeskBeta2`) and `HomeFragment` (both prefills).
  End-trip requests (`TravelDeskEndTripRequest`) intentionally left without
  beta2, matching the web.
- Mconnect `:app:assembleDebug` -> **SUCCESS**.

**3. "Completion form shows the vehicle default driver" — NOT a bug.**
- `travelDeskTrips.allocate` stores `args.driverName` (the explicitly selected
  roster driver) and only falls back to `vehicle.defaultDriverName` when no
  driver was selected. The completion form prefills `trip.driverName`, which is
  that stored assigned driver. So when it reads as the default, the trip was
  allocated without a separate driver pick, so the vehicle default IS the
  assigned driver. No code change; would only revisit if the allocate FORM is
  failing to send the selected driver (separate from this completion form).

**Deployment note:** `beta2` persistence/total still require the MMS/Convex
deploy (backend done in Session 63). Travel Desk UI changes are client-only and
live on hot-reload.

**Nothing committed/pushed this turn (Codex also active in these repos).**

---

### Session 66 - Driver-link availability computed client-side

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)

**Symptom:** Public driver-link page (`/driver/trips/[token]`) showed
"This trip will become available on its assigned date" and a disabled action
for a PAST-dated trip (26 Jul, today 29 Jul).

**Diagnosis:** The shared backend rule is already correct — `tripAssignmentDate`
= `scheduledDate`; `canOperateTripToday` and the mutation gate
`assertTripOperationalOnAssignmentDay` both use `scheduledDate <= today(IST)`
(past + same-day allowed). The live lock is the OLDER prod deploy still using an
exact-date rule for the `canOperateToday` flag.

**Change (Travel Desk, no deploy needed for the UI):**
- `src/app/driver/trips/[token]/page.tsx`: compute `availableByDate` client-side
  (`trip.scheduledDate <= todayIst`) and set
  `canOperate = availableByDate || trip.canOperateToday === true`, so a stale
  backend flag can't wrongly lock a past/same-day trip. Future trips stay locked.
- Travel Desk `tsc --noEmit` -> **SUCCESS**.

**Caveat:** If the LIVE prod server-side action gate is also still the old
exact-date rule, the action button will now enable but the mutation could reject
a brand-new action on a past-dated trip until MMS/Convex is deployed. (This trip
already progressed to On-site, so prod has been accepting its actions.)

---

### Session 67 - Start-counselling navigates even if the API call fails

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)

**Symptom:** Tapping "Start counselling" on the SV-QR confirm sheet did not
navigate to the SV outcome page.

**Cause:** `QrScannerFragment.markSiteVisitOnCounselling` navigated only on a
successful `markOnCounselling` response; on any failure (most likely the
markOnCounselling route not yet deployed on the live backend) it toasted the
error and resumed scanning — so it dead-ended.

**Change (Mconnect):**
- `markSiteVisitOnCounselling` now wraps the call in `runCatching`, toasts the
  real reason on failure, and ALWAYS calls `openSiteVisitOutcome(...)` to open
  `SiteVisitOverviewFragment.forScannedVisit`. The overview loads the real SV
  status and unlocks the outcome buttons when eligible (on_site / on_counselling).
  The authorised viewer already passed the server-derived `canStartCounselling`
  gate at scan time, so optimistic navigation is safe.
- Mconnect `:app:assembleDebug` -> **SUCCESS**.

**Note:** Recording the outcome still calls the backend; if the live backend
hasn't advanced the SV to on_counselling (route pending deploy), the outcome
buttons rely on the real status (they unlock from on_site onward).

---

### Session 68 - All-staff role pickers + superadmin QR outcome button

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)

**1. Role pickers show ALL active staff (not just sales/telesales).**
- MMS web `features/marketing/pages/site-visits-list-page.tsx`: `staffSelectItems`
  (BDO / Site Incharge / HOD / AVP / GM / Senior Manager) and `lmoStaffSelectItems`
  (LMO) now filter only on `status === "active"` — dropped the
  `department includes sales/telesales/...` restriction. MMS `tsc` -> SUCCESS.
- Mconnect `CompleteCpVisitBottomSheet.pickSvStaff`: dropped the same
  sales/telesales department filter; the SV role pickers now list every active
  staff member. App `:app:assembleDebug` -> SUCCESS.

**2. Superadmin scan of an ongoing visit now offers the outcome button.**
- The Session-63 backend `canRecordOutcome` flag isn't deployed, so the app got
  `false` and hid the "Go to outcome page" button for superadmins on ongoing
  visits. The scan model `ScannedSiteVisitStaff` only carries `name` (no staff
  id), so BDO/incharge can't be matched client-side — but superadmin /
  scanConsulting can.
- `QrScannerFragment`: `canRecordOutcome = visit.canRecordOutcome ||
  session.hasPermission("marketing.siteVisits.scanConsulting")` (true for
  `isAdmin`). Superadmins + scanConsulting-granted staff now get the button
  immediately; assigned BDO/Site Incharge still resolve via the backend flag
  once MMS/Convex is deployed.
- App build -> SUCCESS.

**Follow-up (same session): mobile booking staff pickers too.**
- `CompleteCpVisitBottomSheet.filterBookingStaff` now returns all active staff
  (removed the role-token match for BDO / AVP / GM / Senior Manager / telecaller),
  matching `pickSvStaff`. So every mobile role picker (SV + booking) lists all
  active staff. App `:app:assembleDebug` -> SUCCESS.

**Follow-up (same session): auto-redirect instead of the outcome button.**
- `SiteVisitCounsellingConfirmBottomSheet`: for an authorised viewer on an
  ongoing visit (`showOutcome`), replaced the "Go to outcome page" button with an
  automatic redirect — the sheet shows the visit details plus a bottom line
  "You will be redirected to the outcome page in Ns" (5s reverse countdown via
  `CountDownTimer`), then fires `RESULT_KEY_OPEN_OUTCOME` and dismisses so
  `QrScannerFragment` opens `SiteVisitOverviewFragment`. Timer cancelled in
  `onDestroyView` if the sheet is dismissed first. The scheduled Start-counselling
  button path is unchanged. App `:app:assembleDebug` -> SUCCESS.

### Session 69 - Auto-redirect crash fix + MMS fleet billing parity

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)

**1. Crash on the 5s auto-redirect (QrScannerFragment).**
- The countdown's `onFinish` fired `setFragmentResult(RESULT_KEY_OPEN_OUTCOME)`
  synchronously, which ran the scanner's result listener while the fragment was
  mid-transition -> `openSiteVisitOutcome` touched `parentFragmentManager` and
  threw `IllegalStateException: Fragment ... not associated with a fragment
  manager`. The old `if (_binding == null) return` guard didn't catch it.
- Fix (`QrScannerFragment.openSiteVisitOutcome`): defer the pop + `showOnce` with
  `root.post { ... }` and re-check `isAdded && _binding != null` before touching
  `parentFragmentManager`. Both outcome routes (countdown + Start-counselling)
  share this function, so both are now crash-safe. App build -> SUCCESS.

**2. MMS fleet completion form now matches the travel-desk portal billing.**
- Requirement: show all the portal's billing details on the MMS fleet
  complete-offline form, and keep ONLY start/end km required.
- `features/fleet/types.ts`: added `travelDeskBeta2?` to `AssignedFleetVisit` and
  `beta2: string` to `RecompleteTripDraft`.
- `use-fleet-assigned-controller.ts`: default/prefill/submit for `beta2`; base
  field is now pricing-mode aware — prefills from `travelDeskKmRate` (km mode) or
  `travelDeskPackageAmount` (package), and submits `kmRate` vs `packageAmount`
  accordingly. Only start/end km are required (unchanged).
- `tabs/assigned-tab.tsx`: base-field label toggles "Per km rate" / "Package
  price" by pricing mode; added a "Beta 2 (optional)" input; added a live
  "Billing total" line; Beta 2 shown in both summary panels.
- `convex/marketing/fleet.ts` `markExpiredTripOutcomePending`: added `beta2` +
  `kmRate` args (validated >= 0), included `beta2` in the total, computed the km
  base from the incoming `kmRate`, and persisted `travelDeskBeta2` /
  `travelDeskKmRate`. Schema already had `travelDeskBeta2`.
- MMS `tsc --noEmit` -> clean (only a pre-existing unrelated test error).
- DEPLOY DEPENDENCY: `beta2`/`kmRate` are new Convex args, so the MMS form must
  not ship to prod ahead of the `max` Convex deploy or old prod would reject the
  extra args. Consistent with the standing "code ahead of prod deploy" pattern.

### Session 70 - Travel-desk driver/vehicle pickers + Packages/Outstation settings

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** travel-desk (`aizen`) + manjusitedevelopment (`max`, deploy-gated)

**1. Removed the "+ Create driver" button (travel-desk `driver/page.tsx`).**
- Drivers are now added inline when allotting one to a vehicle on a trip, so the
  standalone create flow is gone: dropped the header + empty-state create buttons,
  the create `DriverForm`, and the now-unused `creating`/`createDraft`/`createBusy`
  state, `handleCreate`, and the `createTravelDeskDriver` import. Edit / deactivate
  / delete stay. Copy updated to explain the new-driver path.

**2. Driver + vehicle combobox arrow, and vehicle option order.**
- New shared `components/combo-chevron.tsx` — a chevron that rotates up when the
  list is open. Added it as a "confirm and close" button (absolute right, toggles
  `open`) to both `driver-combobox.tsx` and `vehicle-combobox.tsx`, with `pr-10`
  on the inputs so text clears the button. Lets the user dismiss the dropdown so
  it stops covering the fields below.
- `vehicle-combobox.tsx` option rows now show the **driver name first** (bold top
  line), then `vehicleNumber · model · type` on the muted second line.

**3. Settings: Packages + Outstation tabs (per user answers).**
- Decision: two independently-saved rate profiles, NO per-trip selector yet;
  reuse the existing "Cancellation allowance" (only **Hill charge** is new).
- Backend (deploy-gated, MMS convex): `schema.ts` `travelDeskAgencySettings` gains
  `hillCharge` + a nested `outstation` object (same fields). `travelDeskSettings.ts`
  `get`/`update` rewritten with a `resolveProfile` helper — Packages stays on the
  flat top-level fields (backward compatible), Outstation nested. `http.ts`
  settings/update route forwards `hillCharge` + the full `outstation` object.
- Frontend (`travel-desk-api.ts`): new `TravelDeskFleetProfile` (adds `hillCharge`);
  `TravelDeskSettings = profile & { outstation: profile }`.
- `settings/page.tsx` rewritten: Packages/Outstation tabs sharing one
  `PROFILE_FIELDS` list (Hill charge added), two independent drafts, Save writes
  both. `profileToDraft` is null/partial-safe so an un-deployed prod backend
  (no `outstation`) doesn't crash the page.
- travel-desk `tsc` clean; MMS `tsc` clean (only the pre-existing unrelated
  attendance test error).
- DEPLOY DEPENDENCY: `hillCharge`/`outstation` are new Convex settings args —
  Save will fail on prod until `max` convex is deployed; GET degrades safely.

### Session 71 - Internal fleet MOBILE parity + travel-desk mobile polish

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)

**1. Internal fleet completion parity on mobile (Mconnect app).**
- Mirrored the Session-69 web recomplete changes onto the app's
  `AdminFleetCompleteOfflineSheet` (internal fleet = fleet-type index 0):
  - **Per km rate label**: when `trip.travelDeskPricingMode == "km"`, the internal
    base field relabels to "Per km rate" (new `tvInternalBaseLabel` id), prefills
    from `travelDeskKmRate`, and submits as `kmRate` (packageAmount null); package
    mode unchanged.
  - **Live billing total**: new `tvBillingTotal` — base (km rate × distance for km
    trips, else package) + all optional charges; shows "—" until a km base is
    computable. Recomputes on every amount/odometer change and on fleet-type
    switch. Mirrors the web preview.
- Model plumbing: network trip gains `travelDeskKmRate`; `AdminTrip` gains
  `pricingMode` + `kmRate` (mapped from the trip); `CompleteOfflineTripResult` and
  `CompleteOfflineTripRequest` gain `kmRate`.
- **Backend route bug fix** (`http.ts` `/api/mms-fleet/dispatch/complete-offline`):
  it was silently DROPPING `beta2` and `standingCharge` (app collected them, route
  never forwarded them) — now forwards `beta2`, `standingCharge`, and the new
  `kmRate` to `markExpiredTripOutcomePending`. Deploy-gated.
- App `:app:assembleDebug` -> SUCCESS; MMS `tsc` clean.

**2. Travel-desk mobile polish.**
- Audit finding: the portal is already substantially responsive — the shell has a
  hamburger drawer (`travel-desk-shell.tsx`, `lg:hidden` header + Sidebar), every
  data table has an `lg:hidden` card fallback (driver/staff/vehicle), and most
  forms already use `sm:grid-cols-2`.
- Fixed the two genuinely-cramped grids that forced 2 columns at all widths:
  trip-detail Toll/Beta pair and the trips-list consulting Start date/time pair
  now `grid gap-3 sm:grid-cols-2` (stack on phones). travel-desk `tsc` clean.
- NOTE: did not do a blanket restyle — the pages are auth-gated so a mobile
  viewport pass couldn't be visually verified; asked the user to flag any specific
  screen that still looks wrong.

### Session 72 - Travel-desk blank-screen fix + external driver double-booking

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)

**1. Blank screen on the travel-desk trip page = no error boundary.**
- The App Router had no `error.tsx`/`global-error.tsx`, so any thrown error
  rendered a blank white page in prod. Added both (travel-desk, `aizen`, pushed
  in Session 71 commit 13b1505) — now shows a recoverable panel WITH the error
  message. Also hardened the dashboard image picker (reset input for same-file
  re-select + thumbnail previews).
- Confirmed prod deploys from `aizen` (the trip page doesn't exist on `main`;
  aizen is 12 commits ahead). A teammate promotes aizen → prod, so the fix goes
  live on their next promotion.

**2. Root cause surfaced by the new error boundary:**
  `assertDriverAvailableOnDate` (convex/lib/driverTripAssignment.ts) threw
  "This driver is already assigned to another trip on <date>" during vehicle
  allocation.

**3. Fix (per user): external-agency drivers may be double-booked.**
- The one-trip-per-driver-per-day guard now runs ONLY for the in-house
  (internal) fleet:
  - `travelDeskTrips.ts` allocate: skip unless the caller agency's `kind` is
    "internal".
  - `marketing/fleet.ts` assignVehicle: wrap the guard in
    `isInternalAssignment(visit, assignmentAgency)` — external agencies skip it.
- MMS `tsc` clean. Committed to `max`; had to `git pull --rebase origin max`
  (teammate had pushed df171405) then pushed -> e436b958. Deploy-gated (convex).

### Session 73 - Travel-desk image upload: compression + picker polish

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** travel-desk (`aizen`), commit e8c4da6

- Root cause of flaky uploads: raw multi-MB phone photos posted as-is. Added
  `src/lib/compress-image.ts` — `createImageBitmap` → canvas downscale (max
  1600px) → `toBlob` JPEG q0.7, keeps original if smaller/fails.
- Applied compression on pick to ALL three image pickers:
  - `trips/[id]/page.tsx` `DashboardFilesPicker` (multi, billing) — now
    append-and-compress, thumbnail grid with per-image remove (×), busy state.
  - `driver/trips/[token]/page.tsx` `DashboardImagePicker` (single) — compress,
    preview + remove, busy state.
  - `trips/page.tsx` `DashboardProofPicker` (single, allot) — same.
- UI polish: gallery/camera SVG icons, previews, remove buttons, "Optimising
  image…" state, inputs disabled while compressing, input reset for re-pick.
- travel-desk `tsc` clean. On `aizen` — needs teammate promotion to reach prod.

### Session 74 - Travel-desk "blank after upload" = shell scroll bug (root cause)

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** travel-desk (`aizen`), commit b8cd25e

- REPRODUCED live on localhost:3200 (user was logged in on the in-app browser).
  Verified via JS + screenshots: compression works (99KB→16KB), authenticated
  upload returns 200 + storageId, selecting an image renders a preview with no
  crash. So upload was never broken.
- Real cause of the "blank screen after upload": a LAYOUT bug. `.shell-root`
  uses Tailwind `h-full` (height:100%) but nothing set a height on `html`/`body`,
  so the 100vh shell collapsed to content height and the whole DOCUMENT scrolled
  (~823px). On the short in-app pane the shell scrolled out of view -> blank
  navy screen (content present in DOM but off-screen; `htmlScrollTop` 823,
  `main` top -761). Looked like an upload failure but wasn't.
- Fix (`globals.css`): `html { height:100% }` and `body { height:100%; display:
  flex; flex-direction:column }` so the shell locks to the viewport and only
  `.app-main` scrolls. Verified after fix: `htmlScrollTop` 0, shell fills 742px,
  form fully visible, image attaches with preview + × remove.
- Also finalised the single-image billing picker (removed `multiple`, replace on
  pick, single preview + remove) started in Session 73.
- Added `.claude/launch.json` (travel-desk-dev, port 3000) for preview_start.

### Session 75 - REAL root cause of blank: sr-only file inputs stretched the doc

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** travel-desk (`aizen`), commit 2a81cb4

- Session-74's html/body height fix helped but the blank RECURRED specifically
  when the native file dialog opened. Diagnosed live in the in-app browser
  (user was logged in): `html` scrollHeight was 1565 vs 742 viewport (823px
  overflow == the exact scroll seen). Enumerated offenders → the four `sr-only`
  file inputs (Tailwind `sr-only` = `position:absolute`) inside the picker
  labels. `.app-main` was `position:static`, so those absolute inputs anchored to
  the document's initial containing block at `top:~1564px`, stretching html.
  Opening the native dialog FOCUSES the hidden input → browser scrolls the whole
  document ~823px to reveal it → the 100vh shell scrolls out → blank. (overflow
  hidden alone didn't help — scrollIntoView still sets scrollTop.)
- Fix (`globals.css`): `.app-main { position: relative }` so the sr-only inputs
  anchor to / are clipped by the scroller, not the document. html scrollHeight
  1565 → 750. Also kept html/body height:100% + overflow:hidden as app-shell
  hygiene.
- Verified live via JS + screenshots: after selecting an image the document
  stays at scrollTop 0, shell at top 0, form visible, thumbnail + × remove
  render, no blank, no error panel. `tsc` clean.

### Session 76 - Unified dashboard image field (billing form)

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** travel-desk (`aizen`), commit 8e91e78

- Per user: image should show in the upper box; existing image removable +
  replaceable; once an image is present, hide the duplicate upload buttons.
- New `DashboardImageField` (trips/[id]) replaces the separate EvidencePreview +
  DashboardFilesPicker pair in the billing block. Shows the current image (new
  pick OR saved server photo) in one box; when present shows only Replace /
  Remove (Gallery/Camera hidden); when empty shows the pickers. Compress on pick.
- Remove of a SAVED image sets a `cleared` flag; `handleFinalizeBilling` now
  sends `startPhotoIds`/`endPhotoIds` = uploaded ids | `[]` (cleared) | undefined
  (keep). Backend already clears on explicit empty array (finalizeBilling
  `args.startPhotoIds ?? existing`; http maps `Array.isArray ? map : undefined`),
  so NO backend change needed. Cleared flags reset on trip reload.
- Verified live on localhost:3200 (logged in): new pick renders in the box +
  Replace/Remove, upload buttons hidden; the previously-saved end image shows in
  its box with Replace/Remove ("Saved photo"). `tsc` clean.
- Confirmed the external-agency driver double-booking logic is present on `max`
  (travelDeskTrips + marketing/fleet, deploy-gated) per the user's reminder.

### Session 77 - Proper image UI + in-app lightbox preview

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** travel-desk (`aizen`), commit a592b25

- "Use proper ui": `DashboardImageField` redesigned — consistent `aspect-video`
  box for BOTH empty and filled states (previously empty box was a different
  size), centered placeholder icon, icon'd Gallery/Camera vs Replace/Remove
  buttons, and a "Preview unavailable" fallback via `<img onError>` for broken
  storage URLs (dark-but-valid images still render).
- "Click image for preview, no external site": clicking an image opens an in-app
  lightbox modal (fixed overlay, backdrop/×/Escape to close) using the same
  same-origin src — no new tab, no navigation. Applied to both
  `DashboardImageField` and `EvidencePreview` (the latter previously used
  `<a target="_blank">` which opened the convex.site URL in a new tab — removed).
- Verified live on localhost:3200: boxes aligned/consistent, image opens the
  lightbox, url unchanged, zero `a[target=_blank]` image links, close works.
  `tsc` clean.

### Session 78 - Attendance approval 500 + day/correction dedupe (app)

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** Mconnect (`merge`), commit 2d83ec5

**1. "Marking Present → HTTP 500" fix.**
- `AttendanceHistoryFragment`: `isRequest` was derived from the active tab
  (`(activeTab==2||4) && activeSubTab==1`). A correction request approved from
  SEARCH or the All tab was sent with `isRequest=false`, so the backend
  (`/api/hr/attendance/approve`) ran `staffAttendance.approve` on an
  attendanceRequests id → threw → 500 (route wraps any throw as 500).
- Fix: new `isRequestRecord(r) = isRequestLinked(r) || requestStage != null`,
  and `isRequestRow = isRequestRecord(record)`. Correction requests now always
  route through `attendanceRequests.approve` regardless of view. Backend route
  already on prod → no deploy needed.

**2. "Shandhiya shows as both attendance approval AND time correction" fix.**
- `isRequestLinked` only dropped rows whose OWN requestType was a request; a
  separate plain attendance row for the same staff+date still showed.
- Added `staffDateKey` + `requestStaffDateKeys`; `hrReviewAttendanceRows`,
  `teamApprovalAttendanceRows`, and new `allApprovalAttendanceRows` now exclude
  any attendance row whose `staff|date` has a pending correction request. So a
  day surfaces once (as the correction, actioned on the Requests tab). Badge
  counts follow automatically.
- App `:app:assembleDebug` -> SUCCESS. Pushed to `merge` (both remotes).

### Session 79 - Uploaded dashboard image shows in MMS + internal fleet mobile

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repos:** manjusitedevelopment (`max` 7858f732) + Mconnect (`merge` 7e37bef)

- Verified round-trip already works for storage: travel-desk uploads to Convex
  (`NEXT_PUBLIC_CONVEX_SITE_URL/api/travel-desk/storage/upload`) → storageId →
  `travelDeskStart/EndPhotoIds` on the siteVisit; MMS displays via `getStorageUrl`
  (SummaryPhotoRow) and the app via `AdminFleetTripManageSheet.bindPhoto`
  (`BASE_URL/api/storage/serve`). Same prod Convex → ids resolve everywhere.
- Gap: the COMPLETION forms only showed upload buttons, not the existing image.
  - MMS `DashboardImagePicker` (assigned-tab recomplete form): added
    `existingPhotoId` prop → renders the saved image (getStorageUrl) until a
    replacement is picked; buttons relabel to "Replace"; wired
    `recompleteTarget.travelDeskStart/EndPhotoIds[0]`. `tsc` clean.
  - App `AdminFleetCompleteOfflineSheet`: new `layoutExistingPhotos` row in the
    dialog XML + `bindExistingPhotos` loading `startPhotoId`/`endPhotoId` via
    Coil from the storage serve URL; hidden when none. Build SUCCESS.

### Session 80 - SV follow-up HTTP 500 fix

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** Mconnect (`merge`), commit 961526f

- Root cause: the SV "Follow up" form (CompleteCpVisitBottomSheet SV outcome
  mode, `applySiteVisitOutcomeMode`) submitted via `setSiteVisitOutcome`
  (`outcome=follow_up`). The backend `siteVisits.setOutcome` first runs
  `assertTransition(status, [on_counselling, picked_from_site, dropped])`, and
  the `/setOutcome` http route wraps any throw as HTTP 500 — so a visit still in
  `scheduled`/`on_site` 500s.
- Fix (`persistPostpone`): SV mode now routes the follow-up through the existing
  `postponeSiteVisit` → `/api/marketing/siteVisits/postpone` (`postponeVisit`),
  whose guard allows `scheduled/client_started/picked_up/on_site/on_counselling/
  picked_from_site/dropped` and reschedules to the chosen next date. New
  `persistSvFollowUp` + `displayDateToApiDate` (dd/MM/yyyy → yyyy-MM-dd). CP mode
  path unchanged. Route already on prod → no deploy. Build SUCCESS; pushed to
  `merge` (both remotes).

### Session 81 - Completed trips → Complete tab (ext+int), delete driver, form parity

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repos:** travel-desk (`aizen` f6af243) + manjusitedevelopment (`max` d3502c15)

**1. Completed agency trips now reach the Complete tab (both portals).**
- Root cause (both sides): "completed" required an SV `outcome`, but an external
  agency completes by finalising BILLING (`travelDeskBillingCompletedAt`), which
  doesn't set `outcome`. So agency-completed trips sat in In-progress.
- travel-desk `isCompletedTrip`: true when `travelDeskBillingCompletedAt != null`.
- MMS `fleetProgressState` (convex, deploy-gated): "completed" when
  `travelDeskBillingCompletedAt != null` (only agency finalize sets it, internal
  unaffected) → agency trip shows in the internal Complete tab too.

**2. Delete external driver "not working" — error was hidden.**
- Backend refuses delete while the driver holds an unfinished trip (400), but the
  error rendered in a page banner BEHIND the modal. Added in-modal `deleteError`
  (`openDelete` clears it) so the reason shows in the dialog.

**3. Complete-expired modal parity + polish.**
- Added `Beta 2 (optional)` (backend already accepts it; modal didn't send it) +
  a live Billing total.

**Already correct (verified):** edit-only-by-agency (MMS recomplete throws "only
for internal fleet trips" for external rows; travel-desk is agency-scoped); MMS
shows agency trip details via `SummaryPhotoRow`/billing summary.

**STILL BLOCKED ON DEPLOY:** the "driver already assigned" removal (Session 72 on
`max`) + the fleetProgressState fix are CONVEX changes. The live error persists
only because prod convex isn't deployed; per "never deploy convex" I did not
deploy — needs the `max` convex deploy. travel-desk `tsc` clean; MMS `tsc` clean.

### Session 82 - Collector self-edit of pending collection (backend + app)

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repos:** manjusitedevelopment (`max` 0b9ee9de) + Mconnect (`merge` 01c2da2)

- Problem: a staff entered ₹48,000 instead of ₹4,80,000 and there was no way to
  fix it. Built a self-correction path (allowed until Accounts acts).
- Backend (deploy-gated): `customerCollections.correctByCollector` (amount/date/
  mode/ref/notes; guarded to `collectedByStaffId` + `verificationStatus ==
  pending_accounts`; stamps `collectorEditedAt`; audit event; recalcs case).
  Schema `collectorEditedAt`. `POST /api/postsales/collections/correct` (auth
  user = actor) + CORS allow. `tsc` clean.
- App (`merge`): `CorrectCollectionRequest` + `correctCustomerCollection`;
  `collectorEditedAt` on the row model + `edited` on `CollectionItem` + mapper;
  adapter shows "Edit amount" on own PENDING rows (My Collections is already
  self-scoped) + inline "Edited" tag; fragment amount-edit dialog. Build SUCCESS.

**NOT done this turn (honest status — flagged to user):**
- WEB collection edit UI: not built yet. The web collector views
  (my-work-page / post-sales case detail) render collections read-only; the edit
  affordance + `correctByCollector` call + Edited tag still need wiring there.
- `travel-desk` mobile UX optimisation: not done this turn (separate large task).
- The one-off ₹48,000→₹4,80,000 curl: NOT executed. No valid api-mfpl auth token
  available, and a direct financial mutation on prod is unsafe to guess. Once the
  `max` convex deploys, the collector (IMRAN.A) can fix it in-app via Edit amount.
- DEPLOY-GATED: the whole feature needs the `max` convex deploy to work live.

### Session 83 - Permissions gate sheet responsive on all devices

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** Mconnect (`merge` d2f0914)

- The "Before we get started" gate (`dialog_background_permissions_gate.xml`,
  shown by `BackgroundPermissionsGateDialog`) was a non-scrolling `wrap_content`
  LinearLayout → on short screens the last rows (Auto Start / Manage app if
  unused) clipped off-screen with no way to reach them.
- Fix: wrapped the content in a `NestedScrollView` (fillViewport, overScroll
  never) and, in `onCreateDialog`, set `behavior.maxHeight = 92% * screenHeight`
  so the sheet hugs content on tall screens but scrolls inside on short ones.
  Build SUCCESS; pushed to `merge` (both remotes).

### Session 84 - Unused-app detection resilience + travel-desk mobile verification

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repos:** Mconnect (`merge` a3fa837) + travel-desk (`aizen` 5166f41)

**1. "Manage app if unused" not detecting on-device.**
- `getUnusedAppRestrictionsStatus` returns ERROR/FEATURE_NOT_AVAILABLE on many
  OEMs (Xiaomi/Oppo/Vivo), so the gate hid the row and users couldn't disable
  hibernation. `refreshUnusedAppRow`: on API 30+ (feature exists on all such
  devices) now surfaces the row even when the query can't read the state
  (`known=false`), toggle reflects a CONFIRMED-disabled state, and an unreadable
  state no longer hard-blocks the gate. Build SUCCESS; pushed to `merge`.

**2. travel-desk mobile UX — verified responsive; polished touch targets.**
- Inspected the LIVE logged-in dev server at 375px: nav hamburger drawer works,
  Trips/Drivers/Settings/allot-modal all stack with ZERO horizontal overflow.
  The app is already responsive in `aizen`.
- FINDING: the agencies' "many UX issues" are on the LIVE site (prod), which runs
  OLD code — the accumulated fixes (blank-screen-after-upload, image upload,
  shell scroll-out, completed→Complete tab, delete-driver error, mobile grid
  stacking, image lightbox) are ALL on `aizen` and only need the teammate's
  promotion to reach prod.
- Polish: `pill-tab` + `btn-action` bumped to a 40px min-height (were ~32-34px)
  for comfortable tapping. Verified 40px + no overflow live. Pushed to `aizen`.

### Session 85 - Trip card: client + area first, de-duplicated address

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** travel-desk (`aizen` 35c3b9b)

- Redesigned the trips-list card (`trips/page.tsx`): **client name** is the
  highlighted first line; the **locality/area** shows next in accent — extracted
  by `extractArea()` (token before a known city, e.g. Poonamallee / Paruthippattu,
  NOT "Chennai"; falls back to a locality-suffix token, then the city). Project
  name + phone + date follow as muted details.
- `dedupeAddress()` collapses the free-typed address's repeated comma-tokens
  (they often repeat the same area/street 3×) and appends the landmark once.
- Verified LIVE on the logged-in dev server: card shows "Ravindran / Poonamallee"
  and "9003124840 / Paruthippattu", with the address de-duplicated. `tsc` clean.

### Session 86 - Verify double-booking fix + Salem area parsing + uppercase

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** travel-desk (`aizen` eaba260)

- Re-verified the external-driver double-booking removal: the ONLY two callers of
  `assertDriverAvailableOnDate` (travelDeskTrips.ts:778, marketing/fleet.ts:950)
  are both inside the internal-only guard; the error string is unique to that
  function; `travelAgencies.kind` = internal|external (absent→external). So
  external agencies never hit it — code is correct; live persists only until the
  `max` convex deploy.
- Area parser bug: `extractArea` used `cityIdx > 0`, so a city-first address
  ("Salem, bus stand") fell through and returned the trailing landmark. Fixed to
  `cityIdx >= 0` (returns the city when nothing precedes it). Expanded the TN
  district/city list (Salem, Namakkal, Erode, Hosur, Karur, …). Stripped
  surrounding punctuation from tokens so "Chennai -" matches. Area line now
  renders UPPERCASE. Verified live: Premkumar→SALEM, Kannan→PORUR,
  Poonamallee/Paruthippattu intact. `tsc` clean.

### Session 87 - Trips sub-tab survives Back navigation

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repo:** travel-desk (`aizen` 419646e)

- Repro (live): on Trips, select "Assigned"/"In progress", open a trip, press
  Back → the tab reset to "Pending" (the sub-tab was local `useState`, lost on
  remount). That's the "not properly back-navigating to the previous page".
- Fix (`trips/page.tsx`): tab now initialises from `?tab=` (`readTabFromUrl`) and
  `changeTab` does `router.replace('/trips?tab=<t>', {scroll:false})`. Back from a
  trip detail returns to `/trips?tab=in_progress` and the page re-reads it →
  restores the tab. Verified live: In progress → open trip → Back → In progress.
  `tsc` clean. Sidebar tabs already used <Link> (browser history) so were fine.

### Session 88 - Agency staff: login + operations + read-only Settings

**Date:** 2026-07-29
**Agent:** Claude (Claude Code)
**Repos:** manjusitedevelopment (`max` c9418bfb) + travel-desk (`aizen` fd4f53b)

- Roles: `driver | agency | agency_staff`. Verified `agency_staff` login is
  supported (`agencyStaffToUser`), and ALL trip/driver/vehicle operation
  mutations use `requireAgencySession` (agency + staff) — so staff already
  allot/complete/manage uploads. Only Settings was admin-gated.
- Backend: `travelDeskSettings.get` → `requireAgencySession` (staff can READ);
  `update` stays `requireAgencyAdminSession` (admin-only write). Deploy-gated.
- Frontend: Sidebar shows Settings for `agency_staff`; settings page renders
  read-only for non-admins (disabled inputs, no Save button, view-only note),
  admin keeps full edit. Both repos `tsc` clean; pushed.
- IN PROGRESS (next): user wants dynamic custom charge fields (add/edit/remove,
  unit = per km/hr/min/person/toll), chosen Package/Outstation at completion,
  defaults applied, synced to MMS. Large multi-repo epic — design below.

### Session 89 - QR scan crash fix + custom charge fields (config layer)

**Date:** 2026-07-30
**Agent:** Claude (Claude Code)
**Repos:** Mconnect (`merge` da17459) + MMS/travel-desk (custom fields, uncommitted)

- **QR crash (URGENT, DONE + pushed `merge` da17459):** staff couldn't close an
  SV via QR — scanning the "Client consulting" QR crashed with
  "Iterable.iterator() on a null object reference". Root cause:
  `ScannedSiteVisit.attendees` was `List<...> = emptyList()` (non-null), but Gson
  writes null when the backend sends `"attendees": null`, so
  `visit.attendees.filterNot{}` (QrScannerFragment.kt:396) iterated null. Fix:
  model → `List<ScannedSiteVisitAttendee>? = null` (GeoTrackApi.kt:1302) + call
  site → `visit.attendees.orEmpty().filterNot{}`. Build OK. Classic Gson trap.

- **Custom charge fields — CONFIG LAYER (done, uncommitted):**
  - MMS backend (`max`, uncommitted): `travelDeskAgencySettings.customFields`
    (both Packages + Outstation profiles) — `{id,label,amount,unit}`, unit ∈
    km|hour|minute|person|toll|trip. `sanitizeCustomFields` (max 30, valid unit,
    non-neg). `get` returns them; `update` accepts them; http.ts maps them for
    both profiles. schema.ts + travelDeskSettings.ts + http.ts.
  - travel-desk web (`aizen`, uncommitted): api types `TravelDeskCustomUnit` /
    `TravelDeskCustomField` + `customFields` on `TravelDeskFleetProfile`. Settings
    page: per-profile "Custom charges" section — Add field button, inline
    label/amount/unit-picker rows, Remove, read-only for agency_staff. `tsc` clean.
  - CONSUMPTION LAYER (next): apply custom charges at trip completion (choose
    per-trip, defaults from unit × qty), persist on trip, show in MMS + app.
    Exploring the billing/complete flow (MMS mutation + app fleet completion) now.

---

### Session 89 — Custom charge fields: CONSUMPTION layer (apply + persist + show)
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested:** Finish the dynamic custom-charge feature — apply the operator-
defined fields at trip completion (choose Package/Outstation, use each field's
default amount by unit), keep them editable/removable, sum into the total, and
surface across travel-desk web + MMS + the app.

**MMS backend (`max`, committed 4d4e6556, pushed — NOT deployed):**
- `schema.ts`: `travelDeskCustomCharges` on `siteVisits` (`{label,amount,unit}`,
  unit ∈ km|hour|minute|person|toll|trip); `customFields` already on the agency
  settings table (both profiles).
- `travelDeskTrips.ts`: `customChargeValidator` + `sanitizeCustomCharges`
  (label required, non-neg, throws on bad amount). `finalizeBilling` and
  `completeExpiredOffline` now accept `customCharges`, fold their sum into
  `travelDeskTotalAmount`, and persist `travelDeskCustomCharges`.
- `travelDeskSettings.ts`: `update` numeric guard now filters to numbers only
  (custom arrays no longer trip it) + accepts top-level `customFields`.
- `http.ts`: `mapCustomCharges` unit-safe coercion forwarded on both completion
  routes; `mapCustomFields` forwarded on settings/update (both profiles).
- `features/fleet/types.ts` + `assigned-tab.tsx`: itemise applied custom charges
  in both billing-summary breakdowns.
- Validation: `tsc -p convex/tsconfig.json` clean; full-project tsc has one
  PRE-EXISTING unrelated error (attendanceMobilePunchEdit.test.ts) only.

**travel-desk web (`aizen`, committed 7fa790d, pushed):**
- api: `TravelDeskAppliedCharge`; `customCharges` on both completion calls;
  `travelDeskCustomCharges` on `TravelDeskTripRow`.
- `trips/[id]/page.tsx`: billing form gets a "Custom charges" block — a
  Packages/Outstation sheet toggle that auto-applies that profile's fields
  (amount = default × qty: km→distance, hour→standingMin/60, minute→standingMin,
  person→attendees, toll/trip→flat), each line editable/removable, ad-hoc
  "+ Add charge"; folded into billing total; completed view itemises them.
- Validation: `tsc --noEmit` clean.

**Mconnect app (`merge`, uncommitted at time of writing):**
- `TravelDeskModels.kt`: `TravelDeskAppliedCharge` data class + optional
  `travelDeskCustomCharges` on `TravelDeskTrip` (Gson-safe, nullable/default).
- `AdminFleetTripsFragment.kt`: `customCharges` on `AdminTrip` + mapping.
- `AdminFleetTripManageSheet.kt`: billing breakdown lists applied custom charges
  before Total. (App display parity — the app's offline-complete sheet is
  internal-fleet only, so external agency trips are completed on the web; the
  app shows what agencies applied.)
- Validation: `:app:compileDebugKotlin` BUILD SUCCESSFUL.

**Deploy gating / follow-ups:**
- Convex NOT deployed (teammate promotes `max`); `aizen` teammate-promoted.
- Scope note: the app-side custom-field DEFINITION editor (ManageRatesBottomSheet
  add/edit/remove) and app internal-fleet completion apply were left to web —
  the app settings model is still behind web (no outstation/hillCharge); a
  faithful port is a separate slice.

---

### Session 90 — Pickup map preview + custom-charge refinements
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested (from screenshots):** custom-charge unit list must include "per
trip"; add delete/edit on custom fields; show the Packages/Outstation toggle on
the completion form; staff see Settings read-only (not removed); inline map with
the client pickup marked on the trip detail page + the external driver link, CP/
SV-style and responsive.

**Already delivered in Session 89 (verified live this turn):**
- Custom-charge units already include "Per trip (flat)" + km/hour/minute/person/
  toll; each row has editable Name/Amount/Unit + Remove; "+ Add field" works.
  Completion form already carries the Packages/Outstation toggle.
- Settings is read-only for agency_staff (canEdit = role==="agency"): inputs
  disabled, no Add field / Save. (The "remove settings tab" ask was superseded by
  the user's final "visible but can't edit/add".)

**New this turn:**
- MMS (`max`, committed 4f21e8a4, pushed): `travelDeskDriverTrips.ts` returns
  `pickupLat`/`pickupLng`/`pickupGoogleMapsLink` (agency trips already had them
  via enrichTrip's `...visit`). Deploy-gated.
- travel-desk (`aizen`, committed 96ffc11, pushed): new `map-preview.tsx` —
  keyless Google Maps `output=embed` iframe (no API key / lib), pins by coords
  when present else geocodes the address, responsive aspect-ratio box + "Open in
  Google Maps". Wired into the trip-detail Client card and the public driver-link
  page; dropped the redundant "Open in map" buttons. `pickupLat/Lng/
  pickupGoogleMapsLink` added to `TravelDeskTripRow`.
- Validation: convex + travel-desk `tsc` clean. Browser-verified on
  localhost:3200: map geocodes the pickup correctly (Porur / Sri Ramachandra
  Hospital), desktop + 375px mobile both clean, zero console errors; Settings
  custom-charge row + full 6-unit dropdown confirmed.

**Deploy gating:** convex NOT deployed (teammate promotes `max`); `aizen`
teammate-promoted. Driver-link pin needs the `max` deploy for coords, but falls
back to address-geocoding meanwhile.

### Session 90 - SV manual-close "Not Interested" HTTP 500 fix

**Date:** 2026-07-30
**Session:** fork (branched from main chat; main is building the travel-desk
custom-charge feature in MMS + travel-desk concurrently — DO NOT touch those
files here)
**Agent:** Claude (Claude Code)
**Repo:** Mconnect (`merge` f7bf8c8d, pushed both remotes)

- Report: manually closing an SV as "Client Not Interested" → Save → bare
  "HTTP 500" (screenshot). App points at PROD (`api-mfpl.theairix.com`).
- Root cause (code-confirmed): `SiteVisitOverviewFragment` unlocks the outcome
  buttons at **On Site** (`ownActiveIndex>=2` / cab `activeIndex>=3`, both =
  status `on_site`). But `marketing/siteVisits.setOutcome` `assertTransition`
  only allows `on_counselling | picked_from_site | dropped` — NOT `on_site`. The
  QR flow calls `markOnCounselling` (on_site→on_counselling) before the outcome;
  the manual path went straight to setOutcome → 500. Also the manual-close catch
  showed raw `e.message` ("HTTP 500") while the sibling `persistSiteVisit` catch
  already parsed the backend `{error}`.
- Fix (`CompleteCpVisitBottomSheet.finalizeTerminalOutcome`, SV branch):
  best-effort `markSiteVisitOnCounselling(svId)` (runCatching, ignore error —
  only transitions from on_site; no-ops for already-eligible statuses) BEFORE
  `setSiteVisitOutcome`; catch now uses `extractHttpErrorMessage`. Added
  `SiteVisitIdRequest` import. `:app:assembleDebug` OK.
- No backend/prod change needed; works whether or not prod has newer setOutcome.

---

### Session 91 — Rate sheet made fully dynamic (add/edit/remove every field)
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested:** the fixed settings fields (Kilometre rate, Package amount, Betta,
Permit, Hill, Standing, Waiting, Cancellation, Toll…) should become added
fields — editable AND removable. User chose "Everything, fully dynamic": drop
the fixed grid, every field is a removable/editable row, completion becomes
line-item driven (total = sum of every line by unit).

**Constraint found:** kmRate/packageAmount columns are read by allocation
(travelDeskTrips:812), extra-km claims (:597) and the MMS fleet table
(travelAgencies.list). So they must stay populated.

**MMS (`max`, committed b197eaf6, pushed — NOT deployed):**
- travelDeskSettings.ts: `seedCustomFieldsFromLegacy` (when customFields never
  set, synthesise the list from the legacy columns — nothing lost); resolveProfile
  seeds only when customFields is `undefined` (a defined [] is a real emptied
  sheet). `update` derives + mirrors kmRate (first per-km line) and packageAmount
  (a "package" per-trip line) back to the legacy columns for the readers above.
- assigned-tab.tsx: when a completed trip has custom charges, show only those +
  total; hide the now-zero legacy fixed rows (both km + package blocks).

**travel-desk (`aizen`, committed 7d5d063, pushed):**
- settings/page.tsx: fixed grid replaced by ONE dynamic list (name/amount/unit +
  add/remove), read-only for staff. Uses shared `resolveProfileFields` which
  respects a defined array else seeds from the legacy columns client-side (works
  pre-deploy).
- trips/[id]/page.tsx: completion is fully line-item driven — Charges section
  with Packages/Outstation toggle applies the sheet (unit × distance/attendees/
  standing), lines editable/removable/addable, base price forced to 0, total =
  sum of lines. Removed the fixed charge inputs; added Standing-time input.
- travel-desk-api.ts: exported `resolveProfileFields`.
- Browser-verified (localhost:3200): Settings shows 10 seeded rows —
  "Kilometre rate 15 / km", "Package amount 3000 / trip", rest "0 / trip"
  (Standing "0 / hour") — each editable/removable, full 6-unit dropdown, + Add
  field, Save. No server/console errors.

**app (`merge`, committed 0b2f56ae — LOCAL per user hold):**
- AdminFleetTripManageSheet.kt: billing breakdown shows only custom lines + total
  when present (hides legacy zero rows). `:app:compileDebugKotlin` SUCCESSFUL.

**Validation:** convex `tsc -p convex/tsconfig.json` clean; travel-desk `tsc`
clean; MMS full `tsc` no fleet errors; app compile OK.

**Deploy gating:** convex NOT deployed — pre-deploy the client seed shows the
sheet, but SAVING needs `max` deployed (old update mutation rejects customFields).
`aizen` teammate-promoted. App commit held local.

---

### Session 92 — Completion auto-applies sheet + prominent toggle; SV no-access note
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested (batch):** (1) the Settings charges should appear in the trip
completion form automatically, usable at default pricing or editable there
WITHOUT changing Settings; (2) make the Packages/Outstation toggle wider +
highlighted; (3) when a user without access opens/closes an SV, show the details
+ "you don't have access, contact the Site Incharge <name>".

**travel-desk (`aizen`, committed c6b1c0e, pushed) — #1 + #2:**
- trips/[id]/page.tsx: charge-line model gains `rate` + `edited`. The default
  sheet auto-applies when the billing form opens (no tap needed); each line
  re-prices live from rate × quantity (distance/standing/attendees) until the
  user edits its amount (which detaches it). Editing here never writes to
  Settings — it's a working copy sent as customCharges.
- The sheet toggle is now a full-width, highlighted 2-col segmented control
  ("Rate sheet"), with copy clarifying edits don't affect Settings.
- Browser-verified on a billing-pending trip (localhost:3200): form shows the
  wide Packages/Outstation toggle + 10 auto-populated lines (per km, per trip…,
  per hour) each removable, Standing-time input, + Add charge, map preview.

**app (`merge`, committed 0da8fc00 — LOCAL per user hold) — #3:**
- SiteVisitCounsellingConfirmBottomSheet.kt: no-access branch (can't start
  counselling nor record outcome) now shows a warning-coloured note naming the
  Site Incharge — "You don't have access to close this site visit. Please
  contact the Site Incharge (<name>)…"; ongoing-but-unauthorised handled too.
  Incharge name already flows in via ARG_INCHARGE_NAME (QrScannerFragment passes
  visit.inchargeStaff?.name). `:app:compileDebugKotlin` SUCCESSFUL.

**Deploy gating:** convex unchanged this session; `aizen` pushed; app commits
held local (0b2f56ae, 0da8fc00 + earlier 4d682d4-era).

---

### Session 93 — Hide Driver WhatsApp box after Dropped
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested:** on a trip that has reached "Dropped", the Driver WhatsApp box
(status + Open driver link / Copy link / Resend WhatsApp) should not show.

**travel-desk (`aizen`, committed d4c8dc9, pushed):** trips/[id]/page.tsx — the
whole Driver WhatsApp box is now gated on `trip.travelDeskEndedAt == null`
(previously only the buttons hid at billing-complete). Once the client is
dropped the run is over, so the box disappears; only billing remains.
Browser-verified on the dropped trip (localhost:3200): "Driver WhatsApp",
"Resend WhatsApp", "Open driver link" all absent; trip still shows "Dropped".
`tsc` clean.

**Also this session:** confirmed (no code change) the completion form's "Total
km travelled" already auto-calculates (Start 100000 / End 100030 → 30 km, live)
on both travel-desk and the app (bindTotalKm); shows "-" only until BOTH km
fields are filled.

---

### Session 94 — Completion charges as quantity × rate
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested:** completion charge lines should show the quantity (auto-fetched or
entered) and the computed amount, e.g. "Travelled km [23] km - 345", "Standing
charge [5] hrs - 200", for all per-unit fields.

**travel-desk (`aizen`, committed 28d02af, pushed):** trips/[id]/page.tsx —
charge-line model gains `mode` ("qty" | "amount") + `quantity`. Per-unit units
(km/hour/minute/person) render as editable quantity + unit label + computed
"₹amount" (rate × quantity); flat units (trip/toll) keep a directly-editable ₹
amount. Quantity auto-fills from odometer distance / standing time / attendees
and re-prices live until edited (edited detaches it). Helpers: isQuantityUnit,
quantityUnitLabel (km/hrs/min/persons), qtyAmount; setLineLabel/Quantity/Amount
replace updateChargeLine; saved re-edit + ad-hoc lines are mode "amount".
Browser-verified (localhost:3200) on a billing-pending trip: km line "30 km →
₹450" (30 from odometer 100000→100030), standing "2 hrs → ₹0" (120 min), flat
lines editable ₹. `tsc` clean.

Note: preview session briefly redirected to /login after HMR; recovered via the
persisted travel_desk_session_token (no re-login performed).

---

### Session 95 — Mobile blank page after image upload
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Reported:** on mobile, uploading a dashboard image → tapping the image →
"done" leaves the page blank ("responsive" bug).

**Diagnosis:** not a CSS/layout bug — verified in the in-app browser at 375px:
no horizontal overflow, no transform-trapped fixed overlay, upload + lightbox
open/close all work. The real cause: mobile browsers reload the page when
returning from the native camera/gallery; on reload the travel-desk session
re-validates over a resuming network and any transient failure (or the 4s
timeout) set `user=null` → the auth guard bounced to /login (blank). Reproduced
the /login redirect earlier in-session.

**travel-desk (`aizen`, committed 5c83cba, pushed):**
- src/lib/auth.tsx: cache the user in localStorage (`travel_desk_session_user`)
  and hydrate it synchronously in the AuthProvider's useState initializer, so a
  reload paints immediately (isLoading starts false when cached). Session
  validation now signs out ONLY on an explicit token rejection; network/timeout
  failures keep the cached user and re-validate on the next navigation (removed
  the logout-on-catch and the isLoading timeout).
- trips/[id]/page.tsx: lock `document.body.style.overflow` while the image
  lightbox (DashboardImageField + EvidencePreview) is open, restore on close.
- Verified (localhost:3200): user cache written ("sudhan"), page stays on the
  trip route (no /login flash); lightbox toggles body overflow hidden↔none.
  `tsc` clean.

---

### Session 96 — Remove duplicate "Start km" on the drop step
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Reported:** the trip page showed "Start km" (+ start dashboard image) twice.

**travel-desk (`aizen`, committed 5e242de, pushed):** trips/[id]/page.tsx — the
`reached === 5` "Passenger dropped" step rendered its own Start km + start image
block, duplicating the "Trip start proof" block that already shows them for
reached >= 3. Removed the duplicate; the drop step now only collects End km +
end evidence. `tsc` clean. (EvidencePreview still used by Trip start proof and
the completed view.)

---

### Session 97 — Driver WhatsApp box lingers on completed trips
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Reported:** after completing a trip, the "Driver WhatsApp / Pending" box still
shows.

**travel-desk (`aizen`, committed 1bbd89e, pushed):** trips/[id]/page.tsx — the
box was gated only on `travelDeskEndedAt == null` (Session 93). But a trip can
be finished with endedAt still null (e.g. the SV is completed in the app without
the travel-desk driver drop flow), so the box lingered. Broadened the gate to
hide when finished by ANY measure: `travelDeskEndedAt == null &&
travelDeskBillingCompletedAt == null && !isCompletedTripRow(trip)`. Imported
isCompletedTripRow. Only adds hide-conditions → no regression for active trips
(all three false → box shows); verified dropped trips still hide it. `tsc` clean.

---

### Session 98 — SV QR scanner frozen on 2nd scan
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Reported:** SV QR scanning shows an issue on the second scan (superadmin +
site incharge roles).

**Root cause:** onQrCodeScanned sets isScanningActive=false and unbinds the
camera, then shows SiteVisitCounsellingConfirmBottomSheet (a dialog — the
scanner fragment is NOT destroyed). The action paths (Start counselling /
auto-redirect to outcome) call openSiteVisitOutcome → popBackStackImmediate,
which removes the scanner. But a PLAIN dismiss (Cancel/swipe/back) fired no
result, so the scanner stayed with the camera unbound + scanning off → the next
scan was frozen. resumeScanning() was only wired to invite-cancel + errors.

**Fix (app `merge`, committed 0497062b, pushed to both remotes):**
- SiteVisitCounsellingConfirmBottomSheet.kt: `actionTaken` flag set true in the
  Start-counselling onClick and the outcome-redirect onFinish; `onDismiss` fires
  a new `RESULT_KEY_CLOSED` result only when !actionTaken (and isAdded).
- QrScannerFragment.kt: setFragmentResultListener(RESULT_KEY_CLOSED) →
  resumeScanning() (re-binds camera + isScanningActive=true).
- `:app:compileDebugKotlin` SUCCESSFUL.

---

### Session 99 — Fleet sync audit (app / MMS web / travel-desk)
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested:** verify the fleet module syncs across mobile, web (MMS), travel-desk.

**In sync (verified):**
- Shared model: all three read/write the same `travelDesk*` fields on siteVisits;
  `travelDeskCustomCharges` name identical everywhere (no drift).
- Display parity: web-applied custom charges show in MMS web (assigned-tab) AND
  the app (AdminFleetTripManageSheet) — both via enrichVisit/enrichTrip `...visit`
  spread; the app model carries travelDeskCustomCharges.
- Both completion paths (internal marketing.fleet, external travelDeskTrips) write
  the same billing fields; legacy kmRate/packageAmount stay derived-mirrored.
- App uses mms-fleet routes for Manju staff (useMmsFleet = !isExternalFleetAgencyOperator)
  and travel-desk routes for agency operators.

**Gaps found (app external-fleet write-side lags web):**
1. App ManageRatesBottomSheet = flat Packages only (no customFields / Outstation /
   hillCharge). Web = full dynamic sheet → app can't define/edit custom fields.
2. DATA-LOSS (FIXED): app settings-save omits customFields; backend `update`
   reseeded over the agency's web sheet → wipe. **Fixed on `max` (c5569158):**
   omitted customFields now preserves the stored sheet (seeds only when none set).
   Web unaffected (always sends its full sheet). tsc clean.
3. App external completion (completeOfflineAgency → /api/travel-desk/trips/
   complete-offline) sends no customCharges + has no UI → an external trip
   completed via the APP gets no custom charges (web applies them).

**Dominant current state = DEPLOY GAP:** `max` + `aizen` not deployed, so the whole
custom-charges/fields/dynamic-sheet feature is inactive on prod; the three surfaces
currently sync on the OLD flat model. Once `max` deploys, web + display parity
activate and app write-side gaps (#1, #3) become live. Follow-ups (not done):
port the dynamic sheet to the app settings screen; add customCharges to the app
completion request + UI.

---

### Session 100 — Driver page + agency nav + mobile blank fixes
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Reported (travel-desk, on prod):** (1) driver link page goes blank on mobile;
(2) trip-detail back-nav lands on Drivers but nav shows Trips; (3) external
drivers shouldn't see amount fields (Toll/Beta); (4) start/end km not calculating.

**travel-desk (`aizen`, committed ed609db, pushed):**
- driver/trips/[token]/page.tsx (#3, #4): removed Toll amount + Beta inputs (and
  the toll/beta state) from the "Mark dropped" step — pricing is admin-only, the
  driver only records the odometer; submit sends endKm only. Added a live "Total
  km travelled" (endKm − travelDeskStartKm) with an "end must be > start" guard.
- trips/[id]/page.tsx (#4): admin billing form shows "End km must be greater than
  the start km" when End < Start instead of a bare "0 km" (that was the clamp,
  read as "not calculating"; valid input like 100000→100030=30 already worked).
- lib/auth.tsx (#2): agency operators now land on /trips (not /driver) on both
  login + the guard, matching the root redirect → Trips is the default tab and
  back-nav from a trip detail returns to /trips, not the Drivers roster. Dropped
  the now-unused isTravelDeskAgencyOperationsRole import.
- layout.tsx (#1): body h-full → h-[100dvh]. Root cause of the mobile blank: the
  app-shell body was overflow-hidden at h-full (= the SMALL viewport), so when
  the mobile URL bar hid, the body didn't grow and a blank gap showed at the
  bottom (public-trip-shell scrolls internally). dvh tracks the dynamic viewport.
- Validation: `tsc` clean; browser-checked at 375px (no overflow, body tracks
  viewport, no console errors).

---

### Session 101 — Remove-driver 404 + driver reactivate
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Reported (travel-desk):** removing a driver from a trip shows "server returned
an invalid response (404)"; deactivating driver/staff works but reactivating not.

**travel-desk (`aizen`, committed 9b85076, pushed):**
- Remove-driver 404: the Next proxy route src/app/api/travel-desk/trips/
  unallocate/route.ts was MISSING (allocate/arrive/end/etc. all have one; the
  convex /api/travel-desk/trips/unallocate route already existed). Created it —
  browser-verified it now returns 401 (auth) not 404.
- Driver reactivate: driver/page.tsx filtered the roster to status==="active",
  so a deactivated driver vanished with no way back. Now lists ALL drivers
  (active first, activeDrivers→sortedDrivers), shows an "Inactive" badge, and the
  action toggles Deactivate/Activate (handleDeactivate→handleToggleStatus).
- Staff: already correct — staff page shows Active/Inactive with an Activate
  toggle, and the /api/travel-desk/staff/update route honors status. No change.
- Validation: `tsc` clean; browser-checked at /driver (90 rows render, toggle
  logic present).

---

### Session 102 — Billing tab + staff billing permission + driver Call-client
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested (travel-desk external fleet):** (1) add a "Billing" tab after In
progress for dropped trips where the admin does billing; (2) staff without
permission see "contact admin" on Settings, add an admin toggle that grants a
staff billing access (view/modify Settings + use Billing tab); (3) move the
driver-page Call-client button beside Food preference.

**MMS (`max`, committed 0f409498, pushed — NOT deployed):**
- schema: travelDeskAgencyStaff.canBill. session: agencyStaffToUser carries
  canBill; agencyToUser → canBill:true (admins always). TravelDeskUser gains
  canBill. updateAgencyStaff accepts canBill and REQUIRES an admin session to
  set it (requireAgencyAdminSession); http staff/update forwards it. tsc clean.

**travel-desk (`aizen`, committed 3c01781, pushed):**
- api: canDoTravelDeskBilling(user) = agency OR (agency_staff && canBill);
  canBill on TravelDeskUser + TravelDeskAgencyStaffRow; updateTravelDeskAgencyStaff
  forwards canBill.
- trips/page.tsx: new Billing tab (Tab union + TAB_VALUES). billingRows =
  travelDeskEndedAt != null && travelDeskBillingCompletedAt == null; takes
  precedence — completed/inProgress/assigned recomputed to exclude it. Tab shown
  only when canBill; ?tab=billing for others → effectiveTab in_progress. Cards:
  "Ready to bill" badge + "Complete billing" link. Browser-verified as admin:
  bar shows Billing 1, the dropped trip lands there with the action.
- settings/page.tsx: gated on canDoTravelDeskBilling — no access → "Permission
  required — contact your admin" (replaces the old read-only-for-staff).
- staff/page.tsx: admin-only Grant/Revoke billing toggle + Billing badge
  (desktop + mobile).
- driver/trips/[token]/page.tsx: Call client moved into the details grid (right
  of Food preference); removed the separate block below the map.
- tsc clean.

**Deploy note:** admin sees Billing + Settings immediately (role check, not
canBill). Staff canBill needs `max` deployed to be granted/surfaced.

---

### Session 103 — Sidebar order, staff toggle (deploy gap), load perf
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested (travel-desk):** (1) Trips first in nav, Drivers moved down near
Staff; (2) external travel desk loads too slowly — fix; (3) "you didn't add the
Admin access toggle to Staff".

**#1 Sidebar (`aizen`, 580d936, pushed):** agencyOperationsNav reordered to
[Trips, Vehicles, Drivers] → full nav Trips, Vehicles, Drivers, Staff, Settings.
Browser-verified.

**#3 Staff toggle:** NOT missing — the "Grant/Revoke billing" toggle (Session
102) is on `aizen` and browser-verified rendering on /staff. The client is on
prod, where `aizen` isn't promoted yet. Deploy gap, not a bug.

**#2 Perf (`max`, a10ff211, pushed — NOT deployed):** profiled localhost against
the LIVE backend (api-mfpl): /trips/pending + /trips/assigned each ~11s. Root
cause: listPending/listAssigned enriched up to 160 trips with ~7 db.get each
(~1000+ reads). Added batched `enrichTrips()` (+ `batchGet`): collect unique
project/vehicle/lead/CP/lmo ids, fetch each once into maps, enrich from maps →
read count drops to the unique-doc total. Same payload; single enrichTrip kept
for mutations. tsc clean. (The double request per endpoint in the trace is dev
StrictMode — single load() trigger, so prod fetches once.)

**Deploy note:** all three land only after promotion — perf needs `max` deployed,
sidebar + staff toggle need `aizen` promoted. Nothing I change can speed up the
current prod backend until `max` is deployed.

---

### Session 104 — Contact-client action on trip cards
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested (travel-desk):** add a client contact option to the trip cards in
Assigned + In progress — web shows Copy (number), mobile shows Call (tel: →
dialer with the number).

**travel-desk (`aizen`, committed 30e8331, pushed):** trips/page.tsx — in the
allocated-trip card block (trip.vehicle, covers Assigned/In progress/Billing),
added a client-contact row gated on trip.clientPhone: a `tel:` "Call client"
link (className lg:hidden) + a "Copy number" button (hidden lg:inline-flex) that
copies trip.clientPhone via clipboard. New clientCopiedId state + copyClientNumber
handler (mirrors the driver-link copy). Browser-verified: at 1280px 8 "Copy
number" visible / tel hidden; at 375px 8 "Call client" visible (tel:9197...) /
copy hidden. `tsc` clean.

---

### Session 105 — Complete-tab date filter + billing exports
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested (travel-desk Complete tab):** date-range selector + filter + "export
all" that adapts to the range; each trip an "export billing" with print/download/
share exporting CSV/Excel with proper formatting.

**travel-desk (`aizen`, committed 5c1c16a, pushed):**
- lib/trip-export.ts (new): tripsToCsv (all-trips sheet), tripBillingCsv +
  tripBillingLines (itemised one-trip), tripBillingHtml (printable), downloadFile
  (UTF-8 BOM → Excel renders ₹/Tamil), printTripBilling, shareTripBilling
  (Web Share file → text → clipboard fallback), safeName.
- trips/page.tsx: Complete tab gets From/To date inputs + Clear; completeTabRows
  now filters `completed` by scheduledDate in range; summary shows "Total SV: N
  (of M)". "Export all" button downloads the filtered CSV. Each completed card
  gets an "Export billing ▾" menu (Print / Download (CSV) / Share) with an
  outside-click backdrop. New state completeFrom/To + exportMenuTripId.
- Browser-verified: 32 cards / 32 Export-billing buttons / Export all; menu shows
  Print+Download+Share; From=To=2026-07-31 → "Total SV: 1 (of 32)", 1 card, Clear
  shown. `tsc` clean. (Excel = CSV with BOM; no xlsx lib added.)

---

### Session 106 — Full mobile-responsive audit (external travel desk)
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested:** make the whole external website mobile-responsive.

**Audit (localhost @375px):** ran a horizontal-overflow + oversized-element check
on every surface — /trips (list, tabs, cards), /driver, /vehicle, /staff,
/settings, /trips/[id] (billing form), /driver/trips/[token], the View-summary
modal, and the Reassign/allocate modal (the biggest form). Result: **0 px
horizontal overflow everywhere**; tables collapse to card layouts, the dynamic
rate sheet stacks (Name/Amount/Unit/Remove), modals fit + scroll. The site is
already built responsive (mobile card patterns + the Session 100 h-[100dvh] fix).

**Change (`aizen`, pushed):** globals.css btn-ghost → min-h-[38px] + bigger
padding on mobile (was ~28px, below the comfortable tap minimum), compact at sm+.

**Conclusion / deploy note:** nothing structurally broken on mobile. The reason
prod may still look off is the deploy gap — the responsive fixes (dvh blank-page,
driver page, etc.) live on `aizen`, not yet promoted. Once `aizen` is on prod the
mobile experience matches the verified-clean audit.

---

### Session 107 — Driver page blank (hydration) + full-width pills
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Reported (travel-desk):** external driver trip link still goes blank after
uploading an image; the "Edited"/"Assigned" status pills render as full-width bars.

**Blank driver page — real root cause = HYDRATION MISMATCH (not memory/OOM):**
Extracted the Next dev error — "Hydration failed… server HTML didn't match client".
The optimistic-cache change (Session 100) initialised auth `user`/`isLoading` by
reading localStorage in the useState initializers, so SSR (no storage → isLoading
true → shell spinner) rendered a different tree than a client with a cached user
(isLoading false → content). React regenerated on dev (worked) but blanked on
prod. Fixes (`aizen`, committed 4151198, pushed):
- auth.tsx: deterministic initial state (user=null, isLoading=true); hydrate the
  cached user + clear isLoading in a mount useEffect (client-only).
- travel-desk-shell.tsx: public routes (login + /driver/trips/) now render BEFORE
  the isLoading spinner check (they never depend on the session).
- driver/trips/[token]/page.tsx: also gate on a `mounted` flag (defence in depth).
- Browser-verified: full reload of the driver link → hydrationError=false, page
  renders; /trips also clean.

**Pills (`aizen`, same commit):** trips/page.tsx — the badge column was
`flex flex-col … sm:items-end`, so on mobile (no base items-*) the pills stretched
full-width. Added `self-start sm:self-auto` to the Edited/Expired/status spans.
Verified @375px: badges 57–76px (were ~351px), not stretched.

---

### Session 108 — Trip dates as dd-mm-yyyy
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested:** show dates in dd-mm-yyyy (were showing raw YYYY-MM-DD).

**travel-desk (`aizen`, committed 659bdad, pushed):**
- lib/time-format.ts: new formatDateDmy(YYYY-MM-DD → dd-mm-yyyy).
- trips/page.tsx: applied to the card date + allocate + extra-km dialog dates.
- trips/[id]/page.tsx: applied to the header + reassign dialog dates.
- driver/trips/[token]/page.tsx: formatTripDate now emits dd-mm-yyyy (regex, no
  Date/locale → also drops a hydration-risk).
- Browser-verified: card shows "31-07-2026 · 2:00 PM"; no ISO dates remain.

---

### Session 109 — Always-visible Clear filter (Complete tab)
**Date:** 2026-07-30
**Agent:** Claude (Opus 4.8)

**Requested:** add a clear option for the Complete-tab date filters (the existing
one only appeared once a date was set → not discoverable).

**travel-desk (`aizen`, committed 2c7ad57, pushed):** trips/page.tsx — the "Clear
filter" button now always renders, disabled (opacity-50) until a From/To is
chosen. Browser-verified: present + disabled with empty filters.

### Session 91 - SV Follow-up save HTTP 500 (diagnosis + error surfacing)

**Date:** 2026-07-31
**Session:** fork (branched from main; main still owns travel-desk custom-charge
feature — did NOT touch MMS convex from this fork)
**Agent:** Claude (Claude Code)
**Repo:** Mconnect (`merge` 93f1c087, pushed both remotes)

- Report: SV "Follow up" (postpone, next date 31/07/2026) on a DROPPED cab trip
  → Save → bare "HTTP 500". App points at PROD (`api-mfpl`).
- Path: `persistSvFollowUp` → `postponeSiteVisit` → POST
  `/api/marketing/siteVisits/postpone` → `siteVisits.postponeVisit`.
  postponeVisit's assertTransition ALLOWS dropped (+scheduled/on_site/…), so NOT
  a transition error. Root cause is a server-side throw in the mutation. The
  standout user-dependent cause: `postponeVisit` line 3186-3188 requires
  `marketing.siteVisits.edit` (route always passes requesterStaffId=auth.user._id),
  whereas the Not-Interested/`setOutcome` path requires NO such permission — so a
  field BDO/Site-Incharge who can mark Not Interested 500s on Follow up. (Gate
  last touched 2026-07-27, commit 61b9f389.) Downstream helpers
  (patchSiteVisitStatsForChange / recordSiteVisitInsight /
  scheduleSiteVisitConfirmationWhatsAppForVisit) are secondary suspects.
- App fix (shipped): `persistSvFollowUp` catch now uses `extractHttpErrorMessage`
  so the real backend `{error}` shows instead of "HTTP 500". Build OK.
- IMMEDIATE PROD remedy (no deploy) if it's the permission: grant
  `marketing.siteVisits.edit` to the field-staff role in web IAM/roles. Proper
  backend fix = relax postponeVisit to allow the assigned staff to reschedule
  their own visit (mirror setOutcome) — DEPLOY-GATED, not done here, and MMS
  convex is the main chat's lane.

### Session 110 — MMS fleet dashboard: internal/external badge + tab counts

**Date:** 2026-07-31
**Session:** continuation
**Agent:** Claude (Claude Code)
**Repo:** manjusitedevelopment (`max` 38a9f944, pushed origin/max — NOT deployed)

- Request: "show these in an organized way properly and highlight was it
  external or internal fleet and show total count in the tabs" (MMS fleet
  dispatcher, Assigned view).
- Badge (frontend-only, ships immediately): each assigned trip row now shows an
  "Internal fleet" (info/blue) vs "External fleet" (primary) badge at the top of
  the Project cell, driven by `isMfplFleetVisit(visit)`. features/fleet/tabs/
  assigned-tab.tsx.
- Tab counts (backend + frontend, DEPLOY-GATED): `marketing.fleet.listAssigned`
  now returns `{ groups, counts }` instead of a bare group array. Counts
  {assigned, in_progress, complete} are computed over the window-filtered rows
  BEFORE the subtab narrows them, so all three tabs reflect the same population
  in one query (no extra round-trips). Reordered the window filter above the
  subtab filter to make this exact.
- Wiring: use-fleet-assigned-controller.ts destructures `{ groups, counts }`;
  AssignedFleetCounts added to features/fleet/types.ts; tab bar refactored to a
  data-driven map rendering a count pill per tab (guarded on
  `typeof count === "number"`, so it degrades gracefully to no-pill until the
  backend is deployed).
- Shape-change fallout fixed: convex/http.ts mobile dispatch endpoint
  (/api/mms-fleet/dispatch/assigned) and siteVisitCabLifecycleOverride.test.ts
  updated to read `.groups`.
- Verified: `tsc -p convex/tsconfig.json` clean; frontend `tsc` clean for
  fleet (only pre-existing unrelated attendance-test error remains). Browser:
  fleet dashboard loads without app errors, but the Assigned subtab is gated by
  `marketing.fleet.view` which the preview account lacks, and counts need a
  convex deploy — so the badge/counts couldn't be visually confirmed in preview.
  Teammate deploy required for counts to populate.

### Session 111 — Travel-desk "Update status" form (cancel/postpone) + voice note

**Date:** 2026-07-31
**Session:** continuation
**Agents/Repos:**
- manjusitedevelopment (`max` 557b643d, pushed — NOT deployed)
- travel-desk (`aizen` dad504a, pushed origin/aizen)

- Request: clicking near Call client should open a "Status update Form" that BOTH
  admin and driver can use — set Cancelled / Postponed / Not available-not picked
  up, with a voice note; result reaches SV Cancelled in the MMS SV tab.
- Decisions (AskUserQuestion): separate "Update status" button (Call client stays
  a dialer); Cancelled & Not-available → SV `cancelled`, Postponed → reschedule
  (new SV); voice note OPTIONAL.
- Backend (MMS, deploy-gated):
  - Extracted `cancelSiteVisitCore` / `postponeSiteVisitCore` from the staff
    `cancel` / `postponeVisit` mutations (marketing/siteVisits.ts) so the external
    flow reuses the identical WhatsApp/audit/stats/new-SV side effects. The staff
    mutations already tolerate no-actor calls, so the external path is safe.
  - `travelDeskDriverTrips.submitStatusUpdate(token, siteVisitId, reasonCode,
    reasonText?, voiceStorageId?, voiceDurationMs?, scheduledDate?, scheduledTime?)`.
    Auth via existing `getOperatorVisit` — agency session, driver session, OR raw
    32-hex driver-link access token — so ONE mutation serves admin + driver;
    ownership-checked. Postponed requires scheduledDate. Attributes agency vs
    driver in notes/audit (extraMeta.source="travel-desk").
  - schema: `travelDeskStatusReasonCode|ReasonText|VoiceId|VoiceDurationMs|
    UpdatedVia|UpdatedAt` on siteVisits.
  - http: `/api/travel-desk/trips/status-update` (OPTIONS+POST),
    `travelDeskOperatorTokenResponse` (bearer only; resolution deferred to the
    mutation) → forwards to submitStatusUpdate.
- Frontend (travel-desk): new reusable `components/status-update-dialog.tsx`
  (3 radios + note + MediaRecorder voice note record/playback/re-record;
  Postponed reveals required date + optional time; uploads voice via the existing
  `/api/travel-desk/storage/upload` then calls the new endpoint). Wired a separate
  "Update status" button on the agency trips card (assigned & in-progress, shows
  even once started) and on the driver link page (phase !== completed). Added
  `submitTravelDeskStatusUpdate` to travel-desk-api.ts.
- Verified: convex tsc clean (only pre-existing attendance-test error); travel-desk
  tsc clean. Browser (portal In-progress, logged in): "Update status" button
  renders on the Ongoing card; dialog opens ("Status update", 3 options, note,
  voice recorder); Postponed reveals date+time and enables Submit. NOTE: the
  mobile preview pane's 800px screenshot vs 375px viewport made pixel clicks miss
  (kept opening Reassign) — drove the final checks via ref/JS. Live submit not
  tested (backend deploy-gated).
- Reminders honored: no convex deploy; branch pushes only; AGENT_LOG local-only.

### Session 111b — Status update: add "No issues" (no-op) option

**Date:** 2026-07-31 · continuation
**Repos:** manjusitedevelopment (`max` 80a4f13e), travel-desk (`aizen` 4c845e2) — pushed, NOT deployed.

- Request: add a "No issues" button that marks nothing and lets the flow continue;
  no Note needed, voice note is enough.
- Frontend: 4th radio "No issues" placed FIRST (safe/common case); when selected
  the Note textarea is hidden, voice note kept, no date required.
- Backend: reasonCode "no_issue" added to schema union + submitStatusUpdate — an
  early no-op that only patches the status/voice metadata and returns
  {outcome:"no_issue"} without touching the SV lifecycle.
- Verified: convex tsc + travel-desk tsc clean; browser JS check confirmed the 4
  options render and selecting "No issues" hides Note while keeping the voice
  recorder.

### Session 111c — Driver link "blank below the fold" on photo pick (real fix)

**Date:** 2026-07-31 · continuation
**Repo:** travel-desk (`aizen` 30895c5, pushed)

- Report: external driver link goes blank/cut-off when uploading the dashboard
  image ("Start trip" visible, everything below blank) — same class as the admin
  blank issue.
- Root cause (this time NOT hydration): app `body` is `h-[100dvh] overflow-hidden`,
  but `.public-trip-shell` was `min-h-full` → it grew to content height (1563px)
  while body stayed 742px and clipped the overflow with NO scroll. Picking a photo
  adds the preview and pushes Start trip + the actions below into the clipped
  region → looks blank. Diagnosed via JS: main.scrollH 1563 vs clientH 742, body
  overflow-hidden.
- Fix: `.public-trip-shell` → `height: 100dvh` (was `min-h-full`), keeping
  `overflow-y-auto` so the shell itself is the scroll container. globals.css only.
- Verified: reload → main clientH 742 / scrollH 1563 / scrolls to bottom (821px);
  Start trip + Update status reachable; screenshot shows full form + bottom button.

### Session 111d — Driver link: Vehicle cell detail + Update status placement

**Date:** 2026-07-31 · continuation · travel-desk (`aizen` 033734a, pushed)

- Vehicle cell now shows vehicle number / driver name / vehicle model (model ·
  type) / driver number (was just number + driver name). Fields from
  trip.vehicle.{vehicleNumber,model,type} + trip.driver{Name,Phone}.
- Update status moved out of the shared Call-client cell into its OWN grid cell
  (the empty cell to the right), restyled btn-secondary self-start to match Call
  client's size (both 42px). Verified in browser.

### Session 111e — Driver link: show vehicle model (not body type)

**Date:** 2026-07-31 · continuation
**Repos:** manjusitedevelopment (`max` d5c7390b, rebased onto teammate 0c8cec2f, pushed — NOT deployed), travel-desk (`aizen` 1df1e64, pushed)

- Request: show vehicle MODEL (Swift / Innova), not the body type (Sedan).
- Frontend: Vehicle cell now renders `trip.vehicle.model` only (dropped the
  model·type join), hidden when absent.
- Backend (deploy-gated): `enrichDriverTrip` returned only {vehicleNumber, type}
  for vehicle — added `model: vehicle.model` (vehicles table has make/model/
  modelYear/type). Until deployed the model won't reach the page.
- NOTE on test data: vehicle "RAMAR" has no model saved (only type "Sedan"), so
  the model line is empty for it even post-deploy — real vehicles with a model
  will show it. Also earlier: deduped driver-name line when it == vehicleNumber.
- Verified: convex tsc + travel-desk tsc clean.

### Session 111f — Vehicles list card: driver name header, vehicle number by phone

**Date:** 2026-07-31 · continuation · travel-desk (`aizen` 09209ee, pushed)

- Request (clarified via AskUserQuestion): on the vehicles mobile card the bold
  header should be the DRIVER NAME and the value in front of the mobile number
  should be the VEHICLE NUMBER (they were mapped the opposite way; the RAMAR
  record has vehicleNumber==driverName which made it confusing).
- Change (src/app/vehicle/page.tsx, mobile card only): header =
  defaultDriverName || vehicleNumber; phone line = vehicleNumber · phone when a
  driver name exists, else just phone (avoids repeating the number when it's
  already the header). Desktop table left as-is (labeled columns).
- Verified: travel-desk tsc clean. Could NOT screenshot — preview account's
  vehicles list stayed on "Loading…" (unrelated data/permission state).

### Session 111g — Vehicle capacity excludes driver seat

**Date:** 2026-07-31 · continuation · travel-desk (`aizen` 5122832, pushed)

- Request: capacity should exclude the driver seat — Sedan 4 (not 5), SUV 6.
- Change: TRAVEL_DESK_VEHICLE_CAPACITY in src/lib/travel-desk-vehicle-rules.ts →
  SUV 7→6, Sedan 5→4, Hatchback 5→4. Single source; drives the form auto-fill
  (model/type onChange) + saved capacity for over-cap checks.
- Verified in browser: type Sedan → capacity 4; SUV → 6. Existing vehicles keep
  old stored capacity until re-saved.
- Also confirmed live in same view: vehicles-card swap (driver name header,
  "RAMAR · phone" below) from 111f is rendering correctly.

### Session 111h — CP visit booking-gate: inline message (keep rule)

**Date:** 2026-07-31 · continuation · manjusitedevelopment (`max` 54d20f0e, pushed — frontend only)

- Request: "handle that error" on Create CP Visit — "Collection CP blocked — no
  confirmed booking". Chosen (AskUserQuestion): KEEP the rule, improve UX with a
  persistent inline message (not a fleeting toast + silent dropdown reset).
- The gate (features/marketing/pages/cp-visits-list-page.tsx handleCpTypeChange)
  requires collection_cp/booking_cp mobiles to have a postSaleCase (byMobile).
  Change: removed the count===0 toast; render an inline destructive <p> with
  AlertTriangle under the CP Type Select when collectionGateCases.count===0 for
  the current phone; plus a green CheckCircle2 "N confirmed booking(s) found"
  when the gate passes. Business rule untouched.
- Verified in browser (localhost:3100, logged in): entered a no-booking mobile,
  picked Collection CP → inline red warning renders exactly as designed; tsc clean.

### Session 111i — SV postpone gated to pre-arrival (before on_site)

**Date:** 2026-07-31 · continuation
**Repos:** manjusitedevelopment (`max` 4991ae66, pushed) · Mconnect app (`merge` b80d138, committed LOCAL — awaiting push confirmation)

- Request: postpone option must NOT be available in/after on_site; before that,
  postponable. (CP/SV module.)
- Key nuance: the SV postpone endpoint (api.marketing.siteVisits.postponeVisit)
  is SHARED — the mobile "Follow up" outcome reuses it to reschedule a still-
  active visit to a next date (persistSvFollowUp → postponeSiteVisit), and the
  backend list already accepts scheduled/client_started/picked_up/on_site.
  Hard-blocking on_site in the backend would break real follow-ups (client not
  available at site → follow up). So gated the RESCHEDULE UI, left the shared
  endpoint accepting on_site (comment added). This also avoids the Session-91
  follow-up-500 area.
- Web (features/marketing/pages): renamed canPostponeBeforeCounselling →
  canPostponeBeforeArrival, dropped on_site (now scheduled/client_started/
  picked_up only), on SV detail (canPostponeVisit) + SV list (canPostponeRow);
  guard toast → "…before the client reaches the site". CP web postpone is dead
  code (read-only mirror), untouched.
- App (SiteVisitOverviewFragment.updatePostponeVisibility): added on_site/"on
  site" to the hide-set (renamed counsellingStarted → arrivedOrLater) so
  btnPostponeSiteVisit hides from on_site. btnPostponed ("Follow up" outcome)
  unchanged.
- Verified: convex test (3/3, incl. on_counselling reject) green; web tsc clean;
  app :app:compileDebugKotlin BUILD SUCCESSFUL. Browser: SV list renders (postpone
  lives in per-row menus; deterministic conditional, not driven per-status).

### Session 111j — Collection CP "Not Collected" outcome

**Date:** 2026-07-31 · continuation
**Repos:** manjusitedevelopment (`max` eff3a386, pushed) · Mconnect app (`merge` b6e45c1, LOCAL — awaiting push with 111i's b80d138)

- Request: Collection CP needs a "Not collected" option (staff forced to enter
  paid amount even when nothing collected). If used → web shows 0 + not-collected
  status. Decisions (AskUserQuestion): CP-visit-outcome only (NO post-sales ₹0
  ledger row); remarks OPTIONAL.
- Backend (deploy-gated): new clientPlaceVisits outcome `not_collected` in
  schema.ts + clientPlaceVisits.ts outcomeValidator + legacyImport type. setOutcome
  handles generically (→ completed). http route already forwards outcome string.
  No customerCollections write.
- App: CollectionPaymentEntryBottomSheet — added "Nothing collected — mark Not
  Collected" footer button (sheet_collection_payment_entry.xml) returning
  KEY_NOT_COLLECTED + optional notes. TripNavigationFragment: result listener
  branch → completeNotCollectedVisit(cpId) = markClientMet(true) +
  setCpVisitOutcome("not_collected", "Not collected — <remarks>"). Skips the
  amount>0 / reference guards.
- Web: cp-visit-detail-page badge "Not Collected · ₹0" (amber) + type; cp-visits-
  list-page OUTCOME label "Not Collected" + type.
- Verified: convex tsc clean (after extending legacyImport ClientPlaceVisitOutcome);
  web tsc clean; app :app:compileDebugKotlin BUILD SUCCESSFUL. Note: existing
  client-ABSENT collection path (completeCpVisitWithoutClient → collection_done)
  left unchanged — this feature is client-PRESENT/nothing-collected.

### Session 92 - Gift Distribution "Confirm" HTTP 500 + local OTP bypass

**Date:** 2026-07-31
**Session:** fork (branched from main). MMS edits confined to fieldVisitOtp.ts,
clientPlaceVisits.ts, http.ts — all clean of main-chat work (only _generated
was theirs). Nothing committed/deployed by me.

- **Local OTP bypass (prior ask):** `DEV_OTP_BYPASS==="true"` in
  `hr/fieldVisitOtp.ts` now also skips the contact-phone gate + geofence gate
  (was only pinning OTP=1111 + skipping SMS). maskPhone/SMS guarded for the
  no-phone case. Inert unless the flag is set (LOCAL/DEV ONLY — never prod).
- **Gift Distribution 500 (this ask):** "Confirm Gift Distribution" 500'd.
  Root cause = ordering: `setCpVisitOutcome("gift_distributed")` runs BEFORE the
  handover photo is linked (that happens in `finalizeCompleteVisit → completeVisit`),
  so `clientPlaceVisits.setOutcome`'s `assertRequiredCpCompletionProof`
  (requires fieldVisit.arrivalPhotoStorageId) threw. The gift OTP sheet opens
  with arrivalPhotoStorageId=null, so no photo is attached at verify.
  - Fix (backend): `clientPlaceVisits.setOutcome` takes optional
    `arrivalPhotoStorageId` and attaches it to the fieldVisit (if not already set)
    BEFORE the proof check. `http.ts` CP setOutcome route forwards it.
  - Fix (app): `SetOutcomeRequest.arrivalPhotoStorageId`; gift flow passes
    `pendingArrivalStorageId`; gift catch now surfaces backend {error} via a new
    `httpErrorMessage()` helper instead of raw "HTTP 500".
  - Validation: app `:app:assembleDebug` OK; convex `tsc` clean. Uncommitted.
  - To test locally: `npx convex dev` (pushes backend) + rebuild/install app at
    local dev. Deploy-gated for prod.

### Session 92 (cont.) - Deployed gift fix to DEV backend + dev-pointed APK

- Confirmed `.env.local` active target = `next-spaniel-814` (DEV; prod
  api-mfpl/convex-mfpl commented out) — safe to push (not the live site).
- Ran `npx convex dev --once` in manjusitedevelopment → deployed working-tree
  convex (fieldVisitOtp DEV_OTP_BYPASS widening + clientPlaceVisits.setOutcome
  arrivalPhotoStorageId attach + http.ts route) to next-spaniel-814. OK.
- Rebuilt app with `MCONNECT_BASE_URL=https://next-spaniel-814.convex.site/` so
  the debug APK points at the dev backend (default build points at prod api-mfpl).
  APK: app/build/outputs/apk/debug/app-debug.apk. Install this to test the gift
  flow end-to-end (backend fix needs the app to SEND arrivalPhotoStorageId).
- DEV_OTP_BYPASS already set on next-spaniel-814 (1111 worked earlier for user).
- Did NOT deploy to prod; did NOT commit (MMS = main chat's repo; user iterating).

### Session 111k — Test DB switch (next-spaniel-814) + CP Type column

**Date:** 2026-07-31 · continuation · manjusitedevelopment (`max` a730dc96, pushed)

- USER EXPLICITLY authorized deploying convex to a TEST deployment next-spaniel-814
  (temporarily overrides never-deploy-convex). Switched .env + .env.local (mfpl
  block commented, next-spaniel active + CONVEX_DEPLOY_KEY); backups in scratchpad.
  First deploy failed (node_modules missing @convex-dev/workpool) → pnpm install →
  `npx convex deploy -y` SUCCEEDED (schema validation passed; all our backend
  changes live). Restarted web-dev preview; verified client bundle built with
  next-spaniel-814 (not mfpl); next-spaniel .convex.site route → 401 (deployed).
  New memory: convex-test-db-next-spaniel. Switch back to mfpl on user's word.
- CP Type column: added "CP Type" col (after Project) to CP Visits list table
  (cp-visits-list-page.tsx) rendering CP_TYPE_LABEL[v.cpType] as a Badge; loading
  colSpan 8→9. Query already returns cpType. Verified live (Collection CP / Gift
  Distribution / Old Client / SV cum CP / Follow-up render). tsc clean.
- FLAGGED: pre-existing UNCOMMITTED web-repo changes NOT mine, left unstaged (NOT
  on max): convex/hr/fieldVisitOtp.ts DEV_OTP_BYPASS flag ("MUST stay unset in
  prod"); convex/http.ts + clientPlaceVisits.ts arrivalPhotoStorageId (gift
  handover photo before proof). They got deployed to next-spaniel via working-tree
  deploy (harmless; DEV_OTP_BYPASS env-gated off). Deploy key secret safe (.env*
  untracked). Still-unpushed APP (merge): b80d138 postpone + b6e45c1 not-collected.

### Session 111l — Maps billing diagnosis + clear updateLocationAndNotes server error

**Date:** 2026-07-31 · continuation · manjusitedevelopment (deployed to next-spaniel-814 test DB)

- Google Maps "Oops" = BillingNotEnabledMapError. Active key AIzaSyB7mD91… (also
  GOOGLE_MAPS_SERVER_KEY) has NO billing on its GCP project. Other key AIzaSyD2l7…
  HAS billing + localhost referrer OK but Geocoding API NOT enabled (REQUEST_DENIED)
  and is referrer-restricted (unusable server-side). Swapped NEXT_PUBLIC_GOOGLE_MAPS_WEB_KEY
  → D2l7 in .env.local (LOCAL only, untracked) → map TILES render; address RESOLVE
  still needs a billing-enabled+Geocoding key. DEFINITIVE FIX (user's GCP action):
  enable Billing on the B7mD project, then revert the swap. Not a code bug.
- CP visit "Save changes" red toast = CONVEX updateLocationAndNotes "Server Error".
  Real error (via `npx convex run` against next-spaniel): "This CP visit has no
  linked clientPlace to edit." The test visit had no clientPlaceId; a plain throw
  surfaces as generic Server Error.
- FIX (convex/marketing/clientPlaceVisits.ts updateLocationAndNotes): when
  !visit.clientPlaceId AND location fields present, CREATE a clientPlaces row
  (name from lead/contact/address; type client/status active; all address+lat/lng
  fields) and patch visit.clientPlaceId, instead of throwing. Deployed to
  next-spaniel; re-ran the mutation → SUCCESS (returns enriched visit). convex tsc
  clean.
- GIT/COMMIT HELD: clientPlaceVisits.ts also carries the teammate's UNCOMMITTED
  arrivalPhotoStorageId hunk (+ http.ts +1, fieldVisitOtp.ts DEV_OTP_BYPASS). Did
  NOT commit to max to avoid bundling their WIP into a prod-bound branch. Awaiting
  user decision.

### Session 111m — Push all

**Date:** 2026-07-31 · continuation
- MMS `max`: 1b7ed712 (DEV_OTP_BYPASS, isolated commit so it can be dropped before
  a prod deploy) + 7691f3f2 (updateLocationAndNotes creates clientPlace when
  missing; arrivalPhotoStorageId gift-photo; regen api types). Pushed.
- App `merge` → BOTH remotes (manjugroupsdev + D-A-R-X): b80d138 (postpone hidden
  at on_site) + b6e45c1 (Collection CP Not Collected). Pushed.
- travel-desk `aizen`: already fully pushed (0 ahead/behind) — nothing new.
- NOT committed (correct): .env/.env.local (secrets/next-spaniel switch, gitignored),
  AGENT_LOG.md (local). Reminder: DEV_OTP_BYPASS is now on max as its own commit;
  drop/revert it before any prod deploy (it disables real arrival OTP when env on).

### Session 93 - SV Follow-up "invalid transition from on_counselling" fix

**Date:** 2026-07-31
**Session:** fork. MMS edit confined to siteVisits.ts (postponeSiteVisitCore).
Deployed to DEV (next-spaniel-814) only; prod untouched; uncommitted.

- The session-91 error-surfacing fix paid off: the app now showed the REAL error
  ("cannot postpone visit from status on_counselling. Allowed: scheduled,
  client_started, picked_up, on_site") — so the SV Follow-up failure was a
  TRANSITION guard, NOT the permission issue I'd speculated in session 91.
- Root cause: `postponeSiteVisitCore` (shared by the mobile Follow-up via
  `siteVisits.postponeVisit`) allowed only scheduled/client_started/picked_up/
  on_site. But the app's Follow-up button stays available through the whole
  post-arrival window (on_counselling/picked_from_site/dropped — same as
  setOutcome). So a Follow-up once counselling started was rejected.
- Fix (backend-only): added on_counselling, picked_from_site, dropped to the
  allowed list. No app change — the app already sends the right request; retry
  in the SAME build. `tsc` clean; `convex dev --once` → next-spaniel-814 OK.
- NOTE: this is a genuine PROD bug too (same restrictive list on prod). Ship to
  prod when folding the fixes into MMS.

### Session 111n — Driver link blank/scroll (real root cause: double scroll container)

**Date:** 2026-07-31 · continuation · travel-desk (`aizen` a4d085d, pushed)

- Report: driver link still scrolls up + blanks after picking dashboard image;
  worse on mobile. Asked to reuse admin upload component.
- Root cause (via live DOM inspection): TWO competing scroll containers — the
  driver-link WRAPPER in travel-desk-shell.tsx (flex-1 min-h-0 overflow-y-auto)
  AND my earlier 111c fix on .public-trip-shell (height:100dvh + overflow-y-auto).
  With body h-[100dvh] overflow-hidden, the html/window scrolled independently of
  the wrapper → content decoupled, page jumped/blanked. (Not the image component —
  driver's DashboardImagePicker is already the same pattern as the admin trips
  page's.)
- Fix: .public-trip-shell → position:fixed; inset:0; z-index:0; overflow-y:auto
  (globals.css). Out of document flow ⇒ the ONE scroll container; body/html/wrapper
  can't scroll. Verified desktop+mobile: window.scrollY & html stay 0, content
  scrolls to bottom, no blank; modals (fixed z-50) still layer above.
- GOTCHA: Turbopack cached the old compiled CSS across dev-server restarts — had to
  `rm -rf .next` + restart travel-desk-dev before globals.css @apply changes took
  effect (plain position:fixed CSS then HMR'd fine).

### Session 111o — Push all (round 2), guarding the app BASE_URL test switch

**Date:** 2026-07-31 · continuation
- MMS `max` a1705a2a: postponeSiteVisitCore accepts on_counselling/picked_from_site/
  dropped too (mobile Follow-up outcome reschedules across the post-arrival window;
  was 500'ing). convex tsc clean.
- App `merge` → both remotes 131c6314: SetOutcomeRequest.arrivalPhotoStorageId +
  gift-distribution passes handover photo to setOutcome; httpErrorMessage() surfaces
  real backend {error} instead of "HTTP 500". app compileDebugKotlin BUILD SUCCESSFUL.
- HELD BACK (kept LOCAL, NOT committed): app/build.gradle.kts — its default BASE_URL
  was flipped from api-mfpl.theairix.com (PROD) to next-spaniel-814.convex.site (TEST
  DB) to build the app against next-spaniel. Committing to merge would point prod app
  builds at the test DB → excluded, like .env. Revert this local edit (and .env/.env.local
  + the D2l7 maps key) when switching back to mfpl. AGENT_LOG.md local-only as always.

### Session 111p — Super Admin authorized for every field visit

**Date:** 2026-07-31 · continuation · manjusitedevelopment (`max` cfa06044, pushed + deployed to next-spaniel)

- Report: Super Admin got "Not authorized for this visit" on Swipe to Complete
  Trip (SV confirmation). Cause: convex/hr/fieldVisitOtp.ts gates
  requestArrivalOtp / verifyArrivalOtp / cancelArrivalOtp with
  visit.staffId !== args.staffId (assigned-staff-only).
- Fix: added isSuperAdminStaff(staff) = isAdmin===true || normalized role
  "super-admin" (matches isSuperAdmin elsewhere). _readVisitForOtp now takes
  optional actingStaffId and returns actingIsSuperAdmin; the two actions pass it
  and bypass; cancelArrivalOtp loads the actor and bypasses. Super admin → all
  visits.
- Verified vs next-spaniel via `convex run staff:list`: the Super Admin account
  (ADMIN-638208, g98a…) has role "super-admin" AND isAdmin true → bypass applies.
  convex tsc clean; deployed. User can retry on the app (BASE_URL → next-spaniel).

### Session 111p-fix — Deployment-target gotcha (dev vs prod on next-spaniel)

**Date:** 2026-07-31 · continuation
- CRITICAL: .env.local has BOTH `CONVEX_DEPLOYMENT=dev:next-spaniel-814` AND
  `CONVEX_DEPLOY_KEY=prod:next-spaniel-814` — TWO different deployments.
  • `npx convex deploy` → PROD (next-spaniel-814.convex.cloud) = what the app/web hit.
  • `npx convex run` WITHOUT --prod → the DEV deployment (stale; never deployed to).
  So my convex-run verifications were sometimes hitting the wrong (dev) deployment
  → false "not deployed" alarms (e.g. _readVisitForOtp "extra field actingStaffId").
  FIX: always verify with `npx convex run --prod ...` (matches deploy + app target).
- Re-deployed super-admin bypass; re-tested with --prod → _readVisitForOtp returns
  actingIsSuperAdmin:true for the super admin vs a visit assigned to another staff.
  Confirmed LIVE on next-spaniel prod (the app's target).
- If the mobile app STILL shows "not authorized": the APK isn't pointed at
  next-spaniel (BASE_URL baked at build time) — rebuild :app:assembleDebug with the
  next-spaniel build.gradle BASE_URL and reinstall.

### Session 111p-fix2 — Super-admin bypass: root cause was deployment targeting

**Date:** 2026-07-31 · continuation
- "Still not authorized" root cause: CONVEX_DEPLOYMENT=dev:next-spaniel-814 +
  CONVEX_DEPLOY_KEY=prod:next-spaniel-814 = different deployments. Deploys/verifies
  were inconsistently hitting the DEV deployment while the app/web hit prod
  (next-spaniel-814.convex.cloud/.site). So the super-admin fix wasn't reliably on
  the app's deployment.
- FIX: commented out CONVEX_DEPLOYMENT in .env.local → deploy/run now use the deploy
  key (= app target) unambiguously. Clean deploy done; verified via
  `convex run hr/fieldVisitOtp:verifyArrivalOtp` (super admin vs other-staff visit)
  → now returns "No active OTP. Request one first." (i.e. PASSED the auth gate).
  Super-admin bypass is LIVE on the app's deployment. Memory updated.
- User should retry Swipe to Complete on the app now.

### Session 94 - SV-cum-CP skipped the confirm sheet (SV left "Fixed")

**Date:** 2026-07-31
**Session:** fork. App-only (Mconnect); no backend change/deploy. Uncommitted.

- Bug: completing an sv_cum_cp trip finished the CP but showed NO form, and the
  linked SV stayed "Fixed" (never confirmed).
- Root cause: after OTP verify, TripNavigationFragment routes to the CP confirm
  sheet only when `isCpVisit()` — which is `tripType == "client_place" && cpVisitId`.
  But sv_cum_cp rows are identified by `visitCategory == "sv_cum_cp"` and do NOT
  carry tripType="client_place" (see HomeFragment:1026 OR-check). So isCpVisit()
  was false → the post-OTP branch fell through to `finalizeCompleteVisit()`
  (silent complete), skipping `showCpCompletionSheet()`.
- Fix 1 (TripNavigationFragment.onArrivalOtpVerified): compute `isSvCumCp`
  (cpVisitId + visitCategory==sv_cum_cp) and route `(isCpVisit || isSvCumCp)` to
  showCpCompletionSheet. Left isCpVisit() itself unchanged so the pre-OTP
  swipe/client-seen path is not altered.
- Fix 2 (CompleteCpVisitBottomSheet.detectAndApplyLockedSvMode): treat a linked
  SV (`convertedSiteVisitId`) as a lock signal so the Confirm/Reject footer shows
  even when proposed/lead/party signals are absent. Confirm → setCpVisitOutcome
  ("interested") → flips linked SV Fixed→Confirmed.
- Build OK (dev-pointed APK). Install + retest: after OTP the Confirm/Reject SV
  sheet must appear; Confirm should move the SV out of Fixed.
- Genuine PROD bug too (same isCpVisit gap) — ship with the others.

### Session 111q — Push all (round 3)

**Date:** 2026-07-31 · continuation
- App `merge` → both remotes bce67214: sv_cum_cp trips now reach the CP confirm
  sheet after arrival OTP (isSvCumCp routing in TripNavigationFragment) +
  hasConvertedSv SV-fix signal in CompleteCpVisitBottomSheet. Not my code
  (teammate WIP) but legit; app compileDebugKotlin BUILD SUCCESSFUL.
- MMS `max` (cfa06044) + travel-desk `aizen` (a4d085d): already clean/pushed.
- HELD LOCAL (not committed): app/build.gradle.kts (BASE_URL → next-spaniel test DB;
  committing would point prod app builds at test DB) and AGENT_LOG.md.
- Reminder: the MOBILE APP is still on PRODUCTION (mfpl) — proven this session by
  the CP visits list showing real prod clients (Vignesh/Gomathi) absent from
  next-spaniel. My next-spaniel deploys don't reach the phone until the APK is
  rebuilt with the next-spaniel BASE_URL and reinstalled.

### Session 95 - Trip Details: client address card above the map

**Date:** 2026-07-31
**Session:** fork. App-only (Mconnect); uncommitted; dev-pointed APK.

- Added a "Client Address" card between tripInfoCard and mapCard in
  fragment_trip_navigation.xml — icon chip (ic_cp_address) + "Client Address"
  label + full address (tvClientAddressFull, wraps, NO ellipsize/maxLines so the
  whole address shows). Styled to match the info card (bg_home_trip_card,
  bg_home_trip_stat_icon_v2, same hex #667085/#111827 the file already uses).
  Re-anchored mapCard's constraintTop to @id/clientAddressCard.
- Fragment: bind tvClientAddressFull = placeAddress ?: placeName ?:
  "Address not available" (next to the existing tvTripOriginName bind).
- Root is a plain ConstraintLayout (no scroll); card is compact (~64dp) and the
  map is only 180dp, so it fits with room below. If overflow appears on very
  small screens, wrap the content in a NestedScrollView (follow-up).
- Address source = placeAddress arg (the trip's client place address). Sparse
  test bookings may show "Address not available"; real visits populate it.
- Build OK (dev-pointed APK). No backend change.

### Session 111r — Status update: No-issues clean pass + rename option

**Date:** 2026-08-01 · continuation · travel-desk (`aizen` be5e089, pushed)
- status-update-dialog.tsx: for "No issues", hide the Note field AND the Voice
  note block (already hid Note; now hides voice too), and skip any stray recorded
  blob on submit for no_issue. Renamed "Not available / not picked up" →
  "Call not connected" (hint "Couldn't reach the client on the phone"); reasonCode
  stays not_available so backend unchanged.
- Verified live (admin trips in-progress → Update status on MANI · BLUE SPOT 3.0):
  labels = No issues / Cancelled / Call not connected / Postponed; No issues shows
  neither note nor voice; Cancelled shows both. tsc clean.

### Session 96 - COORDINATION NOTE for the main chat (SV/CP/fleet regression push)

**Date:** 2026-08-01
**Session:** fork (branched from main). READ THIS before touching SV/CP/fleet.

**Context / standing rule (user-set):** The SV/CP visit + external-fleet flows
were tested working-perfect ~early–mid July 2026. While other modules were
worked on, some of that code drifted and REGRESSED. All the recent SV/CP/fleet
bugs are these regressions surfacing. Goal = RESTORE known-good behavior, not
redesign. Method: (1) git-blame the working-era code before changing a function,
(2) impact-check across modules (SV/CP/fleet share setOutcome, postponeSiteVisit
Core, fieldVisitOtp arrival OTP, completeVisit, clientPlaceVisits↔siteVisits,
isCpVisit routing), (3) surgical fixes that leave working paths untouched.

**A large batch of SV/CP/fleet issues + edge cases is incoming to THIS fork.**
The fork is owning the SV/CP/fleet regression cleanup. Main chat: please avoid
editing the files listed below out from under it; coordinate here.

**This fork's UNCOMMITTED work (dev only — NOT prod, NOT committed):**
- App (Mconnect `merge`) — uncommitted, dev-pointed APK built against
  next-spaniel-814: TripNavigationFragment.kt (gift-distribution setOutcome
  arrivalPhotoStorageId, sv_cum_cp routing fix, client-address card),
  CompleteCpVisitBottomSheet.kt (gift + sv_cum_cp lock-detect), GeoTrackApi.kt
  (SetOutcomeRequest.arrivalPhotoStorageId), fragment_trip_navigation.xml.
  (Already PUSHED earlier to merge: QR crash da17459, SV manual-close f7bf8c8d,
  SV follow-up surfacing 93f1c087.)
- MMS (`max`) — uncommitted, DEPLOYED to DEV next-spaniel-814 only via
  `convex dev --once` (prod api-mfpl/convex-mfpl untouched): fieldVisitOtp.ts
  (DEV_OTP_BYPASS widening — dev-only, never set the flag on prod),
  clientPlaceVisits.ts (setOutcome arrivalPhotoStorageId attach), http.ts (CP
  setOutcome route forward), siteVisits.ts (postponeSiteVisitCore transition list
  broadened — likely RESTORING a regression; verify vs July working era).
- These backend + gift/sv_cum_cp bits are GENUINE PROD bugs too — to be folded
  into MMS + pushed to prod once the user verifies on dev.

See memory: svcp-fleet-regression-guard.

### Session 111s — Status update: drop Note field + play voice note in MMS

**Date:** 2026-08-01 · continuation
**Repos:** travel-desk (`aizen` 098e785, pushed) · manjusitedevelopment (`max` 70023b59, pushed + deployed to next-spaniel)

- Client complaint: voice note "not working and sending". Root cause = NO
  VISIBILITY (upload route /api/travel-desk/storage/upload just ctx.storage.store
  any blob — audio fine; submitStatusUpdate stores travelDeskStatusVoiceId). Fix =
  surface it in MMS.
- travel-desk: removed the "Note (optional)" textarea + reasonText from
  status-update-dialog.tsx — voice note is the only reason capture now.
- MMS backend: siteVisits enrich() now resolves travelDeskStatusVoiceUrl =
  ctx.storage.getUrl(travelDeskStatusVoiceId) (only when present). Verified:
  siteVisits.get returns the key. Deployed to next-spaniel.
- MMS web: site-visit-detail-page.tsx renders a "Travel desk update" card (reason
  Cancelled/Call not connected/Postponed + agency/driver + time + <audio> player).
- BACKEND CAVEAT (flagged to user): travel-desk portal .env →
  NEXT_PUBLIC_CONVEX_SITE_URL=https://api-mfpl.theairix.com (PRODUCTION), NOT
  next-spaniel. So status updates + voice notes submitted via the portal hit
  PROD (and Cancel/Postpone affect REAL SVs); they won't appear in the MMS web
  (next-spaniel). For the client, both TD + MMS are on mfpl, so the flow works once
  this code is deployed to prod (teammate). Couldn't end-to-end test voice on
  next-spaniel (portal on mfpl + mic blocked in preview browser).
- All tsc clean (convex/web/travel-desk).

### Session 97 - SV Bundle 1: edge-case audit + regression fixes (in progress)

**Date:** 2026-08-01
**Session:** fork. Task = fix SV edge cases in convex/marketing/siteVisits.ts,
all SV tests must pass. Baseline `npx vitest run convex/siteVisit` = 2 FAILING.

**Regression A (FIXED):** `siteVisitFollowupOutcome.test.ts:66` — my earlier
commit `a1705a2a` widened postponeSiteVisitCore to allow on_counselling/
picked_from_site/dropped, breaking the locked "postpone is pre-counselling only"
design (`4991ae66`). REVERTED to [scheduled,client_started,picked_up,on_site] in
the working tree. Test now green. IMPLICATION: the mobile "Follow up" must route
post-counselling through setOutcome(follow_up), NOT postpone — the earlier app
"fix" (route follow-up→postpone) was itself wrong. App follow-up needs the
linked fix (Mconnect: persistSvFollowUp → setSiteVisitOutcome(follow_up,
followupDueDate); + /setOutcome route must forward followupDueDate).

**Regression B (NEEDS MAIN-CHAT DECISION):** `siteVisitCabLifecycleOverride.test
.ts:601` — commit **`52dd3614` "fleet billing parity"** (MAIN CHAT's feature)
added a `billingComplete` gate to `isTravelDeskTaskComplete`
(convex/lib/travelDeskProof.ts:83-96), so an external-driver trip stays
travelDeskTaskStatus="pending" even AFTER end-proof — the test expects
"completed" on driver proof (both driver + agency views). This is the main
chat's committed billing work; recommended fix = drop the billing factor from
the DRIVER-task completion (track billing separately), but NOT reverting it
unilaterally — awaiting user/main-chat call.

**Edge-case audit (task's "fix or documented decision" per case):**
2 reassign, 3 setOutcome-without-drop (do NOT add guard — breaks tests), 4 stale
alert (never existed), 5 correctOutcome, 6 auto_confirmed (state doesn't exist),
7 WhatsApp (runAfter post-commit), 8 reassign-notify, 9 markPickedUp, 10 cab
permission (test-locked), 11 IRIS convert — ALL correct/known-good, NO fix.
12 rollup: patchSiteVisitStatsForChange called only on create/update/reassign/
postpone/delete; 13 status-changing mutations omit it (pre-existing, affects
byStatus only). Task asks to add — will add the one-liner per mutation.

### Session 97 (cont.) - SV Bundle 1: both regressions fixed, suite GREEN

- Regression A (postpone a1705a2a): REVERTED to pre-counselling statuses. Green.
- Regression B (cab billing gate): resolved by UPDATING the stale test, NOT
  un-gating. Discovery: un-gating breaks `travelDeskProof.test.ts:60`, which
  intentionally locks "external trip pending until agency billing finalized"
  (added by 52dd3614). So the billing gate stays; instead
  `siteVisitCabLifecycleOverride.test.ts` (test @506) now finalizes billing
  up-front (t.run patch travelDeskBillingCompletedAt) so it isolates the
  end-proof gate (its subject). User approved "update the stale cab test".
- `npx vitest run convex/siteVisit convex/travelDeskProof` = 6 files / 26 tests
  ALL PASS.
- Edge cases 2-11: documented no-fix (already correct/known-good). 3
  (setOutcome-without-drop) MUST NOT get a guard (breaks followup+cab tests).
- Item 12 (rollups): delegated subagent adding patchSiteVisitStatsForChange to
  the 13 status-changing mutations (markPickedUp/ClientStarted/ArrivedSite/
  OnCounselling/OnCounsellingFromQr/PickedFromSite/Dropped/advanceCabLifecycle/
  NoShow/setOutcome/correctOutcome/convertToBooking/cancelSiteVisitCore) +
  re-running suite. Pre-existing gap (byStatus only), not a regression.
- LINKED APP FIX still owed (Mconnect): since postpone no longer accepts
  on_counselling, the mobile "Follow up" must call setSiteVisitOutcome(
  outcome=follow_up, followupDueDate) instead of postponeSiteVisit; the
  /marketing/siteVisits/setOutcome HTTP route must forward followupDueDate.
- All uncommitted; NOT deployed to prod. siteVisits.ts also carries the main
  chat's uncommitted enrich(travelDeskStatusVoiceUrl) — untouched by us.

### Session 98 - Trip Details scroll fix (button was stuck / cut off)

**Date:** 2026-08-01
**Session:** fork. App-only (Mconnect). Uncommitted; dev-pointed APK.

- After adding the Client Address card (session 95), the fixed non-scrolling
  ConstraintLayout overflowed and the Start Trip button was cut off with no way
  to reach it (as flagged then).
- Fix (fragment_trip_navigation.xml): wrapped tripInfoCard + clientAddressCard +
  mapCard + tripProgressCard in a NestedScrollView (id tripContentScroll,
  fillViewport, top→topBar bottom, bottom→bottomActions top; inner
  ConstraintLayout wrap_content, tripInfoCard top retargeted topBar→parent).
  bottomActions now PINNED (constraintBottom_toBottomOf parent) so Start Trip /
  swipe is always visible.
- Fix (TripNavigationFragment.onMapReady): uiSettings.isScrollGesturesEnabled =
  false so the in-scroll map doesn't swallow vertical drags.
- Build OK. No backend change.

### Session 99 - Trip Details swipe button "too low" (edge-to-edge inset)

**Date:** 2026-08-01
**Session:** fork. App-only (Mconnect). Uncommitted; dev-pointed APK.

- After pinning bottomActions to parent bottom (session 98), the swipe/Start-Trip
  button sat under the gesture nav bar — app is edge-to-edge
  (MainActivity setDecorFitsSystemWindows(false)).
- Fix: TripNavigationFragment applies the app's existing
  `BottomActionInsets.applyAboveSystemNavAndTabs(R.id.bottomActions)` helper
  (reserves navigationBars inset + tab bar height + breathing room). Robust
  across devices; matches how chat/other screens handle it. Build OK.

### Session 100 - Travel-desk driver page: Call client + Call driver above address

**Date:** 2026-08-01
**Session:** fork. travel-desk (`aizen`), driver/trips/[token]/page.tsx only.
Uncommitted. NOTE: trips/[id]/page.tsx is the MAIN CHAT's file — untouched.

- Added a "Contact" row (grid grid-cols-2) with Call client (tel:clientPhone) +
  Call driver (tel:driverPhone) side by side, placed ABOVE the Pickup address
  cell. Each button conditional on its phone (empty <span/> keeps left/right).
- Removed the old standalone "Call client" grid cell (moved above); Update status
  kept. tsc clean.
- Deploy: aizen → traveldesk.aivida.in is teammate-promoted; change is local/
  uncommitted so it won't show on the live site until pushed + promoted.

### Session 101 - Travel-desk: horizontal start/end proof + unstick driver upload

**Date:** 2026-08-01
**Session:** fork. travel-desk (`aizen`). COMMITTED + PUSHED (cc6d845).

- trips/[id]/page.tsx (admin trip detail, "Mark progress"): at the dropped
  stage (reached===5) the "Trip start proof" and end evidence were stacked
  vertically. Now they sit side by side in a `grid gap-4 md:grid-cols-2` —
  Trip start proof (start km + start dashboard image) LEFT, Trip end proof
  (End km + Toll/Beta + end dashboard image) RIGHT. Standalone start-proof box
  condition changed to `reached >= 3 && reached !== 5` so it isn't duplicated
  at stage 5. Isolated to this admin view — zero cross-module impact.
- compress-image.ts (SHARED by driver picker + admin trips/page.tsx picker):
  `createImageBitmap` and `canvas.toBlob` can HANG (never settle) on odd HEIC
  blobs on some mobile browsers. That left DashboardImagePicker's `busy` flag
  stuck true → every file input `disabled` → "driver came back and couldn't
  upload." Wrapped both in an 8s `withTimeout` that rejects → catch falls back
  to the original file, so `busy` always clears. Happy path unchanged for all
  callers (worst case: a slightly larger original file uploads).
- driver page: Call client/Call driver row (from session 100) kept.
- Verified: `npx tsc --noEmit` clean; cleared stale `.next` (Turbopack cache
  showed a phantom status-update-dialog parse error at an old line number —
  gone after rm -rf .next + restart); /trips route compiles + renders logged in.
- Stage-5 horizontal layout not exercisable without a live dropped trip; it's a
  pure Tailwind restructure that typechecks and the route compiles.

### Session 102 - Travel-desk Status Update "invalid response (404)" fix

**Date:** 2026-08-01
**Session:** fork. travel-desk (`aizen`). COMMITTED + PUSHED (7a5487e).

- Report: choosing any option in the Status Update form + Submit → "The server
  returned an invalid response (404)."
- FALSE START: first hypothesised "Convex route not deployed to prod." Probed
  prod directly — `POST api-mfpl.../trips/status-update` → **401** (live), not
  404. So the Convex route + `submitStatusUpdate` mutation ARE deployed. Wrong
  theory; corrected course.
- REAL root cause: `tripsRequest()` (travel-desk-api.ts:498) fetches RELATIVE
  paths → they hit the PORTAL's own Next.js API route handlers under
  `src/app/api/travel-desk/trips/*`, which proxy to Convex. Every trips endpoint
  has a handler EXCEPT `status-update` (the newest feature) → Next.js 404 →
  parseJson renders "invalid response (404)."
- Fix: added `src/app/api/travel-desk/trips/status-update/route.ts`, a straight
  POST proxy to `${convexSiteUrl()}/api/travel-desk/trips/status-update`,
  mirroring the sibling handlers (e.g. end/route.ts). Cross-checked: all 18
  tripsRequest endpoints now have matching route handlers (status-update was the
  only gap). Purely additive; no existing path touched.
- Verified: tsc clean; dev route now returns 400 (reaches Convex) not 404.
- DEPLOY NOTE: travel-desk frontend change only — NO Convex deploy needed (route
  already live on prod). Takes effect once the travel-desk portal is
  redeployed/re-promoted (traveldesk.aivida.in is teammate-promoted). The same
  redeploy also ships the earlier stale-UI fixes the screenshot showed missing
  ("Call not connected" rename, removed "Note (optional)", no-issue voice hide).

### Session 103 - Travel-desk: agency staff billing/Settings access glitch

**Date:** 2026-08-01
**Session:** fork. travel-desk (`aizen`). COMMITTED + PUSHED (913b956).

- Report: staff granted "Billing" still don't see billing features (Settings +
  Trips Billing tab); a no-access staff opening Settings "glitches" straight to
  Trips instead of seeing a "you have no access" notice.
- Root cause (single bug): auth.tsx guard bounced EVERY `agency_staff` off
  `/settings` (and `/staff`) UNCONDITIONALLY, before the page rendered — it
  never checked `canBill`. So granted staff couldn't reach the rate sheet, and
  no-access staff got redirected to Trips instead of the settings page's own
  "Permission required" notice.
- Verified the rest of the chain is already correct: settings page self-gates on
  `canDoTravelDeskBilling(user)` (rate sheet vs notice); Trips "Billing" tab
  gates on `canBill` (trips/page.tsx:571-572, tab list ~1170); backend sends
  fresh `canBill` on every validate (resolveTravelDeskSession re-reads the staff
  doc → canBill: staff.canBill === true). So a granted staff picks it up on
  reload; no stale-session code bug.
- Fix (travel-desk only, no backend):
  * auth.tsx: drop `/settings` from the agency_staff redirect block; keep
    `/staff` admin-only. Page now decides what staff see.
  * Sidebar.tsx: split nav — `agencyStaffNav` (Trips/Vehicles/Drivers/Settings,
    NO Staff) for agency_staff; `agencyAdminNav` (adds Staff) for agency admin.
    Prevents the Staff link glitching for staff the same way.
- Blast radius: role-scoped. Admin + driver nav unchanged (verified in browser:
  admin still sees Trips/Vehicles/Drivers/Staff/Settings and opens the rate
  sheet). tsc clean.
- DEPLOY NOTE: frontend-only; takes effect on the next travel-desk portal
  redeploy/re-promote. Granted staff may need one page refresh so the session
  re-validates and canBill flips true.

### Session 104 - SV postpone leaves closed visit stuck "pending confirmation"

**Date:** 2026-08-01
**Session:** fork. MMS/Convex (`max`). COMMITTED + PUSHED (fd4d5e46). NOT deployed.

- Verify-one-by-one pass, item 1 (Postponed → postponed again). Traced
  postponeSiteVisitCore end-to-end.
- FINDINGS: new SV created correctly (all fields copied, scheduled + confirmed,
  stats/insight/WhatsApp fired); original closed (status: postponed). BUT the
  close patch never touched confirmationStatus. CP-originated SVs start
  "pending" (siteVisits.ts:2365), so postponing one left it postponed + pending
  = dangling.
- LEAK (the "fuse"): the dangling pending inflated pending-confirmation COUNTS
  only — fixedCountForDate (its terminal set omitted "postponed"!),
  approximateSiteVisitStats + siteVisitRollupDelta (bucket by confirmationStatus
  w/ no status guard). The pending-confirmation LIST (:1420) already excluded
  postponed, so only the metrics drifted. Tell: the list at :1425 excluded
  postponed but the count at :1097 forgot to.
- postpone-again chain: works; only the FIRST original (if CP-pending) dangled;
  subsequent postpones act on auto-confirmed rows.
- FIX (source): close patch now also sets confirmationStatus:"confirmed" +
  confirmedAt. Drops it from every pending counter at once; covers BOTH callers
  (MMS postpone + travel-desk "Postponed"). Also added "postponed" to
  fixedCountForDate's terminal set (defense-in-depth).
- TEST: new regression test runs the real postponeVisit on a pending visit,
  asserts closed→postponed+confirmed, fresh→scheduled+confirmed, and the
  postpone-again chain. `npx vitest run siteVisitFollowupOutcome.test.ts` → 4/4
  pass. `tsc -p convex` clean for my files.
- PRE-EXISTING (NOT mine, flagged): convex/http.ts:11068 passes
  arrivalPhotoStorageId to marketing.clientPlaceVisits.setOutcomeAndSendSiteVisitWhatsApp
  which doesn't declare it → TS2353. Committed code, blocks a convex deploy.
  Separate torn wire (arrival photo never reaches that action). Left untouched.

### Session 105 - SV reassign didn't notify the OUTGOING staff (item 2)

**Date:** 2026-08-01
**Session:** fork. MMS/Convex (`max`). COMMITTED + PUSHED (3c010458). NOT deployed.

- Verify-one-by-one item 2: reassign during active visit (picked_up/on_site).
- FINDINGS: reassign runs during active visits (only TERMINAL statuses blocked).
  NEW staff notified properly — notifyStaffWithPush = in-app notifications row +
  FCM push (pushNotifications.sendToStaff). But OLD staff got NOTHING: the
  targets array only carried the new staffId for changed roles; before-id never
  passed to the notifier. Old staff only saw it passively (visit drops off their
  scoped list on refresh). Torn wire: mid-visit the field person is un-assigned
  with zero heads-up.
- FIX: notifySiteVisitReassignment now takes {staffId, previousStaffId, role}
  per role + visit status. Fires only on change; pings incoming AND outgoing;
  skips a "removed" ping for anyone still assigned to another role (role swap).
  Active statuses (client_started/picked_up/on_site) use handover wording. New
  notification type "site-visit-unassigned". reassign passes before/after ids +
  status. Additive; single caller; no data-write/guard change.
- TEST: convex/siteVisitReassign.test.ts — on_site reassign → new incharge gets
  site-visit-assigned "Site Visit Handover"; old incharge gets
  site-visit-unassigned "Site Visit Handed Over"; unchanged BDO gets nothing.
  33/33 SV-suite tests pass. tsc clean (pre-existing http.ts:11068 untouched).
- NOTE: backend change; won't reach users until MMS/Convex prod deploy (blocked
  by the pre-existing arrivalPhotoStorageId type error — still flagged, item TBD).

### Session 106 - SV outcome guards: completed-reopen + stale-counselling watcher

**Date:** 2026-08-01
**Session:** fork. MMS/Convex (`max`). COMMITTED + PUSHED (f2434d51). NOT deployed.

Two items from the verify-one-by-one pass, both fixed + tested.

- ITEM 3 (setOutcome-without-cab-drop): NO guard on setOutcome is CORRECT — the
  cab return leg is decoupled (travelDesk* timestamps), and both driver endTrips
  support post-completion. BUT `mmsFleetDriverTrips.markPickedFromSite` &
  `markOnSite` wrote SV status UNCONDITIONALLY → an internal-fleet return-pickup
  after setOutcome REOPENED the completed SV (completed→picked_from_site). Fix:
  only advance status from the expected active predecessor, else preserve
  visit.status (mirrors endTrip's existing guard). travelDesk* stamps unchanged.
  External travelDeskDriverTrips already safe (writes only travelDesk* fields).
- ITEM 4 (listOutcomePending): confirmed listOutcomePending is a passive pull
  query with NO time dimension, and NO cron/daily-task watched on_counselling
  staleness. Added `marketing/siteVisits:remindStaleCounsellingOutcomes`
  (internalMutation) + 6h cron: on_counselling + consultingAt >24h + no outcome →
  ensure ONE daily task (`<id>:outcome`) for BDO/incharge (notifies via
  notifyTaskAssigned). Extended markSiteVisitDailyTasksCompleted to clear
  `:outcome` too → auto-closes on outcome/cancel/postpone. Deduped (pre-check),
  skips fresh visits.
- TESTS: convex/siteVisitOutcomeGuards.test.ts — (a) markPickedFromSite on a
  completed cab SV keeps status "completed" + stamps travelDeskPickedFromSiteAt;
  (b) watcher creates a task only for the >24h visit, idempotent, clears on
  setOutcome. 35 SV/fleet/daily-task tests pass; tsc clean.
- Setup notes for future fleet tests: mms-fleet driver = staff designation
  "Driver" + visit.driverPhone===staff.phone + vehicleId set + no external
  travelAgencyId + scheduledDate<=today. vehicles insert needs {vehicleNumber,
  status}.
- STILL pending prod: the pre-existing http.ts:11068 arrivalPhotoStorageId type
  error blocks `convex deploy` — none of sessions 104/105/106 can reach users
  until that's fixed + a prod deploy runs.

### Session 107 - App: outcome form greyed at picked_from_site (stale completedAt lock)

**Date:** 2026-08-01
**Session:** fork. Mconnect app (`merge`). COMMITTED + PUSHED both remotes (2ff9282e).

- Report (screenshot): cab SV at PICKED FROM SITE, no outcome recorded, but the
  Outcome buttons (Converted/Not Interested/Follow up) were greyed. User wants
  them active at on_counselling and every status after (backend setOutcome allows
  on_counselling/picked_from_site/dropped).
- Traced SiteVisitOverviewFragment.updateStepper: the enable-GATE already allows
  on_site→dropped (cab: activeIndex>=3; own: ownActiveIndex>=2). picked_from_site
  maps to index 5, so the gate enables it. The disable came from
  isOutcomeLocked = isTerminalOutcome(visit), which locked on
  `visit.completedAt != null`. For cab visits the fleet return-leg advances the
  status while a stale completedAt lingers (see the decoupling + session 106
  reopen bug), so a still-pending outcome got locked.
- FIX: dropped `completedAt` from isTerminalOutcome. Genuine terminals are still
  caught by terminal status / recorded outcome / convertedBookingId / cancelledAt,
  so this only unblocks the outcome-still-pending case. (initial-args path at
  L393 already uses isTerminalOutcomeStatus(rawStatus) only — unaffected.)
- Build: :app:compileDebugKotlin BUILD SUCCESSFUL. App-only, no backend.
- Caveat: if a visit has a genuinely recorded `outcome`, it stays locked
  (correct). This fix targets the completedAt-set-but-no-outcome state.

### Session 101 - Call client/driver buttons: always-visible + pushed to aizen

**Date:** 2026-08-01
**Session:** fork. travel-desk (aizen fd52d39, pushed origin).

- Prior turn's conditional Call client|Call driver row was committed+pushed but
  gated on trip.clientPhone/driverPhone — user "still not showing". Two causes:
  (a) live traveldesk.aivida.in deploy lag from aizen, (b) trip data may lack
  phones (external agency trip → visit.driverPhone / lead.mobileNumber null).
- Fix: made BOTH buttons always render (grid-cols-2 above pickup address),
  greyed + pointer-events-none + aria-disabled when the number is missing, so a
  data gap can't hide the affordance. tel: link when present. tsc clean.
- Committed + pushed aizen (fd52d39). Appears on traveldesk.aivida.in once that
  branch deploys/promotes; hard-refresh if cached. driver/trips/[token] only;
  did NOT touch trips/[id] (main chat's file).

### Session 108 - Assign-vehicle dialog: driver name/phone read-only from vehicle

**Date:** 2026-08-01
**Session:** fork. MMS/Convex (`max`). COMMITTED + PUSHED (5b6bafe1, 5a882d71). NOT deployed.

- Request: the fleet assign-vehicle dialog showed editable Driver name / Driver
  phone inputs (with fleet-driver autocomplete + manual override). Driver should
  come from the selected vehicle and be NON-editable; show it only after a
  vehicle is picked.
- File: features/fleet/components/assign-vehicle-dialog.tsx.
  * Driver name/phone are now read-only+disabled Inputs bound to
    selectedVehicle.defaultDriverName / defaultDriverPhone. Placeholders:
    "Select a vehicle first" (none picked) / "No driver set on this vehicle".
  * Submit sends selectedVehicle defaults directly (no manual override).
  * Removed dead machinery: driverName/Phone state + manual flags,
    driverSuggestionsOpen, the autofill useEffect, the driverSuggestions memo,
    the fleetDrivers + listBusyDriverPhones queries (autocomplete-only),
    normalizePhoneDigits, FleetDriverRow type, resolveDriverField import. Fewer
    queries on open.
- Test: replaced the obsolete "manual override preserved" test with
  "driver fields are read-only and follow the selected vehicle" (asserts
  disabled + swaps driver on vehicle change). 7/7 dialog tests pass.
- Verify: component tsc-clean, eslint-clean, convex tsc clean. NOTE: root
  `tsc --noEmit` flags convex *test* files (by_staffId index / a merged
  attendance test) — pre-existing root-tsconfig-vs-convex scoping quirk, not
  from this change; convex/tsconfig.json is authoritative and clean.
- Deploy: MMS frontend; reaches users on the next web deploy.

### Session 109 - App: postponed SV tag + outcome form gated on counselling (QR)

**Date:** 2026-08-01
**Session:** fork. Mconnect app (`merge`). COMMITTED + PUSHED both remotes (89d20b6c).

- Report (screenshot): postponed a cab SV before on_site; the postponed SV detail
  showed the stepper/header on ON SITE (no POSTPONED tag), and the outcome form
  was available too early. Also asked: outcome form should open only on/after
  reaching on_counselling by QR scan, and stay open through later statuses until
  outcome recorded.
- Root cause: SiteVisitOverviewFragment drives header + stepper off the
  DRIVER-BOOSTED step index (computeWebParityStepIndex max(base, travelDesk*
  boost)). "postponed" wasn't a closed state → relabelled from the boosted step
  (ON SITE). And the outcome gate used the boosted activeIndex>=3 (on_site).
- FIX 1 (postponed display): bindStatusHeader adds POSTPONED label+colour;
  bindStatusHeaderForStep treats postponed as closed (keeps its tag);
  computeWebParityStepIndex + computeOwnStepIndex skip the driver boost for
  postponed/cancelled (closed SV can't advance).
- FIX 2 (outcome gate): new member outcomeStatusEligible = isOutcomeStatusEligible(
  status) = status in {on_counselling/consulting, picked_from_site, dropped} —
  mirrors backend setOutcome transition set, excludes on_site & earlier. Both cab
  and own gates now use it (+ isFleetOutcomePending bypass) instead of the
  boosted index. Toast reworded to "Outcome opens after the client QR scan".
- Build: :app:compileDebugKotlin SUCCESSFUL. App-only; reaches device on next APK.
- NOT DONE / caveats: the "close it out of the active list" part is LIST-level
  (site-visits list tabs), not touched — the postponed original still needs to
  land in a closed/history tab rather than an active one; flag for follow-up.
  Verified postponeSiteVisitCore does NOT copy travelDesk* timestamps, so the NEW
  scheduled SV is fresh (won't show on_site). Visual QA needs the app on device.

### Session 102 - GeoTrack GPS-loss on app update (31-Jul underpaid-allowance bug)

**Date:** 2026-08-01
**Session:** fork. App (Mconnect) + MMS backend (tamper). Deployed dev; uncommitted.

- Diagnosis (from the web/convex chat): APK update killed GeoTrackService and it
  never restarted → GPS trail collapsed → 27km paid as 5.23km. Root fix is in the
  ANDROID app (this fork's domain), which that chat couldn't reach.
- Fix (app): BootReceiver now also handles `ACTION_MY_PACKAGE_REPLACED` (manifest
  intent-filter added) — same resume path as BOOT_COMPLETED via
  GeoTrackBootstrapSync.sync, which re-checks fine/background/activity-recognition
  permissions and only (re)starts the service inside a clock-in tracking window.
  Also enqueues an `APP_UPDATED` event so future diagnoses have the explicit
  update signal the web chat had to infer.
- Fix (backend, MMS): added `APP_UPDATED` to tamper eventType union
  (geotrack/tamper.ts), TAMPER_SEVERITY (MEDIUM), action label, AND the
  schema `tamperEvents.eventType` union (schema.ts) so the insert validates.
  Deployed to dev next-spaniel-814. app :app:assembleDebug OK; tamper tsc clean.
- Permission re-check on launch already exists (GeoTrackBootstrapSync.sync +
  BackgroundPermissionsGateDialog); the new update-trigger runs it too.
- PRE-EXISTING typecheck break flagged (NOT this task): convex/http.ts:11068
  passes arrivalPhotoStorageId to clientPlaceVisits.setOutcome — source is
  consistent (both committed) + deploy works, but committed _generated/api.d.ts
  is stale (session-92 gift fix committed without regen). `convex dev --once`
  codegen did not refresh it. Needs a proper _generated regen + commit.

### Session 110 - App: SV "Follow up" is an outcome, not a postpone

**Date:** 2026-08-01
**Session:** fork. Mconnect app (`merge`). COMMITTED + PUSHED both remotes (55a35db9).

- Report: SV "Follow up" (with a follow-up date) errored "cannot postpone visit
  from status on_counselling". User clarified: Follow up ≠ Postpone. Postpone
  recreates an unassigned SV on a new date (needs re-assigning vehicle). Follow
  up should complete the SV and create a follow-up CALL for the assigned
  telecaller/LMO to discuss the client's decision.
- Root cause: CompleteCpVisitBottomSheet.persistSvFollowUp DELIBERATELY routed
  SV follow-up through geoApi.postponeSiteVisit (comment claimed setOutcome
  follow_up 500s at scheduled/on_site). But postpone rejects on_counselling, and
  it reschedules instead of creating the telecaller followup.
- FIX (app-only): persistSvFollowUp now calls setSiteVisitOutcome(
  outcome=follow_up, followupDueDate=<date>, notes=<reason>) after a best-effort
  markSiteVisitOnCounselling. Backend setOutcome(follow_up) completes the SV +
  inserts telecallerFollowups due on that date; the /siteVisits/setOutcome HTTP
  route already forwards followupDueDate/Time (http.ts:12176-77). Added
  followupDueDate/followupDueTime to SetSiteVisitOutcomeRequest. Removed unused
  PostponeSiteVisitRequest import.
- Works now because session-109 gated the outcome buttons to on_counselling+, so
  setOutcome(follow_up) always accepts the status.
- Form labels were already SV-aware (applySiteVisitOutcomeMode: "Follow up" /
  "Follow-up date" / "Why does this client need a follow up").
- Build: :app:compileDebugKotlin SUCCESSFUL. Reaches device on next APK.
- The pre-counselling reschedule (Postpone) still lives in
  PostponeSiteVisitBottomSheet — unchanged.

### Session 111 - App: cab SV stays fleet-pending after outcome (stepper)

**Date:** 2026-08-01
**Session:** fork. Mconnect app (`merge`). COMMITTED + PUSHED both remotes (7de2d15c).

- Report: recording SV outcome (follow_up) marked the trip "done" even though the
  fleet side (picked_from_site/dropped) wasn't updated. Should stay pending on
  fleet until the driver completes — "completed as sv but not as fleet".
- VERIFIED BACKEND IS CORRECT: fleetProgressState returns "pending" for outcome-
  only (isTravelDeskTaskComplete needs travelDeskEndedAt) -> In-progress tab, not
  Complete. Driver lists (mmsFleetDriverTrips.listForStaff / travelDeskDriverTrips)
  keep the trip (drop only cancelled/ended), so the driver can still complete.
  So only the APP's SV stepper mislabelled it done.
- Root cause: computeWebParityStepIndex = max(mapStatusToStepIndex(status),
  driverBoost, ...). status=completed -> 7 (Done), jumping past the pending fleet
  steps.
- FIX (user picked "keep fleet steps pending"): for cab visits (!isOwnVehicle) and
  fleet NOT ended (travelDeskEndedAt==null), cap the SV-status contribution at
  step 4 (counselling): statusContribution = min(baseFromStatus, 4). Fleet steps
  5/6/7 then come only from the driver's travelDesk* timestamps. Once ended, full
  status contributes (Done shows on completed+ended). Own-vehicle unchanged.
  (Sits after the session-109 postponed/cancelled early-return guard.)
- wireBookingResult's optimistic updateStepper(6) left as-is: it dismisses the
  sheet immediately; persistent view recomputes on reload.
- Build: :app:compileDebugKotlin SUCCESSFUL. Reaches device on next APK.
- Header still shows COMPLETED (SV outcome) while stepper shows fleet-pending —
  intended "SV done / fleet pending". If a fleet-pending header label is wanted,
  follow-up.

### Session 112 - App: follow-up "postponed" subtitle + stale-APK diagnosis

**Date:** 2026-08-01
**Session:** fork. Mconnect app (`merge`). COMMITTED + PUSHED both remotes (8fa1fdfc). Fresh APK sent to user.

- Report: SV Follow up form STILL showed "cannot postpone visit from status
  on_counselling" + subtitle "Why is the visit being postponed."
- DIAGNOSIS: the postpone TRANSITION error was already fixed session 110
  (persistSvFollowUp → setSiteVisitOutcome(follow_up), verified still intact at
  L4448). No postpone call remains in the follow-up flow. So the DEVICE is
  running an OLD APK (pre-session-110). Built assembleDebug and sent
  app-debug.apk to the user to install.
- REAL LEFTOVER FIXED: the reason subtitle in outcome_body_postpone.xml (L58-66)
  had NO id, so applySiteVisitOutcomeMode couldn't override it → SV follow-up
  mode kept showing "Why is the visit being postponed." Added
  id=tvPostReasonSubtitle; applySiteVisitOutcomeMode now sets "Note the client's
  decision so the telecaller can follow up."
- The separate PostponeSiteVisitBottomSheet (pre-counselling reschedule, "Postpone
  SV" button) is unrelated and correct.
- Build: :app:assembleDebug SUCCESSFUL (full APK). 
- NOTE for future: app fixes are compile-checked but the user tests on-device —
  when a fix "still" fails, suspect stale APK; assembleDebug + send it.

### Session 113 - SV handoff GM approver went to cross-dept IT/HR (PAVITHRA.P)

**Date:** 2026-08-01
**Session:** fork. MMS/Convex (`max`). COMMITTED + PUSHED (db23e20d). NOT deployed.

- Report: an immediate SV confirmation ("Awaiting Confirmation : PAVITHRA.P")
  went to PAVITHRA.P, IT-team HR, unrelated to the marketing SV.
- Root cause: resolveHandoffManagerStaffId (outOfStationHandoffs.ts) resolves the
  GM approver as explicit gmStaffId → reportingTo chain walk → org-wide first
  staff whose DESIGNATION matches /gm|general manager/. The handoff was created
  with no GM selected, so it hit the org-wide fallback — which is department-
  BLIND — and PAVITHRA.P (IT "General Manager", first by employeeId) was picked.
  Self-heal never fixed it because she counts as a GM by designation.
- FIX (user chose "same department as telecaller"): scope the chain walk AND the
  org-wide fallback to the telecaller's department via deptOk(); a cross-dept GM
  is ineligible. Explicit gmStaffId stays authoritative; if telecaller has no
  department, behaves as before (no failure). Self-heal branch re-resolves when
  the current manager's department != telecaller's (repairs existing PAVITHRA.P
  handoffs on next "Send to GM").
- TESTS (outOfStationHandoffs.test.ts, +2): IT GM ordered first by employeeId →
  new handoff resolves to the marketing GM; existing IT-GM handoff re-resolves to
  the marketing GM on retry. 5/5 pass. convex tsc clean.
- NOTE: the "Awaiting Confirmation" message is built client-side
  (site-visits-list-page.tsx buildHandoffWhatsAppText, manager?.name) and shared
  via wa.me; fixing manager resolution fixes the name shown + the GM daily task.

### Session 103 - SV "Follow up" ≠ Postpone (design clarification + pending fix)

**Date:** 2026-08-01
**Session:** fork. LOG-ONLY this turn (design capture). No code changed yet.

**User clarified the intended design (IMPORTANT — do not conflate these):**
- **Postpone** = a PRE-COUNSELLING reschedule. Recreates a NEW siteVisits row for
  the new date WITHOUT an assigned vehicle (so it needs re-assigning a vehicle).
  Backend: `postponeSiteVisitCore` (allowed statuses scheduled/client_started/
  picked_up/on_site only — restored in Session 97, locked by
  siteVisitFollowupOutcome.test.ts).
- **Follow up** = a DIFFERENT, post-SV OUTCOME. It must NOT recreate an SV / need a
  vehicle. It records the SV outcome as follow_up AND creates a FOLLOW-UP CALL
  task for the LMO/telecaller assigned to that client (to discuss the client's
  decision after the SV). Backend already does this: `siteVisits.setOutcome`
  with outcome="follow_up" completes the current SV and inserts a
  `telecallerFollowups` row (dueDate = followupDueDate) for the assigned
  telecaller. Allowed from on_counselling/picked_from_site/dropped.

**Current bug:** the mobile "Follow up" sheet routes to `postponeSiteVisit`
(persistSvFollowUp) → now throws "cannot postpone visit from status
on_counselling" (screenshot). That routing is the mistake.

**Pending fix (app + route; this is the Session-97 "linked app fix"):**
- Mconnect `CompleteCpVisitBottomSheet.persistSvFollowUp` → call
  `setSiteVisitOutcome(outcome="follow_up", followupDueDate=<nextDate>)` instead
  of `postponeSiteVisit`. (Reason/notes → the follow-up notes.)
- `SetSiteVisitOutcomeRequest` needs `followupDueDate`/`followupDueTime`; the
  `/api/marketing/siteVisits/setOutcome` HTTP route (convex/http.ts ~12166) must
  forward them (it currently drops followupDueDate — setOutcome mutation already
  accepts them).
- Keep Postpone as-is (pre-counselling reschedule). DO NOT re-widen
  postponeSiteVisitCore to "fix" follow-up — that re-breaks the locked test.

**MAIN CHAT NOTE:** if you touch SV follow-up/postpone on web, follow the same
split: Follow up = setOutcome(follow_up)+telecaller task; Postpone = reschedule.

### Session 114 - QR counselling: real incharge locked out under duplicate staff id

**Date:** 2026-08-01
**Session:** fork. MMS/Convex (`max`). COMMITTED + PUSHED (217b8516). NOT deployed.

- Report: CHITRA.P (Site Incharge) scanned the client QR; app showed "You don't
  have access ... contact the Site Incharge (CHITRA.P)" — told to contact
  herself. canStartCounselling from backend = false.
- Traced: app SiteVisitCounsellingConfirmBottomSheet trusts backend
  visit.canStartCounselling (no client fallback for START). Backend getByQrPayload
  → canStartCounselling = isQrOutcomeAuthorized() && status in
  [scheduled/client_started/picked_up/on_site]. HTTP scanQr route passes
  viewerStaffId = auth.user._id = presentAuthUser(staff)._id (a real staff id).
  So the check IS correct; isQrOutcomeAuthorized only does
  inchargeStaffId===staffId || bdo===staffId → fails when the SV's stored
  incharge id and the same person's logged-in id are DIFFERENT staff records
  (duplicate/legacy rows). Not a regression (git-blame: logic stable; recent
  commit only moved the status check out).
- FIX (additive, identity-scoped): after the direct-id checks, authorize when the
  actor's normalized phone matches the assigned incharge's or BDO's phone. Only
  ever matches the SAME person; admin/scanConsulting/direct-id/outsider paths
  unchanged. Shared by getByQrPayload + markOnCounsellingFromQr.
- TEST (siteVisitQrCounselling.test.ts +1): duplicate incharge (same phone, diff
  id) → canStartCounselling=true + can markOnCounsellingFromQr; different-phone
  outsider stays false. Existing QR test still passes. convex tsc clean.
- CAVEAT: hypothesis is duplicate staff records. If the real cause is different
  (e.g. she's genuinely not the assigned incharge), need the SV id + her employee
  ids to confirm. Underlying data issue = duplicate CHITRA.P staff rows; worth a
  dedupe.

### Session 104 - Move Call client/driver to MOBILE SV overview (revert web)

**Date:** 2026-08-01
**Session:** fork. Wrong-surface correction (user uploaded wrong screenshot last time).

- REVERTED the travel-desk driver-page (driver/trips/[token]/page.tsx) Call
  client/Call driver row — restored the original single Call client below the
  address. Committed + pushed aizen (fd52d39..2a47bc8). (trips/[id].tsx tsc error
  seen there is the MAIN CHAT's file, not mine.)
- ADDED Call Client | Call Driver row ABOVE the pickup address on the MOBILE SV
  overview: fragment_site_visit_overview.xml (btnOverviewCallClient/Driver, 46dp,
  weight-1 side-by-side, ic_phone_outline) + SiteVisitOverviewFragment.kt
  (dialPhone via ACTION_DIAL, refreshCallButtons enable/grey-out by availability).
  - Client phone = visit.client/lead.mobileNumber (initial: leadPhone arg).
  - Driver phone = visit.proposedSiteVisit?.driverPhone. NOTE: getForMobileId
    maps proposedSiteVisit = full SV doc (clientPlaceVisits.ts:1466/1527), which
    has driverName/driverPhone → added those 2 fields to the app ProposedSiteVisit
    model (GeoTrackApi.kt) so Gson parses them. NO backend change needed.
  - Gotcha noted: CpVisitDetail ends at GeoTrackApi.kt:1489; the SV progress/
    vehicle/driver fields live on ProposedSiteVisit (1501+), not CpVisitDetail.
  - Build OK (dev-pointed APK). App-only.

### Session 115 - SV stepper: Reached CP node + postponed/cancelled terminal (3 surfaces)

**Date:** 2026-08-01
**Session:** fork. All 3 repos. COMMITTED + PUSHED. Billing deferred (needs user rules).

- Request: mobile stepper should match web; postponed/cancelled show reached
  prefix + terminal node (all 3: web/travel-desk/app); add a "Reached CP" step
  between Assigned and Picked-from-CP for internal+external fleet (cancellation +
  waiting charges).
- KEY FINDING (Explore agent): "Reached CP" already exists as travelDeskArrivedAt,
  written by markArrived (both mmsFleetDriverTrips + travelDeskDriverTrips), and
  travel-desk already shows "Reached client". Only web+app lacked the node. No
  schema/mutation change needed. Web already had postponed/cancelled terminal
  (terminalProgressSnapshot).
- MMS WEB (max, 8a7031b5): added "Reached CP" to CAB_STAGES + cabDriverProgressBoost
  (travelDeskArrivedAt branch) + re-indexed cabProgressState + consulting-gap.
- TRAVEL-DESK (aizen, 76b710d): admin + driver steppers now collapse
  postponed/cancelled to reached-prefix + terminal node (tripProgressView /
  tripStageView); "Reached client" -> "Reached CP". (HEAD already had the driver
  helper from another session; I wired the render.)
- APP (merge, 3439971c): 9-node cab stepper (inserted Reached CP + stepLine8),
  re-indexed mapStatusToStepIndex/computeWebParityStepIndex(+arrivedAt boost)/
  bindStatusHeader(ForStep); added travelDeskArrivedAt to ProposedSiteVisit.
  Refactored cab + own branches to node/line ARRAYS. Postponed/cancelled ->
  reached prefix + relabel-next-node-as-terminal + hide-rest (supersedes
  session-109 header-only). Removed session-109/111 early-returns; kept the
  fleet-pending cap (now min(status,5)). compileDebugKotlin OK.
- DEFERRED (workstream 3): billing — waiting charge (reachedCp->pickedUp) +
  cancellation-after-reached. NO cancellation charge exists today; standing/waiting
  is captured at markPickedFromSite by the driver + entered by agency admin.
  Waiting for the user's rate/rule numbers.
- TODO: on-device visual QA of the app stepper (terminal relabel/hide + Reached CP
  spacing); MMS web visual QA (dev server wasn't started).

### Session 105 - Time Correction marked Unavailable in app

**Date:** 2026-08-02
**Session:** fork. App-only (Mconnect). Env note: memory says next-spaniel-814
REVERTED — app/web/travel-desk back on mfpl PROD, never-deploy in force. Built
with DEFAULT (prod) URL, no dev override.

- EditAttendanceBottomSheet (HR → Edit Attendance): the request-type dropdown
  "Time Correction" option is now "Time Correction (Unavailable)"; selecting it
  shows a toast "Time correction is currently unavailable." and stays on Remark,
  so the correction flow (time fields + type="correction" submit) is unreachable.
  Remark still works. Correction submit code left intact (dead) for easy
  re-enable — comment in code documents how to restore.
- Build :app:assembleDebug OK. No backend change.

### Session 116 - Travel Desk: back-nav, Edit billing, client wait, cancellation billing + mfpl revert + pull max

**Date:** 2026-08-02
**Session:** fork. travel-desk (aizen) + web (max) + app (merge). Env back on mfpl PROD.

- BACK-NAV (travel-desk): trip detail "← Back to trips" preserved the wrong tab
  (hardcoded /trips → default). Now carries ?from=<tab> from the card +
  Complete-billing links; back link returns to it.
- EDIT BILLING (travel-desk): Complete-tab cards get an "Edit billing" action
  (billing-permitted, billed trips) → ?edit=billing reopens the billing editor.
- CLIENT WAIT (travel-desk): trip detail shows the wait duration
  (travelDeskStartedAt - travelDeskArrivedAt), admin-only; driver link has no
  timer, so the driver never sees it's measured.
- CANCELLATION BILLING: additive, external-only (portal rejects internal fleet at
  login). Backend (web/max): finalizeCancellationBilling mutation (charges-only,
  no odometer, cancelled/no_show only) + /api/travel-desk/trips/cancellation-billing
  http route + listAssigned visibleStatuses widened (cancelled/no_show). Core
  finalizeBilling + cancelSiteVisitCore UNTOUCHED. Frontend: cancelled trips land
  in Billing ("Cancelled · to bill"); detail shows a Cancellation billing card
  (reason + "+ Add charge" + total). STAGED for the api-mfpl deploy (never-deploy).
- ENV: reverted app + web to mfpl PROD (was next-spaniel-814 test DB). App
  build.gradle.kts defaultBaseUrl -> api-mfpl.theairix.com; web .env/.env.local
  MFPL block active. travel-desk already on api-mfpl.
- PULL MAX: merged 10 commits into local max; 1 conflict in site-visit-detail-page
  (cab stepper) resolved as 3-way: kept the 9-stage Reached-CP scheme + adopted
  the team's terminal current:null. NOTE: pulled frontend now expects newer convex
  (listPending statuses[]) not on mfpl prod -> validator errors on mfpl until the
  team deploys. Typecheck clean both repos (only the pre-existing http.ts:11068
  arrivalPhotoStorageId error).

### Session 105 (cont.) - Time Correction Unavailable on WEB too

**Repo:** manjusitedevelopment (web frontend), app/attendance/page.tsx. Uncommitted.
NOTE: web frontend change (Next.js), not a convex deploy — never-deploy rule not
affected; appears on prod when the web is deployed. app/attendance/ was clean of
main-chat edits.

- Request dialog "Time Correction" SelectItem now `disabled` + labelled
  "Time Correction (Unavailable)" (only Remark selectable). (~line 7114)
- Neutralized the forced-correction path: field staff missing a punch-out no
  longer get forced into correction — `forceCorrection = false`, opens as Remark
  (~line 5288-5293). Removed now-unused isField/isMissingPunchOut.
- Existing "Time Correction" DISPLAY labels for already-submitted/pending requests
  left as-is (history/badges). tsc clean for attendance/page.
- Mirrors the app change (Session 105). Both surfaces: correction creation is off.

### Session 117 - SV list pipeline tabs (app) + CP→SV incharge default & required gate

**Date:** 2026-08-02
**Session:** fork. app (Mconnect/merge) + web (manjusitedevelopment/max). mfpl prod.

- SV LIST PIPELINE TABS (app): SiteVisitsFragment now mirrors the MMS web
  /marketing/site-visits tabs — Fixed | Scheduled | Enroute | Onsite |
  Returning home | Completed | Cancelled | Postponed, plus All + Expired kept as
  extras (user choice). Regrouped status predicates to match web boundaries
  (enroute=client_started+picked_up, onsite=on_site+on_counselling, returning=
  picked_from_site+dropped, completed=completed only). Row badges follow suit.
  All CLIENT-SIDE over the existing getMySiteVisits fetch → live on mfpl now.
- FIXED tab needs confirmationStatus: added `confirmationStatus` to TodayVisit
  (GeoTrackApi) + to the backend mobile mapper listForViewerAsMobileVisits
  (siteVisits.ts, web/max) — STAGED for the mfpl deploy; until then Fixed shows
  empty and pending-confirmation SVs sit under Scheduled.
- CP→SV INCHARGE DEFAULT (CompleteCpVisitBottomSheet): Site Incharge now defaults
  to the CP-assigned staff (visit.assignedStaff) — captured on CP load
  (cpAssignedStaff), seeded into the fix form when the SV carries no incharge,
  and used as the submit value (inchargeStaffId = svIncharge?.id ?? cpAssignedStaff.id).
  Fixes the QR-scan showing the telecaller as Site Incharge. App-side, live now.
- CP→SV REQUIRED GATE: persistSiteVisit blocks conversion unless Site Incharge
  (picker/CP default) AND BDO (session.staffId = the fixer) are present, with
  clear errors. Backend already asserts required staff (assertRequiredSvStaffAssignments)
  — belt-and-suspenders. NOTE: no separate BDO picker exists (BDO = the fixer).
- Build: :app:compileDebugKotlin SUCCESSFUL. Convex tsc clean (only pre-existing
  http.ts:11068 arrivalPhotoStorageId).

### Session 118 - Travel Desk summary-modal "Edit billing" + reassign wording + standing log rule

**Date:** 2026-08-02
**Session:** fork. travel-desk (aizen) + web (max, uncommitted). mfpl prod.

- TRAVEL-DESK (aizen, uncommitted): added an "Edit billing" button inside the
  Complete-tab "View summary" modal (trips/page.tsx). Gated on canBill +
  travelDeskBillingCompletedAt != null; closes the modal and router.push →
  /trips/{id}?from=complete&edit=billing (reuses the detail billing editor —
  no duplicate inline form). tsc clean.
- WEB (max, uncommitted): ACTIVE_SV_STATUSES widened with on_counselling +
  picked_from_site + dropped so a mid-flight reassign reads as a live "Handover"
  (was "Reassigned"). Wording-only; the set is referenced ONLY by
  notifySiteVisitReassignment (verified). convex tsc: no new errors (pre-existing
  http.ts:11068 + a pull-introduced whatsappInbound.ts:201 remain).
- VERIFICATIONS done this session (no code change): correctOutcome guard (perm +
  triple-blocked after convertToBooking); listPending shows only
  confirmationStatus=="pending" (index-level, no auto_confirmed leak);
  createAndSendWhatsApp (SV survives WA failure — send is swallowed, 3x idempotent
  retry, no durable re-send); reassign notification (resolveSvStaffAssignments +
  incoming/outgoing dedup + mutation-safe push scheduling).
- NEW STANDING RULE (user): update AGENT_LOG.md every chat response.

### Session 106 - Remove "Expired" feature from SV + CP lists

**Date:** 2026-08-02
**Session:** fork. App-only (Mconnect). Built with DEFAULT prod URL (env reverted
to mfpl per memory). Uncommitted.

- SV (SiteVisitsFragment): removed the Expired filter tab (dropped pillExpired
  from pillsAndFilters map + hide pillExpired visibility=GONE in setupFilterPills)
  AND stubbed isExpiredVisit() -> false. Result: no Expired tab, no "Expired"
  badge; a past-slot scheduled SV now shows under Scheduled. Filter enum EXPIRED +
  "No Expired Visits" empty-state + the paintPill("Expired") branch left as dead
  code (unreachable) for easy re-enable.
- CP (CpVisitsFragment): removed the VisitExpiry.isExpired `when` branch (statusText/
  actionLabel "Expired", tapMode NONE) — a past-slot CP now falls through to its
  normal live status (Need to Clock In / Start Trip) instead of "Expired".
- Build :app:assembleDebug OK. No backend change. VisitExpiry util untouched
  (still used elsewhere if any).

### Session 118 - SV edge-case audit (12 items) + docs/sv-flow.md

**Date:** 2026-08-02
**Session:** fork. Web (manjusitedevelopment/max) verification + docs. mfpl prod.

- Audited the 12 SV edge cases from the D-A-R-X GitHub issue (assigned @D-A-R-X,
  verifier @SafeerMohamed). Each is a fix or a documented no-fix decision.
- CODE FIXES (2, siteVisits.ts, committed max 22aa56bc):
  - markPickedUp rejects travelMode==="own_vehicle" (use markClientStarted). Cab /
    external / unallocated (IRIS) untouched — narrow guard, no IRIS/allocation break.
  - ACTIVE_SV_STATUSES widened (adds on_counselling, picked_from_site, dropped) so a
    mid-flight reassign reads "Handover" not "Reassigned". Wording only; used solely
    by notifySiteVisitReassignment.
- VERIFIED-CORRECT (no change): correctOutcome guard (perm + post-booking triple
  block), listPending (by_confirmationStatus eq "pending", no auto_confirmed literal),
  createAndSendWhatsApp (SV survives, swallowed, 3x idempotent retry), reassign
  notify (incoming+outgoing, resolveSvStaffAssignments, push is fire-and-forget so
  no rollback), advanceCabLifecycle IAM gate (marketing.siteVisits.updateCabLifecycle;
  11/11 tests), IRIS convertToSiteVisit (atomic status:"converted"+convertedSiteVisitId,
  idempotent), rollup integrity (all 5 status mutations call patchSiteVisitStatsForChange;
  postpone twice). Prior-session fixes confirmed: postpone close sets confirmationStatus
  confirmed; remindStaleCounsellingOutcomes 6h cron for >24h on_counselling.
- TESTS: npx vitest run convex/siteVisit -> 7 files / 25 tests all pass.
- DOC: created docs/sv-flow.md (Day-1 doc never existed) — lifecycle, tracks,
  confirmationStatus, rollups, notifications, IRIS, + the 12 edge-case decisions.
- Drafted the GitHub reply for the issue (all comms in GH per the issue).

### Session 119 - SV/CP management sign-off gate and same-day multiple-SV blocker

**Date:** 2026-08-02
**Session:** main task. Documentation-only changes in manjusitedevelopment/max;
no commit or push.

- Read the management sign-off task and audited its prerequisites against the
  repository. `docs/sv-flow.md` and `docs/sv-flow.docx` exist, but
  `docs/cp-flow.md` is missing; reviewer completion and management sign-off
  evidence are also not present. The sign-off task therefore remains blocked.
- Added edge case 13 to `docs/sv-flow.md`: same client with multiple SVs on the
  same day is explicitly `INCOMPLETE / BLOCKED` because the current Fleet model
  can cross-wire assignment, progress, proof, billing, and tab classification.
  Documented the five management decisions required before implementation and
  required the approved behavior to be filed as a separate GitHub issue.
- Synchronized the same edge-case row, decision checklist, and release gate into
  the management-facing `docs/sv-flow.docx`.
- Created `docs/sv-cp-management-signoff.md` as an evidence-based task tracker.
  All prerequisite, review, signature, upload, follow-up-issue, and confirmation
  boxes remain unchecked until their GitHub evidence exists.
- Validation: `git diff --check` passed; DOCX structural inspection confirmed 13
  edge rows and the new blocked heading. Microsoft Word exported the DOCX to PDF,
  Poppler rendered four PNG pages, and all pages were visually checked with no
  clipping or broken layout. The bundled renderer itself could not start because
  LibreOffice is not installed, so Word was used as the rendering fallback.
- Remaining actions: create and obtain reviewer completion for `docs/cp-flow.md`,
  present both flows to management, collect written/signature evidence, upload
  `docs/sv-cp-signoff.pdf` (or a GitHub attachment), file each newly approved
  requirement as its own GitHub issue, and comment on the source issue with the
  dated sign-off. No management review or signature was claimed in this turn.

### Session 120 - Publish pending app, web documentation, and Travel Desk changes

**Date:** 2026-08-02
**Session:** main task. Publish audit across all three project repositories.

- Audited pending changes in Mconnect (`merge`), manjusitedevelopment (`max`),
  and travel-desk (`aizen`). All three local branches matched their remote tips
  before publishing (`0 ahead / 0 behind`).
- Confirmed publish scope: app removal of CP/SV expiry presentation; SV flow and
  management sign-off documentation including the same-day multiple-SV blocker;
  and Travel Desk Complete-summary `Edit billing` navigation.
- Kept `AGENT_LOG.md` local-only and excluded it from staging as required.
- Validation before commit: Mconnect `:app:assembleDebug` passed using Android
  Studio's bundled JDK; Travel Desk `npx tsc --noEmit` passed; web documentation
  `git diff --check` and sign-off tracker existence check passed. The SV DOCX had
  already been structurally and visually verified in Session 119.
- Published successfully and verified `0 ahead / 0 behind` after fetching:
  - Mconnect `merge`: `b4094f27` (`fix(marketing): remove visit expiry presentation`)
    pushed directly to `manjugroupsdev/Mconnect` so the additional configured
    D-A-R-X push URL was not touched.
  - manjusitedevelopment `max`: `2707bb1a` (`docs(sv): record multiple-visit
    sign-off blocker`).
  - travel-desk `aizen`: `b10d756` (`feat(trips): edit billing from completion
    summary`).
- Final repository state: web and Travel Desk worktrees are clean; Mconnect has
  only this required local-only `AGENT_LOG.md` modification.

### Session 121 - Management-ready SV flowchart document

**Date:** 2026-08-02
**Session:** main task. Documentation-only work in manjusitedevelopment/max; not
committed or pushed.

- Created `docs/sv-flowchart-guide.docx` as a separate, cleaner management-facing
  companion to the detailed `docs/sv-flow.md` technical audit.
- Reorganised the SV material into eight readable pages with five high-resolution
  flowcharts: end-to-end entry, cab/Fleet lifecycle, own-vehicle lifecycle, QR to
  counselling/outcome, and exceptions/controls.
- Documented entry origins, confirmation states, required staff, internal/external
  Fleet ownership, pending-details classification, QR access rules, counselling,
  outcomes, postpone/cancel/reassign behavior, odometer/evidence ownership, and
  cross-system MMS/Travel Desk/Mconnect consistency.
- Kept same-client same-day multiple SVs prominently marked `INCOMPLETE / BLOCKED`
  with the five management decisions and release gate required before a separate
  implementation issue can proceed.
- Added a management review checklist that remains explicitly unsigned and does
  not claim review completion.
- Validation: canonical `render_docx.py` could not start because LibreOffice is
  unavailable; Microsoft Word exported the DOCX and Poppler rendered all eight
  pages. Reviewed every page twice after correcting an awkward section break; no
  clipping, overlap, broken diagrams, or crowded tables remain. Final accessibility
  audit reports 0 high, 0 medium, and 0 low findings after marking both table header
  rows correctly. Structural checks confirmed five inline diagrams, two tables,
  Letter page geometry, 1-inch side margins, and the blocked requirement text.
- Repository state: `docs/sv-flowchart-guide.docx` is a new untracked deliverable;
  no source code, existing SV documentation, commit, or remote branch was changed.

### Session 122 - SV cancellation parity for mobile and Travel Desk

**Date:** 2026-08-02
**Session:** main task. Cross-repository implementation in Mconnect, MMS backend,
and Travel Desk; not committed or pushed.

- Inspected the existing MMS Site Visit cancellation contract and confirmed that
  `cancelSiteVisitCore` owns terminal-state validation, lead reopening, lifecycle
  WhatsApp, daily-task completion, audit, and rollup updates. Confirmed the web
  mutation enforces `marketing.siteVisits.cancel` for staff callers.
- Added the authenticated mobile `POST /api/marketing/siteVisits/cancel` endpoint
  to the MMS backend and its CORS preflight registration. It accepts `id` and an
  optional `reason`, attributes the action to the bearer-token staff member, and
  delegates IAM and lifecycle behavior to the existing mutation.
- Added the mobile API model/call, IAM-gated `Cancel visit` action, and a reusable
  confirmation bottom sheet with an optional reason. Terminal SVs hide the action;
  successful cancellation closes the stale detail sheet.
- Added an explicit Travel Desk agency `Cancel visit` action that preselects the
  existing ownership-checked `cancelled` status flow. Added optional typed reason
  capture and prevented completed/already-cancelled cards from offering the action.
- Validation: Travel Desk `npx tsc --noEmit` passed after the final edits;
  Mconnect `:app:processDebugResources :app:compileDebugKotlin --no-daemon`
  passed, covering the new XML resources, Retrofit model, and Kotlin UI flow;
  and `git diff --check` passed in all three repositories.
- A full Mconnect assemble was attempted twice. The first attempt reached dex
  merge but failed on a corrupted generated ZLIB stream after an earlier forced
  timeout; after stopping Gradle daemons, a clean assemble exceeded the five-minute
  runner limit. The focused resource/Kotlin build then passed in 51 seconds.
- MMS `npx tsc --noEmit --pretty false` completed but remains red on pre-existing,
  unrelated errors in the older arrival-photo HTTP call, attendance and SV lifecycle
  tests, reassign test indexes, and WhatsApp inbound nullability. The new cancel
  route produced no TypeScript diagnostic.
- Repository state at completion: Mconnect contains the five cancellation source/
  resource changes plus this required local-only log; manjusitedevelopment contains
  `convex/http.ts` plus the pre-existing untracked `docs/sv-flowchart-guide.docx`;
  Travel Desk contains the trip-page and status-dialog changes. No commit, push,
  Convex deployment, or live cancellation was performed.

### Session 123 - Chart-only SV flow document

**Date:** 2026-08-02
**Session:** main task. Documentation-only work in manjusitedevelopment/max; not
committed or pushed.

- Created `docs/sv-flowcharts-only.docx` as an 11-page, chart-only SV flow
  document with no narrative sections, tables, or cover material.
- Covered direct/CP/IRIS entry, confirmation and required staff, own/internal/
  external transport routing, Fleet lifecycle and ownership, QR/IAM counselling,
  all counselling outcomes, separate SV postponement, cancellation/no-show/
  reassignment, Fleet evidence and billing, cross-system status synchronization,
  supported edge cases, and the blocked same-client same-day multiple-SV case.
- Kept same-client multiple same-day SVs explicitly marked incomplete/blocked and
  charted the management decisions required before implementation.
- Validation: the standard LibreOffice renderer was unavailable, so Microsoft
  Word exported a QA PDF and Poppler rendered it. Visually reviewed all 11 pages
  after correcting image ratio, font sizing, wrapping, and connector routes.
  Final PDF verification reports 11 landscape Letter pages; the DOCX is present
  at 1,428,267 bytes.
- No application source, API, schema, configuration, commit, push, or deployment
  was changed for this request. Existing uncommitted work from Session 122 was
  left untouched.

### Session 124 - Travel Desk working-version recovery snapshot

**Date:** 2026-08-02
**Session:** main task. Travel Desk commit/push and recovery reference only.

- Reviewed the pending Travel Desk cancellation-parity diff in
  `src/app/trips/page.tsx` and `src/components/status-update-dialog.tsx`.
- Validation passed: `git diff --check` and `npx tsc --noEmit`.
- Confirmed local `aizen` and `origin/aizen` were synchronized before commit,
  committed the two files as `feat(trips): add agency cancellation action`, and
  pushed successfully to `origin/aizen`.
- **Stored Travel Desk recovery commit:**
  `b340926dc331f06e948876278f2dcf7af6600ef4`.
  When the user says **"store recovery"**, restore/recover the Travel Desk
  working version from this exact commit unless the user explicitly replaces the
  recovery point later.
- Added the local Git tag `recovery-travel-desk-working-2026-08-02` and local Git
  config `codex.recoveryCommit` pointing to the same SHA. The tag was intentionally
  kept local; the branch commit itself is present on the remote.
- Post-push verification: `HEAD`, `origin/aizen`, and the local recovery tag all
  resolve to the stored SHA, and the Travel Desk worktree is clean.
- MMS web and Mconnect source changes were not staged, committed, or pushed in
  this step. `AGENT_LOG.md` remains local-only as required.

### Session 125 - Horizontal Travel Desk trip progress

**Date:** 2026-08-02
**Session:** main task. Travel Desk UI-only change; not committed or pushed.

- Replaced the vertical Trip progress list in
  `src/app/trips/[id]/page.tsx` with a connected left-to-right seven-stage
  stepper.
- Preserved completed/current/pending state styling and stage timestamps. The
  active stage now has a separate `Current` label and `aria-current="step"`.
- Added a stable minimum rail width and horizontal overflow so narrow screens
  retain stage order and readable labels instead of compressing the tracker.
- Validation passed: `npx tsc --noEmit`, `git diff --check`, and the full
  `npm run build` production build.
- Focused ESLint remains red on eight pre-existing `react-hooks` errors and one
  pre-existing dependency warning elsewhere in the same large page; no diagnostic
  points to the new progress-stepper block.
- Browser verification reached the local Travel Desk login screen, but the
  isolated test browser did not have an authenticated agency session, so live
  trip-data visual verification could not be completed without credentials.
- No commit or push was performed. The stored recovery commit remains
  `b340926dc331f06e948876278f2dcf7af6600ef4` and was not moved.

### Session 126 - Drop-gated Start/End proof updater

**Date:** 2026-08-02
**Session:** main task. Travel Desk UI/flow change; not committed or pushed.

- Updated `src/app/trips/[id]/page.tsx` so live trips show Start km/proof on
  the left and a visibly disabled End km/proof panel on the right.
- The End panel remains locked through Picked from Site. The pre-drop form no
  longer accepts end odometer, end image, toll, or beta details; `Mark Dropped`
  is now the explicit event that unlocks end-proof entry.
- After Dropped, the agency billing updater presents Start and End odometer/image
  panels side by side. The End panel is marked `Unlocked`, and total distance
  validation/calculation remains immediately below the paired panels.
- Mobile/narrow layouts stack the two panels while desktop keeps the requested
  left/right arrangement.
- Validation passed: `npx tsc --noEmit`, `git diff --check`, and the full
  `npm run build` production build.
- This change remains in the same uncommitted trip-detail file as Session 125.
  No commit or push was performed, and the stored recovery commit remains
  `b340926dc331f06e948876278f2dcf7af6600ef4`.

### Session 127 - Optimized Travel Desk trip details card

**Date:** 2026-08-02
**Session:** main task. Travel Desk UI-only change; not committed or pushed.

- Reworked the `Details` section in `src/app/trips/[id]/page.tsx` into a compact
  `Trip details` layout with clearer label/value hierarchy.
- Kept the pickup address as a full-width lead row, then arranged pickup time,
  attendees, vehicle, driver, and rate in a responsive five-column desktop grid
  that becomes two columns on narrow screens.
- Split vehicle type and driver phone into quieter secondary lines and added
  explicit package/per-kilometer context beneath the rate, without changing any
  source values or actions.
- Added wrapping and minimum-width protections so long vehicle, driver, address,
  or pricing text cannot overlap adjacent fields.
- Validation passed: `npx tsc --noEmit`, `git diff --check`, and the full
  `npm run build` production build.
- No commit or push was performed. Sessions 125-127 remain together in the
  modified trip-detail page; the stored recovery commit is still
  `b340926dc331f06e948876278f2dcf7af6600ef4`.

### Session 128 - Actionable pending trip evidence

**Date:** 2026-08-02
**Session:** main task. Travel Desk UI plus MMS backend lifecycle contract; not
committed, pushed, or deployed.

- Updated the live Start km/proof panel in Travel Desk to detect missing start
  odometer and image independently, show an amber `Pending details` tag, and
  expose only the missing input/uploader.
- Added `Save start details`, allowing the agency to upload either missing Start
  item immediately; the other item remains visibly pending until supplied.
- Added Complete/Pending tags to both Start and End proof panels in the post-drop
  billing updater. Existing proof previews remain visible and missing fields stay
  editable through the existing completion workflow.
- Extended `convex/travelDeskDriverTrips.ts::submitEvidence` so Start evidence can
  be backfilled after trip start but before drop. End evidence is still rejected
  until `travelDeskEndedAt` exists, preserving the requested drop gate.
- Expanded `convex/siteVisitCabLifecycleOverride.test.ts` to verify successful
  pre-drop Start km/image backfill, rejection of pre-drop End km, and normal End
  evidence completion after drop.
- Validation passed: Travel Desk `npx tsc --noEmit`, `git diff --check`, and full
  `npm run build`; MMS focused Vitest suite passed all 11 tests; targeted MMS
  `git diff --check` passed.
- No commit, push, Convex deployment, or live upload was performed. Existing
  unrelated MMS `convex/http.ts` and documentation work was left untouched. The
  stored Travel Desk recovery commit remains
  `b340926dc331f06e948876278f2dcf7af6600ef4`.

### Session 129 - Standing AC checkbox interaction fix

**Date:** 2026-08-02
**Session:** main task. Travel Desk UI behavior fix; not committed or pushed.

- Diagnosed the non-responsive `Standing with AC` checkbox in
  `src/app/trips/[id]/page.tsx`: it was disabled whenever Standing time was
  blank, even though that field is explicitly optional.
- Removed the standing-time-dependent `disabled` gate so the checkbox can always
  be toggled during Picked from Site.
- Removed the input handler that forcibly unchecked AC whenever standing minutes
  were cleared. The user selection now remains stable while editing the optional
  duration.
- Validation passed: `npx tsc --noEmit`, `git diff --check`, and full
  `npm run build`.
- No commit or push was performed. Existing Travel Desk and MMS changes remain
  uncommitted; the stored recovery commit remains
  `b340926dc331f06e948876278f2dcf7af6600ef4`.

### Session 130 - SV not-interested outcome reason fix

**Date:** 2026-08-02
**Session:** main task. MMS web outcome form fix; not committed, pushed, or
deployed.

- Diagnosed the reported Convex `setOutcome` failure. The backend intentionally
  requires a non-empty `notInterestedReasons` array for a `not_interested`
  outcome, but the MMS Site Visit detail dialog submitted only the outcome and
  optional notes.
- Updated
  `features/marketing/pages/site-visit-detail-page.tsx` in the MMS/web repo to
  present the same eight canonical not-interested reasons used by the Mconnect
  SV form as app-native checkboxes.
- Added client-side validation requiring at least one reason, included the
  selected reasons in the `setOutcome` mutation payload, and reset the selection
  on cancel, close, and successful submission. The Convex data-integrity guard
  remains unchanged.
- Validation: targeted `git diff --check` passed. Focused ESLint reached only
  pre-existing `no-explicit-any` errors and one unused-disable warning at
  unrelated lines in the same page; the new code produced no lint finding. Full
  `npx tsc --noEmit --pretty false` exceeded the 120-second command limit without
  emitting diagnostics.
- Existing unrelated MMS changes and documentation files were left untouched.
  No commit, push, Convex deployment, or live verification was performed.

### Session 131 - Faster and recoverable SV QR scanning

**Date:** 2026-08-02
**Session:** main task. Mconnect scanner plus MMS QR-query latency fix; not
committed, pushed, or deployed.

- Traced the SV QR flow from CameraX/ML Kit through
  `POST /api/marketing/siteVisits/scanQr` and found that the camera froze with no
  loading feedback while a request could wait for the shared 30-second network
  timeout.
- Restricted ML Kit recognition to QR codes, added a visible `Loading site
  visit...` state, bounded each SV lookup to 10 seconds, and added one short
  retry for transient I/O/time-out failures. A final failure now resumes the
  camera and gives a clear retry message instead of leaving the scanner frozen.
- Added scanner job cancellation and barcode-scanner cleanup with the fragment
  lifecycle to prevent stale requests/results after navigation.
- Parallelized MMS `getByQrPayload` authorization and visit enrichment, removing
  an unnecessary serial wait before returning the scan details.
- Validation passed: Mconnect `:app:assembleDebug` completed successfully using
  Android Studio's bundled JDK; MMS `siteVisitQrCounselling.test.ts` passed both
  focused authorization/scan tests; targeted `git diff --check` passed in both
  repos. The first cold Android build retried internally after a stale generated
  Kotlin class lookup and later completed; a second clean incremental build
  confirmed `BUILD SUCCESSFUL` in four seconds, and its generated diagnostic log
  was removed.
- Existing cancellation, fleet, outcome-dialog, and documentation changes in
  both repositories were preserved. No commit, push, Convex deployment, APK
  installation, or physical-device camera/network test was performed.

### Session 132 - Publish accumulated changes

**Date:** 2026-08-02
**Session:** main task. Git publication across Mconnect, MMS, and Travel Desk
completed.

- Audited all three repositories and confirmed the target branches are Mconnect
  `merge`, MMS/web `max`, and Travel Desk `aizen`.
- Fetched each corresponding origin branch; all three local branches were
  exactly synchronized with their remotes before staging (`0` behind, `0`
  ahead).
- Publication scope includes the accumulated app cancellation/QR work, MMS
  cancellation/fleet/SV outcome/QR work and SV flow documents, and the Travel
  Desk trip-detail workflow/UI changes. `AGENT_LOG.md` remains intentionally
  unstaged and local-only.
- Created and pushed Mconnect commit
  `b236fff112cdc679400bed4a5e9228c3be466799` to branch `merge`. Because the
  configured `origin` has two push URLs, the same commit was published to both
  `manjugroupsdev/Mconnect` and `D-A-R-X/Mconnect`.
- Created and pushed MMS/web commit
  `e1342d46e35dff2e321ef64ba37160fa98e7e2c7` to branch `max` on
  `manjugroupsdev/manjusitedevelopment`.
- Created and pushed Travel Desk commit
  `33a9d7d4abf8d7657d4ae92a2a2aba510165e9df` to branch `aizen` on
  `manjugroupsdev/travel-desk`.
- Verified each branch using `git ls-remote`; every remote SHA exactly matched
  its local HEAD after push. MMS and Travel Desk are clean. Mconnect contains
  only this intentionally uncommitted `AGENT_LOG.md` update.
- No Convex deployment, web deployment, or APK release was performed; this turn
  published source commits only.

### Session 133 - Stored multi-repo recovery baseline

**Date:** 2026-08-02
**Session:** main task. Local recovery metadata only; no source commit or push.

- Stored the current published Mconnect baseline at commit
  `b236fff112cdc679400bed4a5e9228c3be466799` using local tag
  `recovery-mconnect-working-2026-08-02` and the repository-local
  `codex.recoveryCommit` / `codex.recoveryLabel` settings.
- Stored the current published MMS/web baseline at commit
  `e1342d46e35dff2e321ef64ba37160fa98e7e2c7` using local tag
  `recovery-mms-working-2026-08-02` and matching local recovery settings.
- Replaced the active Travel Desk recovery pointer with commit
  `33a9d7d4abf8d7657d4ae92a2a2aba510165e9df` using local tag
  `recovery-travel-desk-working-2026-08-02-v2`. The earlier historical tag at
  `b340926dc331f06e948876278f2dcf7af6600ef4` was preserved rather than moved.
- Verified every new tag resolves to its recorded full commit SHA. Future
  requests to `recover` or `use stored recovery` should restore these three
  active `codex.recoveryCommit` values unless the user stores a newer baseline.
- Recovery tags/config are intentionally local metadata and were not pushed.
  The underlying commits are already available on their respective remote
  branches from Session 132. `AGENT_LOG.md` remains local-only.

### Session 134 - SV map-first reusable address component

**Date:** 2026-08-02
**Session:** main task. MMS/web reusable component and SV-only integration;
validation in progress, not committed, pushed, or deployed.

- Audited the existing `UnifiedAddressFields` widget and confirmed it already
  owns address parsing, India Post pincode lookup, forward geocoding, reverse
  geocoding, and coordinate/Google Maps link state, but exposed the searchable
  map only through a secondary Drop Pin dialog.
- Added an opt-in `layout="map-first"` component mode. It presents an address
  search/paste input first, live suggestions, an immediately visible interactive
  map, automatic map focus, tappable/draggable pin placement, reverse-geocoded
  field updates, and the existing seven structured fields below the map.
- Enabled the map-first mode only in Site Visit creation and Site Visit editing.
  CP and every other current consumer retain the established fields-first UI
  until the user explicitly requests the broader rollout.
- Preserved the existing SV persistence contract: the seven fields are joined
  into `pickupAddress`, with exact `pickupLat`, `pickupLng`, and
  `pickupGoogleMapsLink` saved separately.
- Confirmed the final map shell keeps the suggestion popup unclipped while the
  map canvas itself retains rounded clipping. `git diff --check` passed for all
  three modified MMS files.
- Audited Mconnect for the matching Android rollout. Google Maps is already in
  use, but the current mobile SV screens consume assigned visits and do not
  expose the MMS create/edit pickup-address form shown in this request. No
  Android source was changed in this SV design pass; broader app adoption stays
  deferred until the requested all-address rollout.
- Verified the live MMS address-search route at port 3100 with `Manju Groups
  Chennai`; it returned HTTP 200, the expected Ashok Nagar address, and precise
  latitude/longitude. The MMS development server remains available on port
  3100 for user testing.
- `next build` compiled the application successfully in 27.7 seconds, then the
  repository-wide TypeScript phase stopped at the pre-existing unrelated
  `convex/http.ts:11068` mismatch where `arrivalPhotoStorageId` is not declared
  by the `siteVisits.setOutcome` argument type. Focused ESLint found only two
  pre-existing `react-hooks/set-state-in-effect` findings in the legacy
  `PinDropDialog` at lines 1246 and 1275; no new map-first lint finding was
  reported.
- Final `git diff --check` passed. MMS has exactly three intended modified
  files (`components/unified-address-fields.tsx`, SV create, and SV edit). No
  commit, push, Convex deployment, or web deployment was performed. Mconnect's
  unrelated `.idea/deploymentTargetSelector.xml` change was observed and left
  untouched; this log remains local-only.

### Session 135 - Canonical address rollout across MMS, Travel Desk, and Mconnect

**Date:** 2026-08-02
**Session:** main task. Cross-repository audit and implementation in progress;
not committed, pushed, or deployed.

- Started the requested rollout of the reusable map-first address workflow to
  every applicable address/location surface across MMS, Travel Desk, and the
  Android app, covering SV, CP, projects, and fleet.
- Established the rollout invariant: structured address fields, canonical
  display address, latitude, longitude, and Google Maps link must be persisted
  and propagated together; downstream screens must prefer the saved coordinates
  and geocode address text only for legacy rows that lack coordinates.
- Initial inventory found four current MMS consumers of
  `UnifiedAddressFields` (SV create/edit and two CP surfaces). Android already
  contains a reusable `MapPinDropBottomSheet`, while fleet/trip screens commonly
  display `pickupAddress`; schema and route tracing is in progress before edits.
- No source files were changed during this audit step. The unrelated local
  `.idea/deploymentTargetSelector.xml` modification remains untouched.
- Activated MMS `layout="map-first"` for all persisted SV and CP create/edit
  surfaces and replaced project create/edit free-text location inputs with the
  same canonical editor. Project saves now include the joined canonical address,
  exact `lat`, exact `lng`, and Google Maps link; the land-to-project mutation
  was extended to accept the coordinates already supported by the project table.
- Confirmed Travel Desk already receives `pickupAddress`, `pickupLat`,
  `pickupLng`, and `pickupGoogleMapsLink`, and its trip/driver map preview
  prioritizes coordinates over text geocoding. No Travel Desk source change was
  needed for canonical fleet pickup locations.
- Extended the MMS internal-fleet driver projection and Mconnect external and
  internal trip models with canonical pickup coordinates/maps link. The Android
  trip detail now pins and opens the saved location directly, using geocoding
  only for legacy trips without coordinates.
- Added the existing reusable Android searchable map/pin picker to the mobile
  CP-to-SV outcome form. The selected address, latitude, longitude, and Maps
  link are submitted together through the already-compatible conversion API;
  existing client-place coordinates prefill the picker when available.
- Completed the canonical operational-location rollout for the requested
  modules. MMS SV create/edit, CP create/edit, and project create/edit now use
  the map-first shared editor. Fleet records inherit the exact saved SV pickup
  address and coordinates instead of independently geocoding the display text.
- Extended the MMS internal-driver-trip projection plus the Mconnect external
  and internal fleet models so `pickupAddress`, `pickupLat`, `pickupLng`, and
  `pickupGoogleMapsLink` stay together end to end. Android trip details center
  the map and open navigation from saved coordinates/link, with text geocoding
  retained only for legacy records that have no coordinates.
- Confirmed Travel Desk already implements this contract in its API models and
  trip map surfaces; no Travel Desk source file needed modification. Its direct
  TypeScript check (`npx tsc --noEmit --pretty false`) passed.
- Validation: focused ESLint passed for all edited MMS UI/projection files;
  `convex/driverTrip.test.ts` passed all 8 tests; `git diff --check` passed in
  MMS, Travel Desk, and Mconnect; and a clean Android
  `:app:assembleDebug --no-daemon` build succeeded.
- MMS full TypeScript checking still reports only repository-existing failures
  outside this change (`convex/http.ts` arrival proof typing, several test
  nullability/index typings, and `convex/whatsappInbound.ts`). The production
  build also could not be captured while the active localhost Next dev server
  held the shared `.next` workspace, so validation used focused ESLint, direct
  TypeScript diagnostics, and the relevant trip contract test instead.
- Browser verification on the live local SV scheduling dialog confirmed the
  map-first search/pin surface and structured fields render. The configured
  Google Maps key currently returns `BillingNotEnabledMapError`; billing must
  be enabled for `NEXT_PUBLIC_GOOGLE_MAPS_WEB_KEY` before exact pin interaction
  can work in deployed environments. This is configuration follow-up, not a
  component code failure.
- Removed the temporary Kotlin compiler crash log created by the initial stale
  incremental-cache failure. The unrelated local
  `.idea/deploymentTargetSelector.xml` modification remains untouched.
- No commit, push, Convex deployment, or production deployment was performed.

### Session 136 - Full-width profile/group photo crop screen

**Date:** 2026-08-02

- Investigated the narrow, floating crop screen shown on mobile. The shared
  `ProfilePhotoCropDialog` was applying its full-screen style from
  `onCreateView`, after Android had already created a floating-width dialog
  window.
- Moved crop-dialog styling into `onCreate`, added a non-floating Day/Night
  crop theme, and explicitly sizes the window to the full available width and
  height in `onStart`. This fixes the clipped/narrow editor for both profile
  photos and chat group photos, which share the same crop dialog.
- Updated the crop surface and text to use semantic inverse theme colors and
  replaced the unrelated bright-green loan background on `Use Photo` with the
  standard app primary-button background.
- Validation completed: `git diff --check` passed and
  `./gradlew :app:assembleDebug --no-daemon` completed successfully. No source
  warning or resource error was introduced by the full-screen dialog theme.
- The unrelated `.idea/deploymentTargetSelector.xml` change remains untouched.
- No commit, push, or deployment was performed in this session.

### Session 137 - Collection CP collected/not-collected audit

**Date:** 2026-08-02

- Traced the Collection CP mobile flow through the payment sheet, Android API
  models, MMS HTTP route, Convex collection mutation, and CP outcome rendering.
- Confirmed the explicit `Collected` and `Nothing collected` actions exist and
  map to `collection_done` and `not_collected`, respectively.
- Found two integrity gaps under repair: the client-not-present branch wrongly
  records `collection_done`, and retrying after a partial network failure can
  create duplicate customer collection rows because mobile collection submits
  are not tied idempotently to the CP visit.
- No source files have been changed yet in this session. The unrelated local
  `.idea/deploymentTargetSelector.xml` modification remains untouched.
- Corrected Collection CP client-not-present completion to persist
  `not_collected` instead of the false `collection_done` outcome.
- Made the collected-payment form mode-aware: Cash permits an optional
  reference, electronic modes require a transaction reference, and Cheque/DD
  reveal and require number, bank, branch, and instrument date fields. These
  values now travel through the Android request and MMS HTTP mutation.
- Added per-CP-visit collection idempotency in the MMS schema/mutation. A
  network retry returns the existing collection row, while conflicting retry
  details are rejected, preventing duplicate Accounts collection entries.
- Added a focused Convex regression test covering repeated mobile submission
  for the same Collection CP. Validation is pending.
- Validation completed: Android `:app:assembleDebug --no-daemon` succeeded;
  the focused `convex/postSales.test.ts` suite passed all 16 tests; focused
  ESLint passed for `customerCollections.ts`, `schema.ts`, and the regression
  test; and `git diff --check` passed in both Mconnect and MMS.
- The first regression run failed only because the synthetic CP fixture omitted
  required `createdAt`; the fixture was corrected and the rerun passed.
- Repository-wide TypeScript checking exceeded the 120-second command limit.
  Full-file ESLint on legacy `convex/http.ts` also reports its existing broad
  `no-explicit-any` backlog, including hundreds of unrelated lines; the single
  added route field was exercised through the passing mutation test and Android
  contract compile.
- The Convex schema/mutation change still requires normal admin deployment
  before the live app receives retry idempotency. No commit, push, Convex
  deployment, or production deployment was performed.

### Session 138 - Publish current repository changes

**Date:** 2026-08-02

- Audited all three project repositories before publishing. MMS has source
  changes on `max`, Mconnect has source changes on `merge`, and Travel Desk is
  clean on `aizen` with nothing new to commit.
- Preparing to commit and push all current source changes in MMS and Mconnect.
  `AGENT_LOG.md` remains local-only, and the unrelated Android Studio
  `.idea/deploymentTargetSelector.xml` change is explicitly excluded.
- Created Mconnect commit `e64ad50` on `merge` and MMS commit `65e716d3` on
  `max`. Travel Desk remains clean with no commit required. Push verification
  is pending.
- Successfully pushed Mconnect `merge` at
  `e64ad5007a962b690eb660699d26f958280f9ed6` to both configured push targets
  (`manjugroupsdev/Mconnect` and `D-A-R-X/Mconnect`) and MMS `max` at
  `65e716d3fbb1be6988a4106ab32f129db745f236` to
  `manjugroupsdev/manjusitedevelopment`.
- Verified local HEAD and each origin tracking branch are identical after the
  push. Travel Desk `aizen` remains synchronized at `33a9d7d4`.
- Only local-only `AGENT_LOG.md` and the unrelated
  `.idea/deploymentTargetSelector.xml` modification remain in Mconnect; neither
  was committed or pushed. No deployment was performed.

### Session 139 - Web-saved CP/SV coordinate propagation failure

**Date:** 2026-08-04

- Began tracing the reported missing destination pin from the MMS web address
  editor through client-place persistence, CP/SV creation and projection
  endpoints, and the Mconnect trip payload/map consumer.
- The screenshot confirms a contract failure rather than a marker-style issue:
  the app receives no usable destination coordinates, falls back to a world
  map/current-location label, and cannot perform the near-client check.
- MMS is clean on `max`. Mconnect contains a separate local change in
  `CreateCpVisitBottomSheet.kt`, plus local IDE/Kotlin state; these are treated
  as user-owned and will not be overwritten or included in this fix.
- No source files have been changed yet in this session.
- Confirmed the Android pin consumes only the nested `clientPlace.lat/lng` for CP rows, while SV rows consume `pickupLat/pickupLng`. The CP persistence helper is capable of patching explicit coordinates, so investigation moved to the web form payload and mobile projection/fallback contract.
- Verified `/api/marketing/clientPlaceVisits/my` delegates to the enriched CP list and currently returns the linked client-place row verbatim, with no fallback when that row lacks coordinates.
- Implemented the first fix pass in MMS: the shared address component now rejects the legacy `0,0` placeholder, CP and SV create flows require a usable map coordinate, CP visits persist an exact address/coordinate snapshot, edit saves update that snapshot, and enriched mobile responses resolve coordinates from visit snapshot -> linked place -> client master while filtering unusable values.
- No Android source was changed; the fix is intentionally on the web/backend contract that feeds the existing Android `clientPlace.lat/lng` fields.
- Hardened both create and edit surfaces for CP and SV so an address cannot be saved with missing, partial, invalid, or legacy `0,0` coordinates. The shared component now renders `0,0` as unpinned and geocodes/asks the user to set the real destination.
- Hardened the HTTP CP create endpoint to accept camelCase/snake_case coordinates, reject partial/non-numeric/`0,0` pairs with HTTP 400, and avoid converting blank coordinate strings to zero.
- Added coordinate regression tests covering missing, non-finite, out-of-range, zero-placeholder, and valid Chennai coordinate pairs.
- Completed the web/backend coordinate fix across CP and SV create/edit flows.
  The shared address control now treats legacy `(0, 0)` as unpinned, requires a
  real map selection, and forwards the selected latitude/longitude unchanged.
- Added visit-scoped CP address/coordinate snapshots to the MMS schema. Mobile
  CP projections now resolve the destination in this order: visit snapshot,
  linked client place, then client master, while rejecting unusable coordinate
  pairs. This preserves the existing Android `clientPlace.lat/lng` contract, so
  no Mconnect source change was required.
- Updated the CP create and CP-to-SV HTTP endpoints to normalize camelCase and
  snake_case coordinates and reject partial, non-numeric, out-of-range, and
  `(0, 0)` values. CP/SV edit saves now update the same persisted destination
  used by downstream app, Travel Desk, and MMS views.
- Added and passed an end-to-end Convex regression test proving that a
  web-created CP returns its exact address and coordinates through the mobile
  list payload, and that editing the pin updates both the stored snapshot and
  nested `clientPlace` projection. Focused result: 4 tests passed.
- Verified `git diff --check`, the local MMS page at
  `http://localhost:3100/marketing/site-visits?tab=scheduled&from=2026-08-02&to=2026-08-02`
  (HTTP 200), and the local map search endpoint, which resolved Anna Nagar to
  latitude `13.0849557` and longitude `80.2101342`.
- Repository-wide TypeScript/ESLint remain blocked by existing unrelated
  errors in legacy files, including the established `no-explicit-any` backlog;
  no reported failure points to the new coordinate logic. Convex codegen also
  remains blocked by the configured malformed/unauthorized deployment token.
- Live behavior requires the normal admin Convex deployment. Legacy visits
  recover automatically when a linked place or client master has valid
  coordinates; a record with no valid coordinate in any source must be opened
  once in CP/SV Edit and pinned. No commit, push, Convex deployment, Android
  source change, or production deployment was performed in this turn.

### Session 140 - Compact address UI and legacy pin self-repair

**Date:** 2026-08-04

- Reworked the shared MMS address component used by CP, SV, and project forms.
  The embedded search/map surface is removed from the form; a compact Drop pin
  or Adjust pin action now appears above the original postal address fields,
  and opens the existing search-and-map dialog only when requested.
- Removed the obsolete `map-first` option from all shared-component call sites
  so CP create/edit, SV create/edit, and project create/edit now render the same
  canonical address UI.
- Address field edits now clear the previous coordinate and Maps link before
  the debounced geocoder resolves the changed address, preventing a stale pin
  from being stored against different address text.
- Added non-blocking legacy CP repair. The mobile CP list queues missing
  coordinates in bounded batches, suppresses duplicate work for ten minutes,
  and writes recovered coordinates to both the linked client place and visit
  snapshot. Existing valid place coordinates are copied without an external
  request; address-only rows are geocoded asynchronously.
- Added a regression test for legacy snapshot repair. Focused coordinate/proof
  tests now pass 5/5, and focused ESLint passes for the shared component,
  geocoding backend, and test.
- Final validation passed: the focused suite remains 5/5, focused ESLint is
  clean across the shared address component and touched CP/project forms,
  `git diff --check` passes, `/marketing/cp-visits` responds HTTP 200 on the
  existing MMS dev server at `http://localhost:3000`, and the map proxy resolves
  Velachery to `12.9754605,80.2207047`.
- Browser visual automation could not navigate away from its stale localhost
  connection-error tab because of the in-app browser URL policy; runtime HTTP
  compilation and DOM-facing lint/tests were used instead. The existing MMS
  server is available on port 3000 (a second port-3100 server is intentionally
  rejected by Next.js while the first dev instance owns the project lock).
- A separate concurrent change appeared in `convex/marketing/siteVisits.ts`
  while this task was running. It concerns SV outcome authorization after
  counselling and was not edited, reverted, or included in this address work.
- No commit, push, Convex deployment, Android source edit, or production
  deployment was performed. The schema/action repair requires the normal admin
  Convex deployment before legacy live records begin self-healing.

### Session 141 - Static coordinate confirmation in address forms

**Date:** 2026-08-04

- Added a permanent Saved coordinates section below the shared address fields.
  Valid pins display latitude and longitude separately at seven-decimal
  precision; unpinned addresses display an explicit Coordinates not set state
  with a prompt to use Drop pin.
- Simplified the top Exact location strip to show only pin-capture status so
  the numeric values have one consistent, easy-to-check location at the bottom
  of every CP, SV, and project form using the shared component.
- Validation is pending. No commit, push, deployment, or Android edit has been
  performed in this turn.
- Validation completed: focused ESLint for
  `components/unified-address-fields.tsx` passed, `git diff --check` passed,
  and the running MMS CP page responded HTTP 200 from
  `http://localhost:3000/marketing/cp-visits`. No commit, push, Convex
  deployment, Android edit, or production deployment was performed.

### Session 142 - Historical CP and SV coordinate recovery

**Date:** 2026-08-04

- Reviewed the existing shared address/pin work and legacy CP repair path.
- Started extending bounded background recovery to past CP and SV records so
  linked saved coordinates are reused first and address-only records are
  geocoded without delaying list responses.
- Records without either trustworthy coordinates or a usable address will be
  left unresolved to avoid assigning an incorrect map pin.
- Implementation and validation are in progress. No commit, push, deployment,
  or Android source edit has been performed in this turn.
- Added legacy SV coordinate recovery in the MMS backend. An SV now reuses the
  exact linked CP visit/client-place pin immediately in enriched web/mobile
  responses and persists that pickup coordinate snapshot in the background.
- Added address-based fallback geocoding for direct historical SVs that have a
  usable pickup address but no saved coordinates. Invalid and legacy `0,0`
  values are treated as missing.
- Added a bounded 15-minute CP/SV historical repair cron. Each pass advances
  through unresolved rows, copies linked coordinates without an external
  request, and staggers at most eight CP plus eight SV geocodes to avoid API
  bursts. Addressless rows are marked examined and intentionally remain
  unpinned rather than receiving an inaccurate location.
- Mobile CP/SV list endpoints continue to trigger immediate bounded repair for
  currently fetched rows; historical records outside the visible date range
  are handled by the cron.
- Added an SV schema throttle field and a regression case proving that a legacy
  SV inherits the exact linked CP pin while a newer addressless row does not
  block the historical sweep.
- Validation passed: focused coordinate/proof tests are 6/6, focused ESLint is
  clean for the repair module/test/cron, `git diff --check` passes, and the
  running MMS `/marketing/site-visits` page responds HTTP 200. The test harness
  still prints its pre-existing asynchronous push-notification transaction
  warning after reporting success. A repository-wide TypeScript check exceeded
  the 120-second limit without producing a focused error; the broad HTTP lint
  remains noisy from its existing explicit-`any` backlog.
- Preserved the separate concurrent SV outcome-authorization change in
  `convex/marketing/siteVisits.ts`; it was not reverted or rewritten.
- No commit, push, Convex deployment, Android source edit, or production
  deployment was performed. The schema, cron, and repair functions require the
  normal admin Convex deployment before live historical rows begin repairing.

### Session 143 - Pin reverse-geocode field population

**Date:** 2026-08-04

- Investigating why a confirmed map pin persists latitude/longitude but leaves
  the shared address fields empty.
- The issue is isolated to the MMS shared address component's pin-dialog
  reverse-geocode/result mapping; implementation and validation are in
  progress. No commit, push, deployment, or Android source edit has been made.
- Fixed the pin dialog's early return when the Google geocoder library is not
  ready. Pin clicks and marker drags now always attempt the same-origin reverse
  geocoder fallback, populate structured door/street/address/city/state/pincode
  components, and ignore stale responses after the marker moves again.
- Added `GET /api/map/reverse-geocode` as a server-side Nominatim proxy with
  coordinate validation, required provider headers, timeout, and caching. This
  avoids browser CORS/key restrictions silently leaving the fields empty.
- Confirm location remains disabled while reverse geocoding is active, so the
  coordinates cannot be accepted before the resolved address is ready.
- Validation passed: focused ESLint is clean for the shared component and new
  route, `git diff --check` passes, and the screenshot coordinates
  `13.0436392,80.2121029` resolve through the local route to Jawaharlal Nehru
  Road, Chennai, Tamil Nadu `600083` with structured road/city/state/pincode
  values.
- No commit, push, Convex deployment, Android source edit, or production
  deployment was performed in this turn. This UI/API route change requires the
  normal MMS web deployment; it does not require a Convex schema deployment.

### Session 144 - Historical coordinate deployment clarification

**Date:** 2026-08-04

- Confirmed that historical CP/SV coordinate repair is automatic only after
  the pending Convex schema/functions/cron are deployed; publishing only the
  MMS web reverse-geocode route fixes new/manual pin interactions but does not
  start the database backfill.
- Existing rows with a usable saved address will be geocoded progressively,
  and rows linked to an existing CP/client-place pin will reuse that exact pin.
  Rows with neither coordinates nor a usable address cannot be resolved safely
  and remain explicitly unpinned until their address is corrected manually.
- No files other than this mandatory local agent log were changed. No commit,
  push, build, test, or deployment was performed.

### Session 145 - Commit and push current repository changes

**Date:** 2026-08-04

- Began auditing MMS, Mconnect, and Travel Desk worktrees, branches, remotes,
  and outstanding changes before committing and pushing the current work.
- `AGENT_LOG.md` remains local-only and will not be staged or pushed.
- Git audit, commit, and push are in progress.
- Audit found MMS changes on `max` and Android source changes on `merge`;
  Travel Desk `aizen` is clean. MMS remote `origin/max` advanced by one commit
  during the work, so the local MMS commit will be rebased onto it before push.
- Mconnect `origin` has two configured push URLs (company and D-A-R-X). The
  current tracked `origin/merge` branch is up to date. The IDE deployment target
  file and this log will be excluded from staging.
- Pre-commit validation passed: MMS coordinate/proof tests are 6/6, focused
  ESLint and `git diff --check` pass, and Mconnect `:app:assembleDebug` completed
  successfully using Android Studio's bundled JBR.
- Created MMS commit `705fcdb6` and Mconnect commit `8c1de1c`. The MMS commit
  was then rebased cleanly onto the newly fetched `origin/max`; its final hash
  will be recorded after push. No conflict resolution was required.
- Pushed MMS `max` at final commit `89e120b8d9baa783e53a54a7253cebd04333075b`
  to `manjugroupsdev/manjusitedevelopment`; remote-tip verification matches.
- Pushed Mconnect `merge` commit
  `8c1de1c5342a10e219e57f5c4259a84e91ad9dbc` to both configured remotes:
  `manjugroupsdev/Mconnect` and `D-A-R-X/Mconnect`; both remote tips match.
- Travel Desk `aizen` had no changes and was already up to date; an explicit
  push confirmed there was nothing pending.
- Final worktree state: MMS and Travel Desk are clean and synchronized.
  Mconnect source is synchronized; only the intentionally uncommitted local
  `.idea/deploymentTargetSelector.xml` and mandatory `AGENT_LOG.md` remain.
- Push task completed. No deployment or Convex deployment was performed.

### Session 146 - Live historical pin diagnosis

**Date:** 2026-08-04

- Confirmed that missing coordinates on existing production CP cards are
  expected while the new Convex repair backend remains undeployed. A Git push
  alone does not activate the schema field, repair mutations, or 15-minute
  historical backfill cron.
- Repository deployment wiring contains only the explicit
  `convex:deploy:prod` package script; no checked workflow automatically runs a
  Convex deployment from the `max` push.
- Once the admin deploys Convex, linked existing pins can appear immediately
  on fetch and address-only rows will be repaired progressively in bounded cron
  batches. Rows lacking both a pin and usable address still require correction.
- Noted new post-push MMS worktree edits in auth/SV/CP files from another
  source and left them untouched. No code, commit, push, test, or deployment was
  performed in this turn; only this mandatory local log was updated.

### Session 120 (main-chat) - QR outcome@dropped, CP pin, single-device logout, rejected-SV visibility

**Date:** 2026-08-02
**Session:** main. app (Mconnect/merge) + web (manjusitedevelopment/max). mfpl.

- SV EDGE-CASE FIXES (web/max, staged): reassign "Handover" wording widened
  (ACTIVE_SV_STATUSES += on_counselling/picked_from_site/dropped); markPickedUp
  rejects own_vehicle (use markClientStarted). Verified correctOutcome guard,
  listPending pending-only, createAndSendWhatsApp resilience, reassign notify,
  advanceCabLifecycle IAM, IRIS convertedSiteVisitId, rollup integrity — no fix
  needed. 25/25 SV tests pass.
- QR OUTCOME @ DROPPED: getByQrPayload canRecordOutcome now covers
  on_counselling|picked_from_site|dropped (was on_counselling only) — matches
  setOutcome. App: ScannedSiteVisitStaff gains _id; QrScannerFragment authorises
  the assigned incharge/BDO client-side (works pre-deploy) across those statuses;
  ConfirmSheet showOutcome broadened. (Fork committed the QR/CP app edits as
  8c1de1c5/b236fff1.)
- CP COORDINATE PIN: CreateCpVisitBottomSheet geocodes the typed address at
  submit when no pin dropped; requires a pin if unlocatable — so a CP always
  ships coords and the trip map pins the client (was 0,0/ocean). Also flagged the
  Google Cloud fix: enable Geocoding API on the Maps keys (REQUEST_DENIED).
- SINGLE-DEVICE LOGOUT: backend logout now deactivates ALL the staff's sessions
  (not just the token) so the dialer single-device block always releases →
  re-login on another mobile works. App LogoutBottomSheet gives api.logout its
  own 4s budget so it's never starved.
- REJECTED SV VISIBILITY (both app + web): CP-reject SV-cancel cascade no longer
  gated on leadId (was invisible for lead-less immediate SVs); stamps
  "[CP rejected by <name>] <reason>" marker; mobile mapper sends notes; app +
  web render a distinct "Rejected" badge/pill with by-name + reason (date =
  cancelledAt). Appears in the Cancelled tab.
- Builds: :app:compileDebugKotlin OK; convex tsc clean for my files. Pre-existing
  pull errors on max (site-visits-list-page 1304/1331/1402, clientPlaceVisits
  808-809, whatsappInbound 201, http.ts 11068) would block a convex/web deploy —
  not mine, flagged for a cleanup pass.
