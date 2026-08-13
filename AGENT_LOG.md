# AGENT_LOG.md

> **⚠️ LOCAL ONLY — DO NOT COMMIT OR PUSH**
> This file is listed in `.gitignore` and must stay that way.
> It is a running logbook for AI agents (Antigravity / Claude / Gemini, etc.)
> working on this repository so each session picks up exactly where the last left off.

## Mandatory Update Rule

**MUST DO — non-negotiable:** Every AI (Claude / Antigravity / Gemini / any
model) must update this file **after each and every response/turn**, without
exception, and after every meaningful change. This applies even when the
response only reports status, answers a question, performs an investigation, or
makes no file changes. A turn is not finished until its AGENT_LOG entry is written.

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

## Session 121 (main-chat) — SV confirm IAM gate (marketing.siteVisits.confirm)
- Bug: LMO (and any user) could Confirm SV without permission — the Confirm SV
  button was always shown and `confirmPendingHandoffAsSiteVisit` had NO auth.
- New permission `marketing.siteVisits.confirm` added to lib/iam-model.ts
  (PERMISSIONS catalog + "Marketing — Site Visits" group + module tree). Super
  admins inherit it via ALL_PERMISSIONS; every other designation must be granted
  it explicitly (this is the intended gate).
- Backend gated (all three confirm paths):
  1. convex/marketing/outOfStationHandoffs.ts `confirmPendingHandoffAsSiteVisit`
     mutation + `...AndSendWhatsApp` action now take sessionToken and call
     requirePermissionForSession(..., "marketing.siteVisits.confirm").
  2. convex/dailyTasks.ts updateStatus — completing a handoff-source task (web
     dropdown + mobile swipe via /api/dailyTasks/updateStatus) now checks
     loadEffectivePermissions before internalConfirmHandoffAsSv (closes the
     task-manager bypass).
- Frontend hidden + guarded:
  - components/handoff/handoff-resolution-dialog.tsx (Confirm SV button) — gated
    on can("marketing.siteVisits.confirm"), passes sessionToken.
  - features/telecaller/leads/detail/components/GmHandoffBanner.tsx — new
    canConfirm prop hides "Complete & create SV"; wired from lead-detail-page.tsx
    which also passes sessionToken to the confirm action.
- Web-only UI; mobile confirm path is covered by the backend gate (no app change).
- convex + web tsc clean for all edited files (pre-existing *.test.ts and prior
  pull errors unchanged). STAGED for mfpl deploy — not deployed, not pushed.
- ACTION NEEDED post-deploy: an IAM admin must grant marketing.siteVisits.confirm
  to the designations that legitimately confirm SVs (GM / senior managers),
  otherwise even authorized non-admin confirmers lose the button.

## Session 122 (main-chat) — SV handoff "Awaiting" staff mismatch after Super Admin edit
- Bug reported: after Super Admin edits staff (esp. GM) in the Resolve Site Visit
  Handoff modal and saves, the pending SV list "Awaiting" still shows the old
  staff (e.g. modal GM = KALYANARAMAN.D but list Awaiting = GM · SRIKANTH.M).
- Investigation (read-only trace): the list "Awaiting" cell renders h.manager
  (features/marketing/pages/site-visits-list-page.tsx:2653) which comes from
  listPending → ctx.db.get(row.managerStaffId) (outOfStationHandoffs.ts:168).
  Schema defines managerStaffId AS proposedSiteVisit.gmStaffId (schema.ts:6707).
  updatePendingHandoff already recomputes managerStaffId from the edited GM, and
  the web modal's buildProposedSiteVisit re-sends gmStaffId — so on `max` the
  edit→Awaiting sync is already correct and reactive. Conclusion: the LIVE site
  runs the older backend; this Convex logic is STAGED for the mfpl deploy (same
  situation as the rejected-SV fix). No app change needed.
- Genuine gap fixed (defensive, safe): updatePendingHandoff previously only
  recomputed managerStaffId from the gmStaffId the client RE-SENT. A partial
  payload that patched proposedSiteVisit WITHOUT gmStaffId left managerStaffId
  stale. Changed convex/marketing/outOfStationHandoffs.ts (updatePendingHandoff)
  to resolve an effectiveGmStaffId = args.proposedSiteVisit.gmStaffId ??
  row.proposedSiteVisit.gmStaffId, and re-sync managerStaffId whenever it differs
  from the current manager — self-heals stale approvers on any edit. No-op in
  steady state; validates the GM is active (throws otherwise).
- Files changed: convex/marketing/outOfStationHandoffs.ts (1 mutation). No app or
  travel-desk change. Web frontend unchanged (already correct).
- Validation: npx tsc --noEmit on the edited convex file → clean (exit 0).
  Pre-existing *.test.ts / prior-pull errors elsewhere unchanged, not mine.
- Deployment requirement: STAGED for mfpl Convex deploy — NOT deployed, NOT
  pushed (awaiting user's "push all changes"). Until deploy, the live site will
  keep showing the old Awaiting value because it runs the older backend.
- Also strengthened the AGENT_LOG Mandatory Update Rule wording per user request
  (update after each response by ANY AI, non-negotiable).

## Session 123 (main-chat) — SV outcome form must stay recordable through completed
- Reported (app, SiteVisitOverviewFragment): a cab SV at DROPPED (and completed
  SVs) showed the Outcome buttons (Converted as Booking / Client Not Interested /
  Follow up) greyed/locked. User: outcome must be recordable from on_counselling
  through every later status INCLUDING completed/done, and must not be "incomplete".
- Root cause:
  1. App locked the form via isTerminalOutcome(visit) which trips on
     isTerminalOutcomeStatus(visit.status) — and that set includes lifecycle
     "completed"/"done"/"closed". A cab trip finished (or fleet completed-offline)
     sets a terminal-ish status before field staff record the sales outcome, so
     the form locked with no outcome recorded.
  2. Backend setOutcome (convex/marketing/siteVisits.ts) assertTransition only
     accepted on_counselling/picked_from_site/dropped — it REJECTS "completed",
     so even if the button were enabled, submit would error.
- Fixes:
  - App (SiteVisitOverviewFragment.kt): new isOutcomeAlreadyRecorded(visit) +
    isOutcomeRecordedStatus() that lock ONLY on an actually-recorded/terminal
    outcome (non-blank visit.outcome, cancelled/no_show/converted_to_booking/
    not_interested/postponed/other status, convertedBookingId, cancelledAt) —
    NOT on bare lifecycle completed/done/closed. isOutcomeLocked now uses it
    (both enriched bind ~1214 and first-frame bind ~439). isOutcomeStatusEligible
    now also includes completed/complete/done/closed so buttons enable when the
    lifecycle is finished but the outcome is still pending. Both cab and
    own-vehicle gate branches share these vars, so both are fixed.
  - Backend (siteVisits.ts setOutcome): assertTransition now accepts "completed"
    as a source status WHEN no outcome exists yet (outcomeAlreadyRecorded =
    Boolean(visit.outcome)). Once an outcome is recorded, "completed" is dropped
    from the set so setOutcome can't silently overwrite (edit goes via editOutcome).
- Not changed (scoped out): QR-scan getByQrPayload canRecordOutcome guards
  (2358 / 2564) still on_counselling/picked_from_site/dropped — the reported
  surface is the detail Outcome form, not the QR flow. Flag if QR should match.
- Validation: convex `tsc --noEmit` clean for siteVisits.ts; app
  `:app:compileDebugKotlin` BUILD SUCCESSFUL (JBR).
- Deployment: convex change STAGED for mfpl deploy (not deployed). App change is
  local (rebuild/reinstall APK to see it). NOT pushed — awaiting "push all changes".

## Session 124 (main-chat) — push all changes
- User: "push all changes". Committed + pushed this session's work.
- APP (Mconnect, branch merge): commit 3a0f4715 (SiteVisitOverviewFragment.kt —
  outcome-through-completed fix). Pushed to origin merge → BOTH remotes
  (manjugroupsdev + D-A-R-X). f3a67498..3a0f4715. .idea/* and AGENT_LOG.md
  deliberately NOT committed (IDE noise / local-only log).
- WEB (manjusitedevelopment, branch max): commit 9df8c35e — 7 files:
  marketing.siteVisits.confirm IAM gate (iam-model.ts, outOfStationHandoffs.ts,
  dailyTasks.ts, handoff-resolution-dialog.tsx, lead-detail-page.tsx,
  GmHandoffBanner.tsx) + updatePendingHandoff Awaiting re-sync + siteVisits.ts
  setOutcome completed-source. Pushed to origin max. fed8b57c..9df8c35e.
- No push to main (rule honored). Convex NOT deployed (staged for mfpl).
- Validation before push: convex tsc clean (my files); app compileDebugKotlin OK.

## Session 125 (main-chat) — OTP entry field must appear even when OTP request limit reached
- Reported (app, Trip Details / TripNavigationFragment "Swipe to Complete Trip"):
  when the server returns "Maximum OTP requests reached for this visit", the app
  only toasted and reset the swipe — no OTP field appeared, so a staff given the
  code by an admin (who can view the client OTP) had nowhere to enter it.
- Root cause: requestArrivalOtpThenOpenCamera() treated ANY resp.success==false as
  a hard block (toast + return), never opening ArrivalOtpBottomSheet. The rate
  limit is on GENERATING OTPs, not verifying them.
- Fix (app, TripNavigationFragment.kt only): in the !resp.success branch, detect
  the OTP request rate-limit (errMsg contains maximum otp / otp requests reached /
  max otp / too many otp). On that specific case, show an informative toast and
  FALL THROUGH to the normal proof + OTP-entry flow (camera→upload→OTP sheet for
  standard, direct OTP sheet for gift distribution) instead of returning. Genuine
  location/distance blocks and other errors still hard-stop as before. alreadyVerified
  branch now sets arrivalInProgress=false explicitly (moved off the shared top-of-
  block reset).
- Verified backend needs NO change: convex/hr/fieldVisitOtp.ts verifyArrivalOtp
  checks the stored arrivalOtpHash + expiry + MAX_VERIFY_ATTEMPTS only — it is
  independent of the request-count limit, so an admin-relayed still-valid OTP
  verifies (and it re-validates arrival location at verify time). ArrivalOtpBottomSheet
  does not auto-send on open (caller sends once; sheet only verifies + manual resend),
  so opening it on the fallback doesn't re-hit the limit.
- Validation: :app:compileDebugKotlin BUILD SUCCESSFUL (JBR). No web/convex change.
- Deployment: app-only, local — rebuild/reinstall APK to see it. NOT pushed
  (awaiting "push all changes").

## Session 126 (main-chat) — push OTP fallback
- "push all changes". APP (Mconnect, merge): commit 5dd19879 (TripNavigationFragment.kt
  OTP-limit fallback). Pushed origin merge → BOTH remotes. 3a0f4715..5dd19879.
- No web/convex change this turn. .idea/* + AGENT_LOG.md not committed. No main push.

## Session 127 (main-chat) — CP visit: address=name/number, 0,0 map, not-showing-on-mobile, confirm-gate regression
- Reported: CP visits with missing map coords show the client name/number in the
  ADDRESS field; a CP that is "in-progress" on web does not appear at all in the
  mobile CP Visits list (super-admin, searching by mobile → No Matches); staff hit
  OTP max-tries and can't end the CP (loops back to "Start Trip").
- Diagnosis (with subagent trace):
  * enrichVisit recovers an address from clientPlace/client but the mobile LIST
    endpoint listMobileCompact (clientPlaceVisits.ts) returned coords from
    NON-EXISTENT columns visit.lat/visit.lng (schema fields are visitLat/visitLng)
    and never returned a resolved clientPlace object → every list row shipped
    coordinate-less + address-less → TripNavigationFragment fell back to showing
    the client NAME in the address slot and pinned the map at 0,0.
  * Mobile CP list capped at 20 rows with CLIENT-SIDE search → a viewAll
    (super-admin) caller could never find a specific client company-wide.
  * OTP "can't end": already fixed in Session 125 (TripNavigationFragment OTP-limit
    fallback), pending their APK rebuild.
- Fixes:
  * convex/marketing/clientPlaceVisits.ts listMobileCompact: fetch clientPlace,
    resolve address (visitAddress||clientPlace.address||formattedAddress||
    client.homeAddress||client.location) + coords (visitLat/Lng||clientPlace||
    client) + maps link; return visitLat/visitLng + lat/lng + a clientPlace object.
    Raised row cap 20→200.
  * convex/http.ts /api/marketing/clientPlaceVisits/my: limit cap 20→200.
  * app GeoTrackApi.getMyMarketingCpVisits: new optional limit query param;
    CpVisitsFragment requests limit=200 (browsable list search now reaches any
    client). Home/today merges unchanged (default limit).
  * app TripNavigationFragment: address card no longer falls back to client name
    (shows "Address not available" instead of the misleading name).
- ALSO fixed a regression from Session 121: http.ts /api/marketing/
  outOfStationHandoffs/confirm called confirmPendingHandoffAsSiteVisitAndSendWhatsApp
  WITHOUT the now-required sessionToken (tsc error). Now passes sessionToken=auth.token
  (bearer == session token via authenticateRequest), gating the mobile confirm path
  with marketing.siteVisits.confirm too.
- Validation: convex tsc clean for clientPlaceVisits.ts + http.ts; app
  :app:compileDebugKotlin BUILD SUCCESSFUL.
- Deployment: convex changes STAGED for mfpl deploy (address/coords/limit/confirm-gate
  fix are NOT live until deployed). App changes need an APK rebuild. NOT pushed yet.
- STILL PENDING (earlier ask): add drop-pin/map location picker to the WEB client
  page (features/marketing/pages/client-detail-page.tsx) + persist client.lat/lng —
  this is the durable coord source so CP visits inherit a pin going forward.

## Session 128 (main-chat) — use client's stored address for CP address + map pin
- User: clients already have addresses stored in the web Clients tab — use that in
  the CP address field and mark the map pin.
- Added composeClientAddress(client) in convex/marketing/clientPlaceVisits.ts:
  joins the client master's stored address columns (doorNo, addressLine1/2,
  landmark, homeAddress[="Full Address"], location[=city], district, state,
  pincode), deduped case-insensitively, → one geocodable line. Now used as the
  address fallback in BOTH listMobileCompact (mobile list) and enrichVisit (detail)
  so a CP visit with no address of its own shows the client's real address.
- Map pin: client rows have no saved lat/lng, but the app already geocodes the
  address when coords are missing (TripNavigationFragment.geocodeDestinationIfNeeded
  → DirectionsClient.geocodeAddress). usableCoordPair rejects null/out-of-range/
  (0,0), so the compact mapper returns null coords for those → forPlace() omits
  ARG_DEST_LAT/LNG → geocode runs on the client-derived address → pin drops. No app
  change needed this turn.
- Validation: convex tsc clean for clientPlaceVisits.ts.
- Deployment: convex change STAGED for mfpl deploy (not live until deployed). NOT pushed.
- Still pending: web client-page drop-pin + persisting client.lat/lng (durable pin
  shown everywhere incl. web, avoids per-open geocoding).

## Session 129 (main-chat) — app shows "Start Trip" while web shows in-progress (CP status desync)
- Reported: staff already started/reached the CP and is trying to record the
  outcome, but the app shows "Start Trip" again (Not Started) while the web shows
  Field visit = in-progress. OTP also blocks completion (2 sends done, not verified).
- Root cause: the spawned fieldVisits row carries the AUTHORITATIVE trip status
  (in-progress/arrived/completed); the clientPlaceVisit's own status stays
  "scheduled" until the outcome is recorded. HomeViewModel.toTodayVisitOrNull
  (line 518) already prefers fieldVisit.status, and TripNavigationFragment (line
  445-459) maps "in-progress" → renderArrivalPhase(false) = the started arrival
  flow. BUT listMobileCompact returned only fieldVisitId, never the resolved
  fieldVisit object → this.fieldVisit was null → effectiveStatus fell to the CP
  "scheduled" → app re-offered "Start Trip".
- Fix: convex/marketing/clientPlaceVisits.ts listMobileCompact now fetches the
  fieldVisit (via fieldVisitId) and returns a fieldVisit object
  { _id, status, arrivalRequestedAt, arrivalVerifiedAt }. The app then reads the
  in-progress status and shows the arrival/complete flow instead of Start Trip,
  matching web. No app change needed (app already consumes fieldVisit.status).
- OTP-blocking-completion is the Session 125 fix (OTP-limit fallback opens the
  entry field so the staff types the admin-revealed OTP) — pending APK rebuild.
- Validation: convex tsc clean for clientPlaceVisits.ts.
- Deployment: convex change STAGED for mfpl deploy (not live until deployed). NOT pushed.

## Session 130 (main-chat) — Edit CP Visit modal: assigned staff shows raw id; deploy-gated mobile symptoms
- User (now testing web on localhost:3000 = updated code): Edit CP Visit modal
  "Staff name" shows a raw staff id instead of the assigned staff; address still
  not on mobile + trip still asks to Start; also address fields duplicating.
- FIXED: components/lead/edit-cp-visit-dialog.tsx eligibleStaffItems now always
  includes the currently-assigned telecallerStaffId (found in staffList) even when
  it fails the CP "eligible LMO" category filter (assigned staff was a BDO), so the
  SearchableSelect resolves the saved id → readable name instead of the raw id.
  Added telecallerStaffId to the memo deps. tsc clean for the file.
- FOUND but NOT changed (needs coordinated backend+frontend fix on the OTHER
  chat's pin feature — flagged to user, not touched to avoid clobbering it):
  address duplication. handleSave folds street into addressLine2
  ([street, addressLine2].join) while joinUnifiedAddress already includes street,
  and updateLocationAndNotes has no streetName arg, so each save+reopen grows the
  address ("5/215 5/215 Vivekanadar Street ..."). Proposed fix: add streetName arg
  to updateLocationAndNotes + patch clientPlace.streetName; frontend send street
  separately and store addressLine2 as-is.
- CRUX (repeated): mobile symptoms (address not available, 0,0 map, "Start Trip"
  desync) are ALL already fixed in code (Sessions 127-129: listMobileCompact
  address/coords/fieldVisit status, composeClientAddress) but the MOBILE app hits
  the DEPLOYED backend (api-mfpl), which does NOT have these convex changes. They
  stay invisible on mobile until convex is deployed to whatever the app targets.
- No push this turn. Web change staged on max.

## Session 131 (main-chat) — address duplication fix + robustness assessment (deploy guarantee)
- Confirmed app BASE_URL defaults to https://api-mfpl.theairix.com/ (prod) — the
  freshly-installed phone hits PROD, which lacks Sessions 127-129 convex fixes.
  That is why mobile still shows "Address not available" / 0,0 / Start-Trip: the
  fixes live on the user's localhost convex (web), NOT on api-mfpl. Everything
  mobile is blocked on deploying convex to the backend the app targets.
- Fixed address duplication (Edit CP Visit modal): components/lead/
  edit-cp-visit-dialog.tsx handleSave no longer folds location.street into
  addressLine2 (joinUnifiedAddress already includes street) — stopped the
  save+reopen growth ("5/215 5/215 5/215 ..."). Reverted an attempted streetName
  persist (clientPlaces has no streetName column). Typed my staff-picker additions.
  NOTE: existing corrupted addresses don't self-heal — a re-save with clean fields
  is needed once; going forward no growth.
- Also (Session 130) edit-cp-visit-dialog staff picker now always includes the
  assigned staff so it shows the name not the raw id. tsc: only pre-existing
  baseline implicit-any at lines 77/87 remain (staffList loosely typed, not mine).
- Robustness assessment for "will deploy show address+marker on ALL past/corrupted
  CP & SV":
  * ADDRESS text — yes after deploy: resolvedAddress falls back to
    composeClientAddress(client) for CP; SV uses pickupAddress/project.
  * MARKER — only guaranteed when coords exist OR geocoding succeeds. Infra exists:
    clientPlaceGeocoding.ts backfills + persists coords for BOTH CP (clientPlaces)
    and SV (pickup*), triggered by the /my route for missing-coord rows, plus
    app-side per-open geocode. BUT: (1) depends on Google Geocoding API being
    ENABLED — earlier REQUEST_DENIED would make ALL coord-less geocoding fail;
    (2) the backfill geocodes the (possibly corrupted) visit/place address, so it
    should prefer the client's CLEAN composeClientAddress; (3) backfill is gradual
    (12/list-load) not one-shot.
- Proposed next (not yet built): one-shot backfill over all CP+SV geocoding the
  client's CLEAN address + persisting coords, so past/corrupted rows get a precise
  pin. Gated on confirming the Geocoding API is enabled (a Google Cloud setting —
  I cannot change it).
- Validation: convex tsc clean for clientPlaceVisits.ts. Web baseline 77/87 only.
- Nothing pushed. Convex changes STAGED for mfpl deploy.

## Session 132 (main-chat) — "Start Trip again" after exiting Trip Details (deploy-independent app fix)
- Repro: staff starts the nadhiya trip on the phone, exits Trip Details, re-opens
  → shows "Start Trip" again.
- Root cause: Trip Details renders from the backend list-row status. The CP row's
  own status stays "scheduled" until the OUTCOME is recorded; the in-progress
  state lives on the fieldVisit. reconcileCpVisitStatusFromServer already reads
  cp.fieldVisit?.status (my Session 129 field) — but the phone hits PROD
  (api-mfpl) where listMobileCompact does NOT yet return fieldVisit, so effective
  falls back to cp.status="scheduled" → pre-start phase → "Start Trip".
- Fix (APP-ONLY, works on the CURRENT prod backend, NO convex deploy needed —
  just rebuild the APK): TripNavigationFragment now records started trips on-device
  (SharedPreferences "trip_local_started", keyed by visitId/cpVisitId/placeId).
  * markVisitLocallyStarted() on successful Start Trip.
  * bindInitialArgs + reconcile "else" branch: if the backend status is pre-start
    (scheduled/assigned/pending/blank) AND this device started it, render the
    enroute/arrival phase instead of Start Trip. Backend arrived/completed still
    win (checked first).
  * clearVisitLocallyStarted() on server-terminal status (reconcile completed
    branch) and in finalizeCompleteVisit() so a finished trip isn't masked.
- Self-limiting: the bridge only applies while the backend still says pre-start;
  once the row advances (arrived/completed) the backend branches take over.
- Validation: :app:compileDebugKotlin BUILD SUCCESSFUL.
- Deployment: APP-ONLY — rebuild/reinstall the APK and it works against prod as-is.
  (Once convex is deployed, reconcile's fieldVisit.status path also kicks in and
  gives the more precise arrived/enroute state.) NOT pushed.

## Session 133 (main-chat) — verify + push all changes
- Verification: full convex tsc → my files clean (clientPlaceVisits.ts has ZERO
  errors, even removed the pre-existing visit.lat/lng ones); remaining errors in
  http.ts (12107/12117/17429) + edit-cp-visit-dialog (77/87) are pre-existing
  baseline implicit-any on lines I did not touch. app :app:compileDebugKotlin
  BUILD SUCCESSFUL. Edge review: local-started bridge self-limits (only when
  backend pre-start) + clears on completion/terminal; reconcile fetch widened to
  limit=200 so the CP is reliably found; listMobileCompact override-after-spread
  order correct; only browsable list requests limit=200 (Home unchanged).
- Pushed: APP merge 5dd19879..36959228 → BOTH remotes. WEB max 9df8c35e..6aca8869.
- LIVE reality (honest): APP changes (Start-Trip local bridge, address fallback,
  list limit) work on CURRENT prod after an APK REBUILD — no convex deploy needed
  for the Start-Trip fix. WEB/convex changes (address/coords/fieldVisit resolution,
  confirm-gate, duplication) need a CONVEX DEPLOY to mfpl to show live.
- Remaining gaps I could NOT close (flagged, not "zero-flaw"): (1) convex not
  deployed; (2) existing corrupted addresses (nadhiya) don't self-heal — need a
  clean re-save; (3) coord-less rows pin only if Google Geocoding API is enabled
  (old REQUEST_DENIED); (4) SV pickup path unchanged this session; (5) repo-wide
  pre-existing baseline tsc errors remain (cleanup pass would help a strict deploy).

## Session 134 (main-chat) — Schedule SV: client-name field + create Client/Lead for new clients
- Feature: when the "Schedule site visit" modal finds no lead for the phone, allow
  scheduling for a NEW client — capture the client name and persist a Client (+
  Lead) record so it shows on the Clients page and links to the visit. User chose
  Client+Lead creation and Client-as-source-of-truth (bidirectional) sync.
- Investigation: building blocks already exist — clients.upsertByMobile (find/create
  client by phone with profile name+address+lat/lng, and propagateClientNameToLeads
  links it to leads by phone); telecallerLeads.create (dedups by phone, returns lead).
  Prior no-lead path was BROKEN (submit used selectedLead!._id; validation at ~1423
  required a lead).
- Implemented (features/marketing/pages/site-visits-list-page.tsx, web only, no
  backend change): cClientName state + reset; "Client name" field rendered when
  phone has no lead match (required); createLead + upsertClient useMutation hooks;
  validation now requires name+10-digit phone (not a linked lead) for non-direct_sv;
  in handleCreate, compute effectiveLeadId = selectedLead ?? (createLead + upsertClient
  with name/address/coords, sourceLeadId link); all leadId call-sites
  (requestOutOfStationHandoff, createClientPlaceVisit, createVisit) now use
  effectiveLeadId. Existing lead-linked flow unchanged (creation skipped when a lead
  is selected). Bonus: the new client is saved WITH lat/lng, so future visits inherit
  a real map pin (addresses the client-has-no-coords gap for new clients).
- Validation: web tsc clean for the file (no errors at all).
- REMAINING (bidirectional write-back the user asked for): editing a VISIT's address
  should patch the linked Client too (updateLocationAndNotes / SV edit → clients).
  Client→visit direction works now (clientId linkage + propagateClientNameToLeads);
  visit→client write-back is the outstanding piece. Not yet built.
- New feature — NOT pushed; user should test on localhost first. Convex unchanged
  so this works on their running dev backend immediately.

## Session 135 (main-chat) — Booking port Phase 1: API contract + backend HTTP gap
- Task: port the web New Booking form (booking-new-page.tsx, 4116 lines: 3 tabs,
  ~60 fields, GST/schedule/self-cash calcs, 7 doc uploads, ~100-field payload) to
  the app to full parity (UI + all logic/calcs/rules/types). Mapped both sides via
  2 subagents (web form spec + backend contract). Backend: single mutation
  api.bookings.create; HTTP POST /api/bookings already exists (only clientName,
  mobileNumber, bookingDate required; server derives ref-no, charges subtotal,
  *AtBooking snapshots, exchange balance; perm = marketing.bookings.create).
- Finding: Kotlin CreateBookingRequest was ALREADY ~90% complete (~95 fields).
  Missing: aadhaarBack/cefFront/cefBack doc ids+names, flexiPaymentSchedule,
  conversionExchangeAmount, skipApproval. Backend HTTP mapper
  (bookingCreateArgsFromHttpBody) did NOT forward aadhaarBack/cefFront/cefBack or
  conversionExchangeAmount (mutation+schema DO accept them) — real gap.
- Phase 1 DONE: (app) ApiService.kt CreateBookingRequest + new FlexiPaymentRow data
  class — added the 6 doc fields, flexiPaymentSchedule, conversionExchangeAmount,
  skipApproval. (web) convex/http.ts mapper now forwards aadhaarBack/cefFront/cefBack
  (+FileName) + conversionExchangeAmount. Deliberately did NOT map skipApproval from
  HTTP (a mobile client must not bypass the booking approval workflow; web never
  sends it). Validation: :app:compileDebugKotlin BUILD SUCCESSFUL; convex tsc clean
  for http.ts region.
- REMAINING (Phases 2-5, large, multi-turn): calc engine (bookingGst + payable chain
  + booking-payment-schedule incl self-cash balancing, ported verbatim); rebuild
  fragment_booking_create.xml with all 3 sections/~60 fields; BookingCreateFragment
  logic (pickers, plot/lead/client prefill, conditional visibility by
  bookingType/category/mode/plan/profession/isAgainstSV, uppercasing, ordered
  first-error validation, 7 doc uploads via POST /api/storage/upload); verify vs
  checklist. Current mobile form uses only ~8 fields.
- NOT pushed. Convex change staged.

## Session 136 (main-chat) — Booking port Phases 2-4: calc engine + full form UI
- Built the mobile New Booking form to broad web parity. Files:
  * app BookingCalc.kt (NEW) — verbatim port of web derived values: agreedAmount,
    GST (bookingGst), grossTotalPayable, exchangeBalancePayable, totalPayable,
    bankLoanAmount, payableChain, minimumAllotmentAmount, outstandingAfterAllotment,
    standardSchedule (2nd/3rd/4th clamping), planDays, self-cash rebalanceFinalRow.
  * app res/layout/fragment_booking_create.xml — rebuilt from ~8 fields to full
    multi-section form (~55 fields): Booking Info, Client Details (+Home/Office
    address, professional), Source/Referral, Financial, Charges, Customer Funding,
    Balance Payment Schedule, Original Staff, KYC (Aadhaar/PAN/CEF uploads),
    References, Finalize. + Save Draft / Save & Send for Approval.
  * app res/values/styles.xml — added BookingSectionHeader/SubHeader/Computed/UploadRow.
  * app BookingCreateFragment.kt — full rewrite: enum pickers (AlertDialog), project/
    unit/staff pickers (SearchableSelectionDialog), 9 date pickers, plot-pricing
    prefill (getBookingPlotPrefill), live calc → agreed/GST/balance/client-payable
    displays, conditional visibility (profession/dept, isAgainstSV, SC>0, category B,
    online/instrument mode, other-dept), lead lookup + client-name prefill, 7 KYC/proof
    uploads (GetContent → uploadStorageFile → storageId+fileName), ordered required-
    field validation, full CreateBookingRequest payload incl. flexiPaymentSchedule
    from 2nd/3rd/4th rows.
- Validation: :app:compileDebugKotlin BUILD SUCCESSFUL (resources + R.id all resolve).
- Phase 1 (Session 135): API model complete + backend http mapper forwards
  aadhaarBack/cefFront/cefBack + conversionExchangeAmount.
- HONEST GAPS still not built (documented, not claimed done): CONVERSION/EXCHANGE/
  INTERNAL-EXCHANGE sub-forms (type picker exists; type-specific fields + source
  lookups not rendered — payload supports them); dynamic self-cash add/remove
  payment rows (standard 2nd/3rd/4th used instead, mapped to flexiPaymentSchedule);
  pincode auto-fill + map pin (home lat/lng/googleMapsLink not sent); reporting-chain
  auto-defaults for original staff; standard-schedule auto-amount clamping in UI.
- NOT pushed (large new UI — should be tested on a build first). Convex http.ts
  change staged.

## Session 137 (main-chat) — KYC uploads added to the VISIT→BOOKING sheet too
- User: KYC document upload + more still missing in the app. Root cause: there are
  TWO booking-create surfaces. Session 136 rebuilt the standalone BookingCreateFragment
  (has all 7 doc uploads). But the common path — CompleteCpVisitBottomSheet (the
  "Converted as Booking" flow from a CP/SV outcome, ~6500 lines) — only had
  Aadhaar-front + PAN + pay-proof, missing Aadhaar-BACK + CEF-front/back.
- Fixed CompleteCpVisitBottomSheet + its layout (outcome_body_booking_staff.xml):
  added Aadhaar Back, CEF Front, CEF Back upload buttons; BookingDocumentKind enum
  +AADHAAR_BACK/CEF_FRONT/CEF_BACK; state vars; findViewById; click handlers;
  chooseBookingDocument target + result when-branches; draft save/restore; clearForm
  reset; required-upload validation (Aadhaar Front/Back, PAN, CEF Front/Back); and
  the CreateBookingRequest payload now sends aadhaarBack/cefFront/cefBack ids+names.
- Validation: :app:compileDebugKotlin BUILD SUCCESSFUL (both surfaces + layouts).
- Depends on Session 135 backend (http.ts mapper forwards the 3 new doc fields) —
  staged for mfpl deploy; until deployed those ids won't persist server-side.

### Session 107 - GitHub #805: SV cum CP completed on Android → SV not in Site Visits (IN PROGRESS)

**Date:** 2026-08-02
**Session:** fork. HIGH-priority issue #805 assigned to DARX. Spans Android
(Mconnect) + backend/web (manjusitedevelopment). Investigating both layers.

- Symptom: complete a `cpType=sv_cum_cp` CP from Android (outcome→interested) →
  linked SV never shows in Marketing > Site Visits scheduled/list.
- Intended: CP outcome "interested" + CP.convertedSiteVisitId → backend flips the
  linked SV confirmationStatus "pending"→"confirmed" → SV appears in Site Visits.
- Suspects: (a) Android completes the CP WITHOUT calling setCpVisitOutcome
  ("interested") — my Session-94 sv_cum_cp routing/lock fix was dev-only +
  UNCOMMITTED, and env reverted to prod, so prod app may skip the confirm sheet;
  (b) backend flip condition false / sets a status the list excludes; (c) web SV
  list filter drops the flipped SV. Two Explore agents mapping backend/web + the
  Android completion path + git state of the Session-94 fix. Fix TBD after.
- MAIN CHAT: if you touch clientPlaceVisits.setOutcome flip or the siteVisits list
  query for this, coordinate here.

## Session 138 (main-chat) — push booking work
- APP (merge): commit cc2888c2 — full New Booking form (BookingCreateFragment +
  BookingCalc + layout + styles) + KYC uploads on the visit->booking sheet
  (CompleteCpVisitBottomSheet + outcome_body_booking_staff.xml) + CreateBookingRequest
  expansion. Pushed origin merge -> BOTH remotes (36959228..cc2888c2).
- WEB (max): commit 523f20fe — http.ts mapper forwards aadhaarBack/cefFront/cefBack
  + conversionExchangeAmount. 6aca8869..523f20fe.
- Rebuild the APK to see the KYC uploads on both booking surfaces. Convex change
  needs mfpl deploy for the new doc ids to persist server-side.

### Session 107 (cont.) - #805 ROOT CAUSE found

- Mobile: CLEARED. Android reliably calls setCpVisitOutcome("interested") for
  sv_cum_cp (confirm sheet opens via isSvCumCp routing, locks on
  convertedSiteVisitId → persistSvCumCpConfirm). Fix is COMMITTED in HEAD
  (bce67214 + e681195b). Not the gap.
- Backend/web ROOT CAUSE: the WEB Create-CP-Visit dialog creates sv_cum_cp CPs via
  clientPlaceVisits.createFromMobile → createCpVisitRows, which tags cpType but
  NEVER creates a linked siteVisits row and NEVER sets convertedSiteVisitId
  (clientPlaceVisits.ts:1991-2019). The setOutcome "interested" flip
  (clientPlaceVisits.ts:2677-2696) ONLY flips a pre-existing linked SV — it never
  CREATES one; outer guard `visit.convertedSiteVisitId` is falsy → whole block
  skipped. So no SV ever exists → nothing to show. Web list query
  (listConfirmedPaginated + isConfirmedHistoryVisit) is FINE — a confirmed SV
  would appear in Scheduled.
- Fix options (BACKEND, deploy-gated, main chat's MMS domain — coordinate):
  (A) creation-side: createCpVisitRows pre-creates a PENDING linked SV +
      convertedSiteVisitId when cpType=sv_cum_cp (design-aligned; SV shows in
      Fixed/pending from creation, mobile lock works, existing flip confirms it).
  (B) completion self-heal: setOutcome creates the SV if sv_cum_cp + missing
      (also fixes already-created CPs).
  Design Q: auto-created SV's staff (incharge = CP's field staff? or office
  assigns?) + pending-vs-confirmed. Test gap: no test covers the CP→SV flip;
  add a regression test. NOTHING implemented yet — awaiting approach decision.

## Session 139 (main-chat) — investigating #805: SV cum CP completion doesn't create SV
- High-pri issue: completing a CP with cpType=sv_cum_cp from Android → related SV
  not visible in Marketing > Site Visits.
- Trace so far: two sv_cum_cp shapes — (1) telecaller "Fix SV" pre-creates a
  PENDING paired SV (CP.convertedSiteVisitId set); mobile Confirm →
  persistSvCumCpConfirm → setCpVisitOutcome(interested) → backend clientPlaceVisits
  .setOutcome (~line 2677) flips that SV confirmationStatus pending→confirmed → SV
  shows. (2) CP created via the WEB "Create CP Visit" modal with CP Type=SV cum CP
  appears to store cpType only (NO proposedSiteVisit, NO convertedSiteVisitId) →
  mobile lock-signals (proposedSiteVisit/lead sv_fixed/party/convertedSv) all FALSE
  → normal CP outcome "Interested" recorded → backend confirm-block requires
  convertedSiteVisitId (absent) → NO SV ever created. Matches the screenshots
  (completed CP, outcome Interested, no SV).
- convertToSiteVisit (2892) requires a projectId (from proposed or args) + arrival
  proof; the CP row itself carries projectId/assignedStaff/scheduledDate, so it may
  be materialisable from the CP alone.
- Dispatched a subagent to confirm exact web-create storage + whether convert can
  run from the bare CP, then implement minimal fix (backend setOutcome materialise
  vs mobile route). No code changed yet.

### Session 107 (cont.) - #805 FIXED (backend, deploy-gated)

- Fix (option A, creation-side): `clientPlaceVisits.createCpVisitRows` now, when
  `cpType==="sv_cum_cp"` && projectId present, inserts a PENDING linked siteVisits
  row (status "scheduled", confirmationStatus "pending", confirmationRequiredBy
  "cp", origin "client_place_visit", inchargeStaffId = args.assignedStaffId [CP's
  field staff], telecallerId/assignedTelecallerStaffId = leadTelecaller,
  address/coords/attendees/etc. mirroring convertToSiteVisit) and patches the CP
  with convertedSiteVisitId (clientPlaceVisits.ts:2037-2076). No-project → skips
  gracefully. The existing setOutcome "interested" flip (2731+) confirms it on CP
  completion; reject path cancels it. End-to-end: SV shows in Site Visits
  Fixed/pending from creation → Scheduled (confirmed) after mobile CP completion;
  mobile confirm-lock now works (convertedSiteVisitId present).
- Test: new convex/siteVisitCumCpLink.test.ts (2 tests) — createFromMobile
  sv_cum_cp → pending linked SV + convertedSiteVisitId → setOutcome("interested")
  → SV confirmed + in listConfirmedPaginated; + no-project skip. `npx vitest run
  convex/siteVisit convex/travelDeskProof` = 9 files / 34 tests ALL PASS.
- Skipped rollup call (patchSiteVisitStatsForChange non-exported; convertToSiteVisit
  also omits it) — pre-existing byStatus-only limitation, list unaffected.
- DEPLOY-GATED + main-chat MMS domain: uncommitted, NOT deployed (never-deploy,
  prod). Needs a convex deploy to take effect on prod. Only NEW sv_cum_cp CPs get
  the SV; already-created CPs (no link) would need the option-B self-heal.

## Session 140 (main-chat) — FIX #805: SV cum CP completion now materialises the SV
- Root cause (confirmed by trace): web "Create CP Visit" modal stores a sv_cum_cp
  CP with cpType + projectId + assignedStaff but NO proposedSiteVisit / no
  convertedSiteVisitId. Mobile classified sv_cum_cp only from proposed/lead/party
  heuristics — never from the real cpType — so it fell to direct_cp, recorded a
  plain "Interested" outcome, and setOutcome (which only CONFIRMS an existing
  linked SV, never creates one) produced no Site Visit.
- Fix:
  * app HomeViewModel.toTodayVisitOrNull: classify category="sv_cum_cp" when
    cpType=="sv_cum_cp" (authoritative) in addition to the soft signals. This
    makes isSvFixedHint true → the sheet opens on the SITE_VISIT form (paled other
    tabs, seeded from CP) → submit routes to persistSiteVisit → convertToSiteVisit,
    which materialises the SV (confirmed/scheduled, linked) so it shows in Site
    Visits. Mirrors the web's "Convert to Site Visit" behaviour.
  * convex clientPlaceVisits.ts convertToSiteVisit: projectId now falls back to
    visit.projectId (was args.projectId ?? proposed.projectId only) — defense so a
    bare sv_cum_cp CP never throws "projectId required".
- Works against CURRENT prod backend after an APK rebuild (mobile passes projectId
  from the SV form picker; the backend fallback is extra safety, staged for deploy).
- Validation: convex tsc clean; :app:compileDebugKotlin BUILD SUCCESSFUL.

## Session 141 (main-chat) — push #805 fix
- APP (merge): 6704ff32 (HomeViewModel sv_cum_cp classify-by-cpType). Both remotes.
- WEB (max): 98ca4267 (convertToSiteVisit projectId fallback). 523f20fe..98ca4267.

## Session 142 (main-chat) — FIX #80: CP outcome must ask type first, not open Booking form
- Issue: normal CP (esp. Follow-up) completion opened the full Outcome Information
  form with Booking pre-selected + client-details form — field staff land in the
  wrong flow by accident.
- Fix (app, CompleteCpVisitBottomSheet): added `outcomeChosen` (default false).
  renderState now shows renderOutcomeChooser() until an outcome type is picked —
  the 4 top tabs (Booking/Site visit/Postpone/Not Interested) act as the selector,
  none pre-highlighted, all forms + CTA hidden, with a "What happened with the
  client?" prompt (reusing bodyComingSoon's text). Tapping a tab → switchOutcome →
  outcomeChosen=true → the corresponding form opens. Forced modes set
  outcomeChosen=true so they skip the chooser: arg-provided outcome, isSvFixedHint
  (sv_cum_cp lock), applyStandaloneBookingMode, applySiteVisitOutcomeMode,
  applyLockedSvMode. Collection/old-client/gift CPs use their own dedicated prompts
  (never reach this sheet). No layout change.
- Validation: :app:compileDebugKotlin BUILD SUCCESSFUL. App-only; rebuild APK to see it.

## Session 143 (main-chat) — push all changes
- WEB (max): f6e9d541 — my Session-134 SV client-name feature
  (features/marketing/pages/site-visits-list-page.tsx) was still uncommitted;
  committed + pushed. Pre-existing baseline staff implicit-any (1312/1339/1411)
  unchanged, not mine.
- NOT pushed / surfaced to user: convex/siteVisitCumCpLink.test.ts (untracked) — a
  #805 test I did NOT author (uses a different "pre-create pending SV at CP
  creation" approach); left for the concurrent fork/DARX to own.
- APP: nothing to push (only .idea + AGENT_LOG modified, both intentionally
  uncommitted). All prior app fixes already pushed (…80682f87).

## Session 144 (main-chat) — booking form: sections as card containers (web parity)
- Request: separate each booking tab's sections into card containers (Booking
  Information, Source/Referral, Financial Details, KYC, …) like the web.
- styles.xml: added BookingSectionCard + BookingCardTitle (standalone form) and
  OutcomeBodyCard (sheet bodies) — white rounded cards (reuse bg_booking_card).
- Standalone New Booking (fragment_booking_create.xml): rewrote so every section
  (Booking Information / Client Details / Source-Referral / Financial / Charges /
  Customer Funding / Payment Schedule / Original Staff / KYC / References /
  Finalize) is its own white card with a title, on a grey backdrop. All field ids
  preserved.
- Visit->Booking sheet (dialog_cp_visit_complete.xml + 11 outcome_body_*.xml):
  greyed outcomeBodyContainer (#F5F6F8) and applied style=OutcomeBodyCard to each
  body root (find_client, client_form, professional, office, booking, charges,
  payment, staff[KYC], site_visit, postpone, not_interested) so each section
  renders as a distinct card across all tabs.
- Validation: :app:assembleDebug BUILD SUCCESSFUL (all layouts/resources link).
- App-only, cosmetic. NOT pushed (rebuild APK to view). Reminder honored: AGENT_LOG
  updated this turn (standing rule).

## Session 145 (main-chat) - push current MMS and app changes
- Re-audited all three repositories on 2026-08-05. MMS `max` has one untracked
  SV/CP linkage test, Mconnect `merge` has the documented booking/outcome layout
  card changes, and Travel Desk `aizen` is clean.
- `AGENT_LOG.md` and `.idea/deploymentTargetSelector.xml` remain local-only and
  will not be staged. Validation, commit, and push are in progress.
- Validation passed: the MMS `siteVisitCumCpLink` regression suite is 2/2 and
  `git diff --check` is clean. The first Android assemble attempt hit a stale
  Gradle output-cleanup lock; after stopping the daemon, `:app:assembleDebug`
  completed successfully. This was an environment lock, not a resource error.
- Committed and pushed MMS `max` commit
  `d1b04676cf8905e129c245e4456aba1db5c0c24c` (`test(site-visits): cover SV
  cum CP linkage`). The company remote tip matches.
- Committed and pushed Mconnect `merge` commit
  `811bcb1eef4e00fa95bc8ae13935e5c4ada287c2` (`style(bookings): group form
  sections into cards`) to both `manjugroupsdev/Mconnect` and
  `D-A-R-X/Mconnect`; both remote tips match.
- Travel Desk `aizen` was explicitly pushed and remains up to date. MMS and
  Travel Desk worktrees are clean. Mconnect retains only the intentionally
  local `.idea/deploymentTargetSelector.xml` and mandatory `AGENT_LOG.md`.
- Push task completed. No application or Convex deployment was performed.

## Session 145 (main-chat) — highlight booking section names
- Sheet section headers (Financial Details, Charges & Advance, Customer Funding,
  Payment Schedule, CEF Form, Home Address, Office Address) used the plain muted
  OutcomeFieldLabel (12sp #475467) so they read like field labels.
- Added OutcomeSectionTitle style (styles_outcome.xml): bold, uppercase, accent
  blue #0B61CA, 13sp, letter-spacing — a clear section highlight. Applied it to
  all 7 headers across outcome_body_booking_charges/payment/staff/client_form/
  office.xml.
- Validation: :app:assembleDebug BUILD SUCCESSFUL (one transient daemon-stop, clean
  on re-run). App-only cosmetic; rebuild APK to view. NOT pushed. AGENT_LOG updated
  (standing rule).

## Session 146 (main-chat) - GitHub SV/Booking issue document audit
- Started the requested end-to-end review of
  `C:/Users/surya/Downloads/GitHub_Issues_SV_Booking.docx`.
- Re-read the mandatory repository instructions and document-processing workflow,
  confirmed the current MMS/Mconnect/Travel Desk repository baselines, and loaded
  the bundled document runtime. No product files have been edited yet; issue
  extraction and ownership mapping are in progress.
- Extracted all seven reported defects: Immediate-SV rejection visibility, Confirm
  SV IAM enforcement, Immediate-SV edited staff consistency, mobile payment
  schedule arithmetic, mobile CEF front/back uploads, Not Interested visibility
  plus Awaiting cleanup, and Same Area SV Field Staff assignment. Initial ownership
  is MMS/Convex for 1/2/3/6/7 and Mconnect plus shared booking contracts for 4/5.
- Audited the current implementations before editing. Rejection history, Confirm
  SV IAM enforcement, CEF front/back upload wiring, Not Interested persistence,
  and fleet Awaiting cleanup already had production fixes on the current branch.
- MMS: fixed the remaining Same Area form defect by introducing a distinct,
  required Field Staff picker. Site Incharge continues to populate the SV while
  Field Staff now populates the linked CP `assignedStaffId`; the Schedule action
  is disabled until that assignment is present.
- MMS: added `convex/siteVisitManagementIssues.test.ts` covering Immediate-SV
  rejection actor/time/reason visibility, server-side Confirm IAM denial, fresh
  staff-name enrichment after Super Admin edits, and Not Interested completion
  removal from fleet Awaiting. Focused Vitest result: 4/4 passed. A known
  convex-test scheduled IRIS action emitted a transaction-cleanup warning after
  the assertions, but did not fail the suite.
- Mconnect: added standalone booking validation based on `BookingCalc` so Advance,
  Allotment, and scheduled instalments are each deducted exactly once and an
  over-allocated schedule is rejected before submission. Added unit coverage for
  the payable chain and standard schedule; Android validation was pending at
  this intermediate step and is recorded below.
- Final validation: MMS focused SV regression suites passed 10/10 across the new
  issue pack plus history, reassignment, and outcome guards; ESLint passed for
  both changed MMS files; both MMS and Mconnect `git diff --check` passed.
  Mconnect `testDebugUnitTest` and `:app:assembleDebug` both completed
  successfully. Web `tsc --noEmit` no longer reported a source diagnostic after
  repairing duplicated generated `.next/dev/types` fragments, but exceeded the
  120-second validation window; focused ESLint and Vitest are the completed web
  checks. No Travel Desk code was required because none of the seven document
  issues belongs to that portal. No commit, push, or deployment was performed.

## Session 147 (main-chat) - release gate and push SV/booking fixes
- User requested publishing the completed issue-document fixes only after a
  final regression check. Reconfirmed repository scope: MMS `max` contains the
  Same Area Field Staff fix and its regression suite; Mconnect `merge` contains
  booking schedule validation and unit coverage; Travel Desk `aizen` is clean.
- Confirmed that `.idea/deploymentTargetSelector.xml` and this mandatory
  `AGENT_LOG.md` are local-only and will not be staged. Final validation,
  intentional staging, commits, and pushes are in progress.
- Release gate results: MMS focused SV suites passed 10/10 and focused ESLint
  passed. Mconnect `testDebugUnitTest` plus `:app:assembleDebug` completed with
  `BUILD SUCCESSFUL`. Repo-wide MMS `tsc --noEmit --incremental false` reached
  the established unrelated implicit-`any`/unknown-type baseline across many
  legacy modules; neither changed MMS file appeared in its diagnostics.
- Proceeding with scoped commits because the affected paths pass their focused
  checks and the Android app compiles. No Travel Desk commit is needed.
- Committed and pushed MMS `max` commit
  `b6154719` (`fix(site-visits): separate same-area field staff`) to
  `manjugroupsdev/manjusitedevelopment`.
- Committed and pushed Mconnect `merge` commit
  `b862cd3f` (`fix(bookings): validate remaining payment schedule`) to both
  `manjugroupsdev/Mconnect` and `D-A-R-X/Mconnect`.
- The mandatory log and Android Studio deployment target remain local-only.
  Remote-tip verification is the final remaining step; no application or
  Convex deployment was performed.
- Remote verification completed: MMS origin `max` exactly matches
  `b6154719134433384d6c13b3c8ecaec7e3f4d407`; both company and DARX Mconnect
  `merge` exactly match `b862cd3fcda5ff16e8f38eb3e4af8aa9bc152088`.
  MMS and Travel Desk worktrees are clean. Mconnect contains only the two
  intentionally uncommitted local-only files noted above. Push task complete.

## Session 148 (main-chat) - bold booking section hierarchy
- User requested web-parity emphasis for every booking section topic in the
  Android booking forms. Audited the standalone form and CP/SV outcome-sheet
  layouts: standalone headings already share `BookingCardTitle`, but the outcome
  flow is missing explicit headings for Booking Information, Client Details,
  Original Staff, KYC, and References, while Source / Referral still uses a
  normal field-label style. Styling and layout corrections are in progress.
- Updated shared heading styles to enforce bold weight explicitly and use the
  same dark, uppercase 14sp hierarchy as the web booking form. Added missing
  outcome-form headings for Client Details, Professional Details, Office
  Details, Booking Information, Original Staff, KYC, References, and Finalize;
  promoted Source / Referral from a field label to a section heading. Existing
  input IDs, visibility logic, and event wiring were not changed.
- Validation: `:app:assembleDebug` completed successfully and resource linking
  accepted every updated layout/style. `git diff --check` passed. Full
  `:app:lintDebug` reached the existing project baseline (93 errors / 6940
  warnings), beginning with an unrelated `MissingPermission` in
  `ClockInAreaFragment.kt`; no finding was reported against the changed XML.
- This is an uncommitted Android-only presentation change. The local IDE file
  and mandatory log remain outside product scope; no push or deployment was
  requested.

## Session 149 (main-chat) - reusable booking uploads and web parity audit
- User requested a reusable upload input and a deep parity pass between the MMS
  web booking-creation form and both Android booking paths, including conditional
  dropdown-driven fields, calculations, validation, and payloads.
- Initial inspection found six standalone upload rows sharing only a visual
  style while their state handling remains fragment-specific; the CP/SV outcome
  path duplicates five KYC/CEF upload controls plus payment proof. A reusable
  component and a web-to-app rule matrix are being prepared before edits.
- Added `BookingUploadFieldView`, a reusable labeled upload control with one
  visual treatment, selected-file state, and upload progress state. Replaced
  payment proof, Aadhaar front/back, PAN, and CEF front/back controls in both
  Android booking entry paths.
- Audited the active CP/SV/standalone outcome form against the MMS web source
  `features/marketing/pages/booking-new-page.tsx`. Corrected payment category C
  to `C - EMI` and routed exchange, loan, advance, and conversion-credit totals
  through the shared `BookingCalc` engine.
- Aligned schedule validation with the web rules: only needed standard
  installments are required, half-filled rows are rejected, schedules cannot
  exceed the remaining payable amount, and confirmed self-cash schedules must
  exactly cover the outstanding balance.
- Corrected dropdown-dependent behavior discovered by the rule matrix:
  category C now stores/displays `C - EMI`, nationality stores the web union
  value `Foreign`, cheque/DD no longer exposes online payment proof, and a
  booking-type change clears stale conversion/exchange values before rendering
  the newly selected branch.
- Added project `allotmentDueDays`/prefill metadata support and enforced project
  allotment windows, configured project/Flexi schedule windows, and no payment
  or preferred-registration date before the booking date.
- Removed the remaining split creation behavior: inventory unit booking now
  opens `CompleteCpVisitBottomSheet.forStandaloneBooking(...)` with project and
  plot preselected. Bookings list, inventory, CP outcome, and SV outcome now all
  execute the same dropdown logic, calculations, validation, upload component,
  and request builder. The legacy fragment remains compiled for compatibility
  but is no longer referenced by a live navigation path.
- Added focused `BookingCalcTest` coverage for exchange/loan/advance/credit
  deduction order and for omitting unused installment rows.
- Validation completed: `:app:assembleDebug` and `:app:testDebugUnitTest` both
  pass after the final changes; `git diff --check` passes (line-ending warnings
  only). No files were committed or pushed. `.idea/deploymentTargetSelector.xml`
  remains an unrelated local user change and this mandatory log remains
  local-only.

## Session 146 (main-chat) — reusable centre outcome-picker + full-screen form
- Request: replace the in-sheet outcome tabs with a centre-floating dialog (SV-
  outcome-button design, single colour, icons, hides disabled options); selecting
  opens the form full-screen; reusable; no backend/flow/logic change; back-out +
  re-select must work. User chose: present the EXISTING form full-screen (safe).
- Built ui/common/OutcomeSelectionDialog.kt (reusable): centre AlertDialog, one
  filled accent-blue pill per enabled outcome (icon+label, reuse ic_outcome_*),
  86% width, cancelable. + drawable bg_outcome_choice_pill.
- CompleteCpVisitBottomSheet: (1) onCreateDialog now presents FULL-SCREEN (sheet
  layoutParams height=MATCH_PARENT, peekHeight=screen) — page-like, all callers
  get it automatically. (2) maybeShowOutcomePicker() shows the dialog when a type
  still needs choosing; enabledOutcomeOptions() hides Site Visit on pure-SV rows
  (disabled-tab parity); 1 option → auto-select, no dialog; cancel → dismiss sheet
  (re-tap Complete outcome to retry). Forced modes (sv_cum_cp/standalone/locked
  outcome/arg outcome) keep outcomeChosen=true and skip the picker. pure-SV mode
  no longer force-selects Booking (shows the 3-option picker instead) unless a
  specific outcome is locked. applyLockedSvMode + onDismiss tear down the picker
  (no window leak). switchOutcome(existing) does the selection — NO form/submit/
  backend change.
- Validation: :app:compileDebugKotlin BUILD SUCCESSFUL. App-only UI/UX; rebuild
  APK to view. NOT pushed. AGENT_LOG updated (standing rule).

## Session 147 (main-chat) — picker: hide sheet behind it + rounded corners
- Fix 1 (both showing): the full-screen sheet was visible behind the centre
  picker. maybeShowOutcomePicker now sets the sheet's view INVISIBLE + drops its
  scrim (window dimAmount 0) while the picker is up, so ONLY the dialog floats
  over the (dimmed) screen. On select → view VISIBLE + dim restored → full-screen
  form appears. onCancel dismisses the sheet. applyLockedSvMode (async SV lock)
  also restores view visibility so the forced SV form isn't left invisible.
  (Used the Fragment `view` property — this sheet has no `root` field.)
- Fix 2 (rounded corners): OutcomeSelectionDialog container now uses
  bg_outcome_dialog (22dp radius white) + transparent dialog window so only the
  rounded card shows (no square system frame).
- Forced/locked modes (sv_cum_cp/standalone/locked outcome) skip the picker
  entirely — unchanged. No backend/flow/form-logic change.
- Validation: :app:compileDebugKotlin BUILD SUCCESSFUL. App-only; rebuild APK.
  NOT pushed. AGENT_LOG updated (standing rule).

## Session 148 (main-chat) — picker: fix blank-white via alpha-hide + top-of-window
- Bug: "click Complete CP → dialog for a second → blank white." Root cause was
  window layering — maybeShowOutcomePicker ran in onViewCreated (before the sheet
  window was attached) so the AlertDialog ended up BEHIND the full-screen white
  sheet; hiding only the fragment `view` (INVISIBLE) left the design_bottom_sheet
  white container covering everything.
- Fix: moved the picker trigger into onCreateDialog's setOnShowListener (fires
  AFTER the sheet window is fully attached → the AlertDialog is added on top).
  Cache the design_bottom_sheet container (sheetContainerView); when an outcome
  still needs choosing, set that container alpha=0 so the WHOLE sheet (white page
  included) is invisible while the centre picker floats over the dimmed screen.
  On select / single-option / async SV-lock → revealSheet() restores alpha=1 and
  the full-screen form appears. onDismiss clears sheetContainerView (no leak).
- Removed the old view.visibility/ setDimAmount(0f) juggling (replaced by the
  alpha hide). applyLockedSvMode now calls revealSheet(). No form/submit/backend/
  flow/condition change — pure presentation timing fix.
- Validation: :app:compileDebugKotlin BUILD SUCCESSFUL. App-only UI/UX; rebuild
  APK to view. NOT pushed. AGENT_LOG updated (standing rule).

## Session 149 (main-chat) — picker: centre zoom animation + click-safety confirmed
- Animation: the default dialog slide read as a left→right zoom. Added
  res/anim/dialog_zoom_in.xml + dialog_zoom_out.xml (scale 0.85→1.0 from
  pivot 50%/50% + alpha fade) and style DialogZoomAnimation; OutcomeSelectionDialog
  now calls window.setWindowAnimations(...) so the picker pops from the middle.
- Click-safety (user worried a tap finalizes the outcome): confirmed switchOutcome()
  only sets activeOutcome + outcomeChosen and calls renderState() — it just reveals
  that outcome's full-screen form. No submit/save/API. Outcome is finalized only by
  the form's own Confirm/Save; Back arrow returns to re-pick. No logic change.
- Validation: :app:assembleDebug BUILD SUCCESSFUL. App-only UI/UX; APK built.
  NOT pushed. AGENT_LOG updated (standing rule).

## Session 150 (main-chat) — picker: kill sheet-slide glimpse (window anim snapshot)
- Bug: sheet's white page flashed for a frame behind the centre picker on open.
  Root cause: the default BottomSheetDialog window enter animation snapshots the
  window surface (white container, before our alpha-hide lands) and slides that
  snapshot up — so hiding the container afterward can't erase the already-captured
  frame.
- Fix: added style SheetNoWindowAnim (windowEnter/ExitAnimation @null) and set it
  via window.setWindowAnimations(...) in onCreateDialog → no snapshot slide.
  Show listener now sets the container alpha=0 FIRST (before layout/expand) always;
  then outcomeChosen → revealSheet() fades it in (180ms), else the centre picker
  floats and reveals on select. revealSheet() is now an alpha 0→1 animate (was an
  instant flip) for a smooth glitch-free entrance on both paths.
- No form/submit/backend/flow change — presentation-only.
- Validation: :app:assembleDebug BUILD SUCCESSFUL. App-only; APK built. NOT pushed.
  AGENT_LOG updated (standing rule).

## Session 151 (main-chat) — form: hide top tabs (show outcome name) + booking client prefill
- Change 1 (no tabs in the form): once an outcome is picked in the centre dialog
  the 4 top tabs are redundant. renderState() now hides R.id.outcomeTopTabs when
  outcomeChosen; new setOutcomeHeader(outcome) sets the header title/subtitle to
  the chosen name (Booking / Site Visit / Follow up|Postpone / Not Interested).
  Called from switchOutcome (picker path) + applyLockedSvMode (forced SV).
  Standalone booking keeps its own "New Booking" header (never routes through
  switchOutcome). Booking sub-tabs (Client/Booking/Staff) are unchanged.
- Change 2 (booking opens client form prefilled): CP is tied to a known client,
  so the "enter mobile number" step is skipped. Captured cpClientPhone/cpClientName
  in seedSvDefaultsFromCpVisit from visit.client.mobileNumber → lead.mobileNumber →
  clientPlace.contactPhone. switchOutcome(BOOKING) non-standalone: if a phone is
  known → bookingStep=CLIENT_FORM + prefillBookingClientFromCp() (seeds mobile/name
  fields, pre-arms the TextWatcher guard to avoid a double lookup, runs the same
  lead/client auto-fill as the find-mobile Next). Falls back to FIND_MOBILE only
  when no phone yet (manual CP / detail not loaded). Standalone unchanged.
- No backend/persistence/validation change — presentation + prefill routing only.
- Validation: :app:assembleDebug BUILD SUCCESSFUL (only pre-existing deprecation
  warnings). App-only; APK built. NOT pushed. AGENT_LOG updated (standing rule).

## Session 152 (main-chat) — Trip Details: full-screen map expand/collapse
- Added a bottom-right expand button (btnMapExpand, ic_map_expand on white circle
  bg_map_expand_btn) on the trip map preview card in fragment_trip_navigation.xml.
- Added a full-screen overlay (mapFullScreenContainer, elevation 24dp, GONE) as the
  last child of the root ConstraintLayout, with mapFullScreenHost + a top-right
  close X (btnMapCollapse, ic_close on the same white circle).
- TripNavigationFragment: expandMap()/collapseMap() REPARENT the single MapView
  between the preview card and the full-screen host — one map, so all markers,
  route line and camera survive the toggle (no second map, no re-render cost).
  Preview locks pan (isScrollGesturesEnabled=false so page scroll works); full view
  enables pan + zoom controls. renderMapMarkersAndRoute() re-fits camera on toggle.
- System back closes full screen first via an OnBackPressedCallback (enabled only
  while expanded), else normal up-nav. Collapse re-inserts the map at index 0 so the
  loading overlay + expand button stay on top.
- Validation: :app:assembleDebug BUILD SUCCESSFUL. App-only UI; APK built. NOT
  pushed. AGENT_LOG updated (standing rule).

## Session 153 (main-chat) — Trip Details: drop Location tile, add area under name
- Removed the "Location / Current Location" info tile (tvTripOriginName) from the
  right stat column in fragment_trip_navigation.xml; ETA tile lost its now-redundant
  top margin so it aligns as the column's first row. Deleted the tvOriginName field
  + all 4 code references (the id no longer exists).
- Repurposed the name-subtitle line (tvTripStaffRole, previously hidden) to show the
  client's primary area/locality under the name, with an ic_cp_locality pin. New
  primaryAreaFromAddress() parses it from the client address: strips a leading
  "Address:", takes the text before ", City:", else the first comma token
  (e.g. "Medavakkam", "Ashok Nagar"). Hidden when no area derivable.
- No backend/flow change — presentation only.
- Validation: :app:assembleDebug BUILD SUCCESSFUL. App-only; APK built. NOT pushed.
  AGENT_LOG updated (standing rule).

## Session 154 (main-chat) — diagnose "HTTP 500" on CP trip completion (no device/prod access)
- Constraint: staff device inaccessible; prod (api-mfpl) is live, no deploy, no repro.
  adb found at %LOCALAPPDATA%\Android\Sdk\platform-tools; OkHttp BODY logging present
  but earlier 500 had scrolled out; `convex logs` CLI won't auth (cloud deploy key
  ≠ log streaming). Diagnosed from CODE instead.
- ROOT CAUSE (code-level, prod-access-free): marketing CP routes markClientMet/
  setOutcome return {status:500,error} on ANY thrown error (http.ts ~11083/11125),
  unlike geotrack routes (200). setOutcome treats any non-"postponed" outcome as
  status="completed" → runs assertRequiredCpCompletionProof (clientPlaceVisits.ts
  ~2805/591) which THROWS unless fieldVisit has BOTH arrivalVerifiedAt (arrival OTP)
  AND arrivalPhotoStorageId (selfie). Missing → 500. App's catch shows only the
  Retrofit "HTTP 500" (drops the real body message).
- Real message ≈ "Arrival OTP must be verified before completing this CP visit"
  (matches the staff's earlier OTP-max-tries / can't-end-CP report).
- Latent bug: "client not seen" path (TripNavigationFragment completeCpVisitWithoutClient
  ~2653) sends outcome "other" with no OTP/selfie → can NEVER satisfy client-present
  proof → always 500. setOutcome has no clientMet===false exemption.
- Proposed: (A) app-side — surface server error body instead of bare "HTTP 500"
  (safe, no deploy); (B) backend (staged, owner deploys) — skip proof when
  clientMet===false and/or admin override for OTP-stuck CPs.
- No code changed yet (diagnosis turn). AGENT_LOG updated (standing rule).

## Session 155 (main-chat) — FIX CP completion 500: not-met needs photo only, no OTP
- Per user: the client-NOT-met flow is photo-proof-only by long-standing design; the
  arrival-OTP requirement on it is the regression causing HTTP 500.
- App (Mconnect, TripNavigationFragment):
  • completeCpVisitWithoutClient now passes arrivalPhotoStorageId=pendingArrivalStorageId
    to setCpVisitOutcome so the proof photo is attached to the field visit BEFORE the
    completion-proof check (was omitted → proof failed).
  • Added serverErrorMessage(e): parses the non-2xx response body's error/message so a
    future failure shows the real reason instead of a bare "HTTP 500". Wired into the
    not-met catch and finalizeCompleteVisit catch.
- Backend (manjusitedevelopment, STAGED — owner must deploy to prod api-mfpl):
  • convex/marketing/clientPlaceVisits.ts assertRequiredCpCompletionProof: skip the
    arrival-OTP requirement when visit.clientMet === false; still require the photo.
  • convex/hr/fieldVisits.ts completeVisit: same exemption (fetch the clientPlaceVisit,
    skip OTP when clientMet===false); still require the photo. Message generalized to
    "A photo proof of the visit…". Met visits UNCHANGED (still require OTP + photo).
- Why both: not-met path calls markClientMet(false) → setOutcome → completeVisit; both
  setOutcome's proof helper AND completeVisit gate OTP for CP visits, so both needed the
  exemption; the app photo-pass makes the photo present at setOutcome's check time.
- Validation: :app:assembleDebug BUILD SUCCESSFUL; tsc clean on both edited convex files
  (only pre-existing app/** implicit-any baseline remains). NOT pushed; backend NOT
  deployed (never-deploy rule). AGENT_LOG updated (standing rule).

## Session 156 (main-chat) — picker polish: kill sheet flash, center text, better pop-in
- Sheet flash on "Complete CP details": the white came from BOTH the fragment root
  (?attr/colorSurfacePrimary) AND the design_bottom_sheet container's own surface bg;
  a frame slipped through because the sheet was already MATCH_PARENT+EXPANDED when
  alpha-0 landed. Fix: while the picker is pending, keep the sheet at height=0 +
  peekHeight 0 + STATE_COLLAPSED + alpha 0 (nothing on screen). revealSheet() now
  grows it to MATCH_PARENT + STATE_EXPANDED and fades alpha 0→1 only on selection /
  forced-outcome. No white can flash regardless of frame timing.
- Button text centering: OutcomeSelectionDialog buttons were TextViews with a compound
  drawable (pins to the edge, leaves text visually off-centre). Rebuilt each as a
  horizontal LinearLayout(gravity=CENTER) with ImageView + TextView so icon+label
  centre as a group.
- Enter animation: dialog_zoom_in now scales 0.80→1.0 with overshoot_interpolator
  (220ms) + a 150ms fade — a clean centre "pop" instead of the flat scale.
- No logic/flow/backend change — presentation only.
- Validation: :app:assembleDebug BUILD SUCCESSFUL (only pre-existing deprecation warn).
  App-only; APK built. NOT pushed. AGENT_LOG updated (standing rule).

## Session 157 (main-chat) — area-under-name now = Address Line 1 (fix parser)
- User: the client's primary area is the "Address Line 1" field; show THAT under the
  name on CP trips. Session 153's parser was wrong for many addresses.
- Root: CP addresses are labeled/comma-joined (CreateCpVisitBottomSheet:359) —
  "Door/Plot No: .., Street: .., Address: <line1>, Landmark: .., City: .., State: ..,
  Pincode: ..". addressLine1 is the "Address:" segment, NOT the first token; the old
  parser (strip leading "Address:", take before ", City:") returned "Door/Plot No: 92".
- Fix (app-only, no deploy): primaryAreaFromAddress in TripNavigationFragment now
  extracts the value of the "Address:" labeled segment (= addressLine1). Fallback for
  an unlabeled backend-composed address ("doorNo, area, city, ..") skips other labeled
  segments + bare door/plot numbers and takes the first real name. Uses the existing
  placeAddress (TodayVisit has no discrete addressLine1; exposing one would need a
  backend deploy — avoided).
- Validation: :app:assembleDebug BUILD SUCCESSFUL. App-only; APK built. NOT pushed.
  Ships with the still-unpushed Session 153 area line. AGENT_LOG updated (standing rule).

## Session 158 (main-chat) — GitHub_Issues_SV_Booking.docx: 7-issue audit + fix
- Parsed the docx (7 issues: 2 mobile, 5 web). Extracted to scratchpad/issues.txt.
- MOBILE #4 (payment schedule double-subtraction): VERIFIED ALREADY FIXED. Both
  BookingCreateFragment (teammate commit b862cd3f) and CompleteCpVisitBottomSheet
  compute the schedule cap as customerPayable − advance − allotment, EACH SUBTRACTED
  ONCE (BookingCalc.outstandingAfterAllotment / customerBalanceAfterAdvance). Equality
  accepted (> comparison). BookingCalcTest passes.
- MOBILE #5 (CEF Form uploads missing): VERIFIED ALREADY FIXED on BOTH surfaces.
  BookingCreateFragment has Upload CEF Front/Back rows (tvUploadCefFront/Back),
  required-field validation, and payload (cefFormFront/BackDocumentStorageId/FileName);
  CompleteCpVisitBottomSheet has BookingDocumentKind.CEF_FRONT/CEF_BACK wired. Model
  CreateBookingRequest carries all 4 CEF fields.
- WEB #1,#2,#3,#6,#7: launched 2 Explore audits (SV display: #1/#6/#7; IAM+edit:
  #2/#3) to locate exact fix sites before editing. Fixes will be STAGED (never-deploy).
- Validation so far: :app:testDebugUnitTest (BookingCalcTest) BUILD SUCCESSFUL.

## Session 158 (cont) — 7-issue pack: resolution
- #1 SV rejection not visible (WEB): FIXED. Rejected immediate SVs are cancelled
  outOfStationHandoffs (never siteVisits rows) → invisible. Added a "Rejected" pipeline
  tab: lib/site-visits-list-state.ts (union + PIPELINE_TABS + removed the parse redirect),
  site-visits-list-page.tsx (new useQuery listRejectedForViewer, filteredRejectedHandoffs,
  TabsTrigger, render branch → existing FixedHandoffsList variant="rejected"; fixed the
  LIFECYCLE_TAB_STATUSES/LABELS/map-cast types for the new tab). STAGED.
- #2 Confirm SV IAM (WEB): ALREADY FIXED on max — marketing.siteVisits.confirm declared
  (lib/iam-model.ts) + enforced FE (handoff-resolution-dialog, GmHandoffBanner) + BE
  (outOfStationHandoffs.confirm*, dailyTasks task-manager path) + regression test; no role
  template grants it to LMO. No change.
- #3 staff-name mismatch after SA edit (WEB): FIXED. Edit form (site-visit-detail-page.tsx)
  now re-derives HOD from the new incharge's reportingTo and clears AVP/GM/SeniorMgr on
  incharge change; update mutation (convex/marketing/siteVisits.ts) re-seeds the reporting
  chain (chainSeed) when the incharge/telecaller/bdo seed changes instead of falling back to
  the stale stored chain. STAGED.
- #4 payment schedule double-subtraction (MOBILE): ALREADY FIXED (verified, test passes).
- #5 CEF uploads (MOBILE): ALREADY FIXED both surfaces (verified).
- #6 Not Interested not visible / Awaiting (WEB): ALREADY HANDLED — outcome shows in
  Completed tab + detail; getOutcomeLabel already title-cases ("Not Interested"); awaiting
  clears via status=completed; regression test passes. No change.
- #7 Same Area SV Field Staff (WEB): ALREADY FIXED (commit b6154719, cFieldStaffId →
  createClientPlaceVisit.assignedStaffId). No change.
- Validation: web tsc clean on all 4 edited files (only pre-existing repo-wide implicit-any
  baseline remains, none in my hunks). App unchanged this step. WEB fixes STAGED on max,
  NOT deployed (never-deploy rule). AGENT_LOG updated.

## Session 159 (main-chat) — picker: definitively kill sheet flash via window alpha
- Recurring flash: collapse-to-zero-height + view alpha still let ONE frame of the white
  sheet composite before onShowListener ran (the listener always races the first frame).
- Definitive fix: set the whole sheet WINDOW invisible from frame 0 in onCreateDialog —
  window.attributes.alpha = 0f (applied before the window is ever shown). The centre picker
  is a separate window, so it stays fully visible on top. onShowListener now always lays the
  sheet out full-screen (no more collapse hack). revealSheet() fades the WINDOW opacity 0→1
  (ValueAnimator, 200ms) only once an outcome is chosen / forced. No frame can leak the white
  page regardless of timing.
- No logic/flow/backend change — presentation only. sheetContainerView now vestigial (kept).
- Validation: :app:assembleDebug BUILD SUCCESSFUL (only pre-existing deprecation warn).
  App-only; APK built. NOT pushed. AGENT_LOG updated (standing rule).

## Session 160 (main-chat) — attendance route map: rider marker → red location pin
- The scooter marker in the screenshot is the attendance route-PLAYBACK marker
  (ic_travel_marker, the only scooter/bike marker in the app), in AttendanceReviewBottomSheet.
- Changed it to the standard red location drop pin: playbackVehicleIcon now
  BitmapDescriptorFactory.defaultMarker(HUE_RED); both the inline and full-map playback
  markers are now upright pins — anchor (0.5, 1.0) at the tip, flat/rotation removed so the
  pin always points down (was flat+rotated-to-heading like a vehicle). Position still updates
  along the route. Removed the now-unused `heading` var (bearing() still used by the small
  ic_route_arrow direction markers, left untouched). TripNav destination + attendance stop
  markers were already red — unchanged.
- Validation: :app:assembleDebug BUILD SUCCESSFUL. App-only; APK built. NOT pushed.
  AGENT_LOG updated (standing rule).

## Session 161 (main-chat) — outcome/booking forms: pin the action button to the bottom
- The Save/Next button scrolled with the form (floated mid-screen on short forms like
  Postpone, scrolled away on long ones like Booking). Restructured to a fixed footer.
- dialog_cp_visit_complete.xml (all CP outcome forms — Booking/Site Visit/Postpone/Not
  Interested + standalone booking via forStandaloneBooking): wrapped the root NestedScrollView
  in a vertical LinearLayout; scroll is now height=0/weight=1; moved btnCpSubmit + cpLockedFooter
  (Reject/Confirm) + tvCpError OUT of the scroll into a pinned footer (outcomeFooter, elevation
  12dp, error now above the button). All ids preserved.
- fragment_booking_create.xml (standalone New Booking): scroll was already weighted; moved
  btnBookingSaveDraft + btnBookingSubmit into a pinned white footer at the bottom.
- No logic/id/flow change — layout only; findViewById targets unchanged.
- Validation: :app:assembleDebug BUILD SUCCESSFUL. App-only; APK built. NOT pushed.
  AGENT_LOG updated (standing rule).

## Session 162 (main-chat) — CP address: City→District (match Booking) + pincode enrich
- Audit finding: the CP "City" field already holds the India Post District value (pincode
  enrichment + paste-parser map District→that field, Name→locality, State→state); it was just
  mislabeled "City" while the Booking form labels the same value "District".
- WEB (staged): components/unified-address-fields.tsx — relabeled the shared field "City"→
  "District" (placeholder too). Underlying key stays `city` so geocoding/pin (value.city) are
  untouched; no schema change. Affects both CP edit dialog + CP create form.
- APP (push): bottom_sheet_create_cp_visit.xml label "City *"→"District *" (hint e.g. Chennai);
  CreateCpVisitBottomSheet.kt toast "City is required"→"District is required"; ADDED India-Post
  pincode enrichment (was missing) — on 6-digit pincode, PincodeLookup fills District(etCity),
  State, and locality(etAddressLine2) blank-only, matching the Booking form + web. Kept the
  internal compiled visitAddress "City:" label unchanged (internal plumbing; TripNav area parser
  already handles both) to avoid format/parser regression. Pin unaffected (geocode uses field value).
- Validation: :app:assembleDebug BUILD SUCCESSFUL; web tsc clean on the edited file.
- Next: push per standing rules (app→origin merge = both remotes; web→max). Never deploy Convex.

## Session 163 (main-chat) — FIX: location tracking dead after APK update (all users)
- Symptom: after "APP UPDATED", location updates stop for a long gap (only resume when the
  user reopens the app). Web timeline shows the hole.
- Root cause: BootReceiver handled MY_PACKAGE_REPLACED but did the restart on a DETACHED
  coroutine with no goAsync() — after onReceive returns, the freshly-updated COLD process is
  killed before the async bootstrap + GeoTrackService.start() run, so the FGS never (re)starts.
  Also risked missing the broadcast's short FGS-background-start exemption window. The 15-min
  TrackingCheckWorker can't cover it: a WorkManager worker is background and CANNOT start a
  location-type FGS on Android 12+ (no exemption) — only BOOT_COMPLETED/MY_PACKAGE_REPLACED
  receivers have that exemption.
- Fix (BootReceiver): if the persisted session says we were tracking (shouldTrackNow &&
  activeTrackingSessionId), call GeoTrackService.start() SYNCHRONOUSLY inside onReceive (uses
  the exemption window, before process death). Then goAsync() keeps the process alive while
  GeoTrackBootstrapSync.sync() reconciles (stops the service if the server says the shift/visit
  ended — tracking stays clock-in→clock-out bounded). pending.finish() on completion.
- Validation: :app:assembleDebug BUILD SUCCESSFUL. App-only; APK built. NOT pushed yet.
  AGENT_LOG updated (standing rule).

## Session 164 (main-chat) — CP creation + remaining forms: pin action buttons to footer
- CP creation form (bottom_sheet_create_cp_visit.xml): Cancel/Create-visit were inside the
  scroll. Made the sheet full-height (onShowListener: MATCH_PARENT + peekHeight full) and
  moved the button row into a pinned footer.
- "Same for all forms": audited every form sheet's submit-button position. MOST already had
  fixed footers (collection create/payment, create vehicle, loan, salary advance) or are
  short/no-scroll (daily log, driver, issue, allocate vehicle, edit attendance). Only stragglers
  fixed: bottom_sheet_apply_leave, bottom_sheet_apply_permission (both: sheet→full-height +
  footer moved out), and dialog_admin_fleet_complete_offline (already full-height in code; footer
  moved out). sheet_create_fine was a false positive (its ScrollView is a separate overlay, button
  not in a main form scroll) — left as-is.
- No logic/id change — layout + sheet-sizing only; findViewById targets preserved.
- Validation: :app:assembleDebug BUILD SUCCESSFUL. App-only; APK built. NOT pushed.
  AGENT_LOG updated (standing rule).

## Session 165 (main-chat) — booking edit sheet: web-parity DROPDOWNS (phase 1)
- Full web booking-form spec obtained (subagent): 3 tabs, ~10 dropdowns, ~12 conditional flows,
  ~20 calcs, ~30 validations. Documented for follow-up phases.
- Root issue user flagged (Title = text): BookingDetailBottomSheet (the programmatic edit/detail
  sheet, the screenshot) rendered EVERY field as a plain EditText — no dropdowns.
- Phase 1 (this session): added dropdown support to that sheet. FieldSpec gained `options`; a new
  Opt object holds the exact web option lists; renderFields now renders an options field as a
  tap-to-pick pill (non-editable EditText + chevron) that opens SearchableSelectionDialog and
  writes the pick back into `inputs` (save path unchanged). Gated to edit-mode + editable + non-
  Approval tab. Converted 13 fields to dropdowns with exact web options: Title, Nationality,
  Profession, Department, Booking Type, Property Type, Client Source, Is-Against-SV (Yes/No),
  Advance Booking Payment mode, Payment Mode, Payment Plan, Reference Relation 1 & 2, Document
  Prepared In. Also marked mobile/pincode/ref-mobile numeric.
- DEFERRED: customerPaymentCategory dropdown (stored as A/B/C code vs full label — needs a
  label↔code map to avoid save-format mismatch) and ALL conditional flows + calculations +
  validation (large phase; the CREATE forms — CP-outcome flow + standalone — already carry the
  BookingCalc engine + most flows, so the edit sheet is the main gap).
- Validation: :app:assembleDebug BUILD SUCCESSFUL. App-only; APK built. NOT pushed.
  AGENT_LOG updated (standing rule).

## Session 166 (main-chat) — booking edit sheet: flows + calc + validation (phases 2-4)
- Phase 2 (conditional flow + cross-tab integrity): added draftValues map + seedDraftFromBooking
  (seeds every field across all 3 tabs on load) + snapshotInputs (before each re-render) + value()
  now falls back to draft. This (a) fixes a pre-existing bug where saving from one tab null-ed out
  the other tabs' fields (only the active tab lives in `inputs`), (b) preserves edits across tab
  switches, and (c) enables reactive re-render. clientFields converted to buildList; Department now
  shows ONLY when Profession=Salaried (cur()), and picking Profession (flowTriggerKeys) snapshots +
  re-renders so Department appears/disappears live. customerPaymentCategory confirmed NOT in
  UpdateBookingRequest → genuinely display-only in this sheet (kept non-editable).
- Phase 3 (calc): saveChanges balance was agreedAmount − advance (dropped all charges). Now uses
  BookingCalc.grossTotalPayable (+ registration/GST/document/patta/other) then payableChain.balanceAmount.
- Phase 4 (validation): added web-parity gates (each fires only if the field has a value, so partial
  draft edits aren't blocked): mobile/alt/whatsapp/ref-mobile = 10 digits, pincode 6, Aadhaar 12,
  PAN 10; Special Consideration ≤ Booking Cost; Advance ≤ total payable; Allotment ≤ customer balance
  after advance.
- Scope note: the edit sheet's UpdateBookingRequest is a subset of the web form (no loan/exchange/GST
  toggles/mode sub-fields), so it can't be 100% web-parity without extending that API — the CREATE
  flows (CP-outcome + standalone) carry the full BookingCalc + flows.
- Validation: :app:assembleDebug BUILD SUCCESSFUL. App-only; APK built. NOT pushed.
  AGENT_LOG updated (standing rule).

## Session 167 (main-chat) — booking CREATE forms audit fixes
- Audited both create forms vs web spec. Fixed:
- CP F1 [BREAK]: "Discount Approved By" was free-text sending the typed NAME; web expects a staff
  _id. Wired rowChargeDiscountApprovedBy as a staff picker (bookingStaffDiscount), field made
  non-editable, payload now discountApprovedBy = bookingStaffDiscount?.id.
- CP F2 + Standalone #10: Nationality "Foreign" → "Foreign National" (both forms).
- Standalone #1 [BREAK]: EXCHANGE/CONVERSION/INTERNAL EXCHANGE were offered but unimplemented
  (no sub-blocks, no payload, wrong money) → removed from the picker (NEW only); those go via web.
- Standalone #2 [BREAK]: 3rd/4th payment rows were visibility=gone (dates already wired) → revealed
  so multi-installment / cat-A-confirmed schedules can be entered.
- Standalone #5: now sends freePayment = (plan==Flexi) and sourceType="walk_in".
- Standalone #8 [VALID]: added SC reason + SC validity required when SC>0; SV mobile 10-digit;
  source mobile 10-digit (if present); advance ≥ project minimum; advance ≤ total; loan ≤ total;
  confirmed self-cash schedule must total EXACTLY outstanding.
- #9 (draft full-gate): NOT a bug — web runs the same gate for draft (per web spec §6). Left as-is.
- REMAINING minor: #7 Special-plan gating (needs dynamic option list on project change);
  min-allotment lower-bound (cat A) in both. Deferred.
- Validation: :app:assembleDebug BUILD SUCCESSFUL throughout. NOT pushed.

## Session 168 (main-chat) — FIX: logout doesn't free session → can't log in on another device
- Symptom: after logging out on mobile, web staff-login still shows the mobile session active and
  re-login on another device is refused (single-device block stays engaged). "Same for web."
- ROOT CAUSE (mobile): /api/auth/logout (convex/http.ts) did the EXTERNAL Modern Dialer logout
  `await fetch(...)` BEFORE running the session-deactivation mutation. For dialer-mapped agents
  (exactly the staff who have the single-device block), a slow/unreachable dialer call delayed the
  handler; the app's 4s client timeout then fired and the session-deactivating mutation effectively
  never landed → session stayed active → block engaged. Both old and new `logout` mutations
  deactivate mobile sessions, so once the mutation runs the block releases — the problem was ORDER.
- FIX (web, STAGED on max — never deploy): reordered the handler to run
  ctx.runMutation(authFunctions.logout) FIRST (frees the session + releases the block immediately),
  THEN best-effort dialer logout bounded by a 3s AbortController timeout (can't hang the response,
  failures ignored). tsc clean.
- FIX (app, push): LogoutBottomSheet now retries api.logout up to 2× with an 8s timeout each until
  the server confirms success, before clearing local — so a slow logout hop still lands and frees
  the server session even on the current (un-deployed) prod handler.
- Web logout uses the authFunctions.logout mutation directly (use-auth-controller.ts) — already
  frees immediately, no dialer-first hang. No change needed there.
- NOTE: the backend reorder must be DEPLOYED to prod (api-mfpl) to fully fix it; the app retry is a
  belt-and-suspenders that helps on the current prod handler too.
- Validation: :app:assembleDebug BUILD SUCCESSFUL; web tsc clean on http.ts. NOT pushed/deployed.
## Session 169 (main-chat) - external Travel Desk web -> Android parity audit
- Started a full parity audit between the external Travel Desk web portal and the existing
  Android external-agency module. Scope includes authentication/session isolation, agency staff
  permissions, drivers, vehicles, allocation, trip state transitions, evidence capture, billing,
  completed-trip edits, WhatsApp links, settings, and backend request/response contracts.
- Preserving Session 168's unpushed per-device logout fix while auditing; no product files changed
  in this step. Current unrelated `.idea/deploymentTargetSelector.xml` remains untouched.
- Validation/push pending until the parity matrix and implementation gaps are resolved.
- Implemented the first parity tranche across the Android external-agency module: persisted
  per-staff billing permission, agency-staff billing controls/badges, a permission-gated Billing
  trip tab, external final/cancellation billing requests, server driver-link open/copy/resend
  actions, and Travel Desk-specific logout so an agency logout only deactivates that device/session.
- Extended Travel Desk models/API contracts for billing, evidence, status updates, driver access
  links, and cancellation billing. External trips now remain in Billing until the agency finalizes
  their own charges/evidence; internal completion remains isolated from that path.
- Fixed the shared trip sheet caller in Home after adding driver-link callbacks and simplified the
  completed-state predicate to keep external billing and internal completion semantics separate.
- Validation currently in progress; initial build found only the now-fixed callback signature
  mismatch. `.idea/deploymentTargetSelector.xml` remains untouched and will not be committed.
- Added a reachable external-agency trip-status workflow to the Android management sheet. Agency
  operators can report client-unavailable, cancel a trip, or choose a future postponement date and
  optional reason; the app submits the same `/api/travel-desk/trips/status-update` contract used by
  Travel Desk web and refreshes the tab/state after success. Internal MMS/Home trips do not expose
  this agency-only action.
- Validation checkpoint: `:app:assembleDebug :app:testDebugUnitTest` succeeded after the initial
  parity tranche (48 tasks, 11 executed). A second compile is required after the status UI addition.
- Second validation passed: `:app:assembleDebug :app:testDebugUnitTest` completed successfully in
  41s after the status workflow. Full `:app:lintDebug` remains blocked by the repo's existing lint
  backlog (94 errors / 6917 warnings; first error is the unrelated location permission call in
  `ClockInAreaFragment.kt:626`). The only lint error reported in a touched file was corrected by
  changing the staff icon tint to `app:tint`; touched files otherwise had warnings only.
- Final validation after the lint-local correction: `:app:assembleDebug` succeeded in 8s.
- Git: staged only 17 product files under `app/`; explicitly excluded this local-only log and the
  unrelated `.idea/deploymentTargetSelector.xml`. `git diff --cached --check` and a staged secret
  scan were clean. Committed as `f27d2332` (`feat(travel-desk): bring agency workflows to Android`)
  and pushed branch `merge` successfully to both configured Mconnect remotes.
- Cross-repo check: Travel Desk (`aizen`) and MMS web (`max`) worktrees were clean, so no unrelated
  commit was manufactured there. The app consumes their existing deployed Travel Desk contracts;
  no Convex deployment was attempted.

## Session 170 (main-chat) - CP geofence distance audit
- Inspected the Android CP navigation/arrival flow and the MMS Convex arrival-OTP enforcement.
- Authoritative server behavior: `convex/hr/fieldVisitOtp.ts` defaults CP arrival verification to
  `DEFAULT_GEOFENCE_RADIUS_M = 300`; a per-visit `geofenceRadiusMeters` can override it, but no code
  currently writes that field, so existing CP visits effectively use 300 m.
- Found a UI mismatch: Android `TripNavigationFragment.REACHING_RADIUS_METERS` and returned
  `reachingRadiusMeters` list metadata currently use 500 m. This can enable/show arrival at 500 m,
  but the server still refuses OTP until the staff is within 300 m. No files changed in this audit;
  normalization should be handled as a separate requested fix.

## Session 171 (main-chat) - CP/SV Others terminal outcome
- Started tracing the shared CP/SV completion sheet and the standalone SV overview outcome actions
  to add an `Others` option. Required behavior: open a required remarks input, submit through the
  existing backend completion path, and close the CP/SV only after confirmed success.
- Confirmed both existing backend contracts already accept the canonical `other` outcome for CP
  (`clientPlaceVisits.setOutcome`) and SV (`siteVisits.setOutcome`), so no schema or API expansion
  is required.
- Added a reusable required-remarks bottom sheet using the existing remarks layout. The shared CP/SV
  completion flow now exposes `Others`, requires non-empty remarks, submits `outcome=other` through
  the existing terminal persistence path, and only emits completion/dismisses after backend success.
- Added the same `Others` action to the standalone SV overview and included it in every counselling,
  locked, saving, retry, and completed-state button gate. `Postpone SV` and `Cancel visit` remain
  separate workflows and were not changed.
- Hardened the failure path for the compact remarks flow: a rejected/network-failed save reports
  the server error and closes only the invisible modal UI; it does not emit a completion result or
  close the CP/SV record, so the staff can retry without a blocked screen.
- Validation passed twice after the final failure-path correction:
  `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest` completed successfully (48 tasks; final
  run 10 executed / 38 up-to-date). `git diff --check` is clean apart from line-ending warnings.
- Changes are local and uncommitted/unpushed. The unrelated
  `.idea/deploymentTargetSelector.xml` remains untouched.

## Session 172 (main-chat) - named active SV call actions
- Inspected the SV overview contact actions. The client action reads its number correctly, while
  the driver action only reads `proposedSiteVisit.driverPhone`; pure-SV detail envelopes can expose
  driver contact at the root, causing an assigned driver's call action to remain disabled.
- Added nullable root `driverName` / `driverPhone` fields to the enriched visit model and retained
  the nested snapshot as first priority. The overview now resolves either backend response shape.
- Added named call labels (`Call <client name>` / `Call <driver name>`) and refreshes their enabled
  state after enriched data arrives. Assigned drivers with a root or nested phone now render as an
  active call action; genuinely missing numbers remain disabled rather than opening a broken dialer.
- Validation passed: `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest` completed successfully
  in 37s (48 tasks, 20 executed / 28 up-to-date). Changes remain local and unpushed; the unrelated
  `.idea/deploymentTargetSelector.xml` remains untouched.

## Session 173 (main-chat) - web display parity for Others outcomes
- Audited the clean MMS web `max` worktree for CP/SV list, detail, calendar, and export rendering of
  the app's canonical `outcome="other"` payload. Backend contracts already retain app remarks in
  `notes`; no Convex schema or mutation change is required.
- Found presentation gaps: CP list used singular `Other` and CP detail did not identify the outcome;
  SV generic title-casing produced singular `Other`, and its remarks were detached from the outcome
  summary.
- Updated the MMS web presentation in `features/marketing/pages/cp-visits-list-page.tsx`,
  `cp-visit-detail-page.tsx`, `site-visits-list-page.tsx`, and `site-visit-detail-page.tsx`:
  list/filter/export labels now consistently show `Others`; CP detail identifies the terminal outcome
  in both its header and a dedicated Outcome section; CP and SV detail pages show saved `notes` as
  `Remarks` within the `Others` outcome summary and suppress the duplicate generic Notes block.
- Preserved all existing read/write authorization and outcome behavior. CP detail remains read-only,
  and SV mutation/IAM gates were not changed. These web changes are local and uncommitted/unpushed.
- Validation: `git diff --check` is clean apart from line-ending warnings. Targeted ESLint reached all
  four edited files; it reported only the existing `no-explicit-any`/unused-disable debt in
  `site-visit-detail-page.tsx` (none in the new outcome blocks). Repository-wide `tsc --noEmit` remains
  blocked by the project's large pre-existing type-error baseline. The Next dev server started and is
  ready at `http://localhost:3100`; no new server runtime error was logged. In-app browser inspection
  was unavailable because its URL security policy blocked the localhost page, so authenticated visual
  data verification remains a deployment/manual follow-up.

## Session 174 (main-chat) - assigned GM mobile SV confirmation 500
- Traced the LMO-fixed SV handoff from `outOfStationHandoffs` into the selected GM's `dailyTasks`
  record and the Android Task Manager Complete action. The handoff/task assignment was correct, but
  `dailyTasks.updateStatus` applied a second global `marketing.siteVisits.confirm` IAM check after
  already verifying the assigned GM, causing a valid mobile confirmation to fail through HTTP 500
  when the GM designation template did not carry that separate grant.
- Updated MMS Convex authorization so the handoff's authoritative `managerStaffId` can confirm that
  assigned handoff from either the mobile daily-task path or the direct handoff confirm mutation.
  Super-admin/explicit-permission access is preserved, while LMO and unrelated staff remain blocked.
  The web handoff Confirm control now uses the same exact manager ownership rule instead of relying on
  inconsistent designation text.
- Updated `/api/dailyTasks/updateStatus` to return 403 for forbidden attempts and 400 for rejected
  business input rather than exposing those expected failures as generic HTTP 500 responses. Android
  `TaskManagerFragment` now parses the backend JSON `error` body, so any future validation problem is
  shown to the GM as the real actionable reason instead of only `HTTP 4xx/5xx`.
- Added a Convex regression test that creates an LMO handoff, assigns a GM with no standalone confirm
  permission, completes it through `api.dailyTasks.updateStatus`, and verifies the handoff closes and
  a confirmed scheduled SV is created with the selected GM as confirmer.
- Validation passed: targeted Vitest (`convex/outOfStationHandoffs.test.ts` and
  `convex/siteVisitManagementIssues.test.ts`) completed 10/10 tests; focused ESLint passed for the
  three changed Convex/test files; Android `:app:assembleDebug :app:testDebugUnitTest` completed
  successfully (48 tasks). A first mistyped Vitest invocation started the repository-wide suite and
  exposed unrelated existing failures, so it was stopped and replaced by the successful targeted run.
  The full lead-detail page still has its pre-existing lint backlog; no new diagnostic points at this
  ownership change. `git diff --check` is clean apart from line-ending warnings.
- Changes are local and uncommitted/unpushed. Convex must be deployed with the MMS web release before
  the production mobile endpoint receives this server-side fix. Existing unrelated Android changes
  and `.idea/deploymentTargetSelector.xml` remain untouched.

## Session 175 (main-chat) - Aadi prize vehicle spelling
- Traced the screenshot label to MMS contest award data rather than the Android vehicle catalog. The
  current production-derived output still contains the legacy value `Maruto Victorius Lxi`, while the
  seed used the incomplete `Maruti Victoris LXi` wording.
- Updated the canonical MMS seed prize to `Maruti Suzuki Victoris LXI` and added a shared contest-read
  normalization in `convex/salesContests.ts`. Existing persisted awards with either legacy spelling
  now render with the approved name in leaderboard cards, rank results, and award management data
  without requiring each record to be manually edited.
- Added a regression test using the exact persisted typo and verified the leaderboard returns the
  approved label. Targeted Vitest passed all 3 `salesContests.test.ts` tests. ESLint passed for the
  changed contest runtime/test files; including the seed file exposed its pre-existing `prefer-const`
  diagnostic at line 553, unrelated to the one-line prize-label correction. Final targeted
  `git diff --check` passed apart from line-ending warnings.
- Changes are local and uncommitted/unpushed. No Android source was changed for this correction.

## Session 176 (main-chat) - compact gram-gold prize labels
- Updated the MMS Aadi contest prize presentation to show gram-gold rewards in the requested compact
  format: `( 2GM ) ( 1GM )`. Vehicle and other prizes retain their existing numbered rank labels.
- Added backend normalization for legacy persisted values such as `2gm gold coin` and `1gm Gold`, so
  existing contest award records render as `2GM` / `1GM` without manual database edits. Updated the
  canonical seed values for 1GM, 2GM, and 4GM rewards.
- Applied the shared formatter to leaderboard award badges, winner badges, and the Awards table, and
  added regression coverage for legacy gold-label variants.
- Validation passed: targeted `salesContests.test.ts` completed 4/4 tests, targeted ESLint passed for
  the contest backend/test/page, and `git diff --check` passed apart from line-ending warnings.
- Changes are local and uncommitted/unpushed. No Android source was changed in this turn.

## Session 177 (main-chat) - 4GM gold reward correction
- Corrected the Aadi TeleSale Head reward to display exactly `( 4GM ) GOLD` while preserving the
  requested `( 2GM ) ( 1GM )` formatting for the LMO reward pair.
- Extended MMS contest normalization to repair the persisted typo `4gm Cold` (and equivalent 4GM
  gold variants) to canonical `4GM GOLD`, and updated the seed accordingly. The shared prize formatter
  now applies the exact presentation in leaderboard badges, winner badges, and the Awards table.
- Expanded the existing gold-label regression test to cover `4gm Cold -> 4GM GOLD`.
- Validation passed: targeted `salesContests.test.ts` completed 4/4 tests, targeted ESLint passed,
  and `git diff --check` passed apart from line-ending warnings.
- Changes are local and uncommitted/unpushed. No Android source was changed in this turn.

## Session 178 (main-chat) - festive poster prize-label parity
- Traced the remaining screenshot mistakes to `components/festival-poster-dialog.tsx`, which had four
  independent raw prize renderers for the marquee, category qualifier, winner row, and Excel export.
  The normal contest workspace formatter from the previous turns therefore did not protect every
  poster presentation path.
- Added shared frontend prize helpers in `lib/contest-prize-label.ts` and applied them to both the
  contest workspace and festive poster. Legacy `Maruto Victorius Lxi` now displays as
  `Maruti Suzuki Victoris LXI`; `Maruti Baleno Delta` is standardized to
  `Maruti Suzuki Baleno Delta`; and gram-gold rewards retain `( 2GM ) ( 1GM )` / `( 4GM ) GOLD`
  consistently in the poster, winner rows, Awards table, and Excel section labels.
- Extended backend normalization and the canonical seed for Baleno, so persisted and newly seeded
  contest records agree with the frontend. Added a focused frontend utility test covering vehicle,
  gram-gold, typo, and non-gold rank behavior.
- Validation passed: targeted Vitest completed 7/7 tests across the backend and shared frontend
  formatter; targeted ESLint passed for all changed runtime/test files. The poster retains its
  pre-existing effect-rule error and two `<img>` warnings; rerunning with only that unrelated effect
  rule disabled found no new errors. `git diff --check` passed apart from line-ending warnings.
- Changes are local and uncommitted/unpushed. MMS web and Convex deployment are required before the
  live festive poster reflects these corrections. No Android source was changed in this turn.

## Session 179 (main-chat) - publish all pending project changes
- User requested that all pending changes be pushed. Audited all three repositories: MMS web/backend
  has the accumulated GM handoff, Others outcome, and contest-label changes on `max`; Mconnect has
  the accumulated Android CP/SV outcome, contact, and task-error changes on `merge`; standalone
  Travel Desk `aizen` is clean with nothing new to publish.
- Confirmed that local-only `AGENT_LOG.md` and unrelated Android Studio
  `.idea/deploymentTargetSelector.xml` must remain unstaged and unpushed. GitHub CLI is not installed,
  but it is not required for direct authenticated Git pushes to the existing current branches.
- Final validation completed. MMS targeted Vitest passed 17/17 tests. Focused changed-module lint is
  clean; a broad inclusion of the large existing `convex/http.ts` file reproduced its longstanding
  547 `no-explicit-any` errors and 12 warnings, unrelated to this turn's one-line response-status
  change. Android `:app:assembleDebug :app:testDebugUnitTest` passed (48 tasks). Diff checks passed
  apart from line-ending warnings.
- Committed and pushed MMS `max` to `origin/max` as
  `b5a78233fde9037a0f2e8253136de4c57d32b05e` (`fix(marketing): align SV confirmations and contest
  outcomes`). Local HEAD and `origin/max` match.
- Committed and pushed Mconnect `merge` as
  `d806663440c25ce1a4b5fe1743b751bc880c9f6e` (`fix(marketing): complete CP and SV mobile outcomes`).
  The configured `origin` push targets published the same commit to both `manjugroupsdev/Mconnect`
  and `D-A-R-X/Mconnect`; local HEAD and `origin/merge` match.
- Travel Desk `aizen` remained clean and already matched `origin/aizen`, so no empty commit was made.
  Only local-only `AGENT_LOG.md` and unrelated `.idea/deploymentTargetSelector.xml` remain modified in
  Mconnect. Convex changes are pushed but still require the approved Convex deployment to become live.

## Session 180 (main-chat) - SV scheduled tab ignored external fleet progress
- Investigated the Ramya mismatch between MMS Fleet and the Site Visits pipeline. Confirmed that the
  external Travel Desk flow records `travelDeskStartedAt`, `travelDeskOnSiteAt`,
  `travelDeskPickedFromSiteAt`, and `travelDeskEndedAt` while intentionally leaving the persisted SV
  `status` as `scheduled`; Fleet derives its Ongoing state from those timestamps, but SV list,
  calendar, export, and stats filters were still comparing only the stale persisted status.
- Updated MMS Convex site-visit reads to derive the effective pipeline status from Travel Desk
  milestones. Existing dropped trips now leave Scheduled and appear under Returning home as
  `dropped`; counselling remains visible as counselling until the return trip begins. The persisted
  status and Fleet billing/outcome completion rules remain unchanged.
- Applied the same effective-status filtering to paginated rows, calendar rows, export candidates,
  date-filtered stats, and the Site Incharge outcome-pending queue. Status-filtered pagination now
  classifies candidates before slicing, preventing stale Travel Desk rows from producing misleading
  or underfilled pages. Added regression coverage for a legacy raw-`scheduled` trip with a complete
  Travel Desk timeline.
- Validation passed: targeted Vitest completed 15/15 tests across SV history visibility and cab
  lifecycle override coverage; focused ESLint passed with only the file's 11 pre-existing
  `no-explicit-any` diagnostics explicitly disabled for the verification run; `git diff --check`
  passed apart from line-ending warnings. The MMS changes are local and uncommitted/unpushed and
  require Convex deployment before the production Ramya row moves out of Scheduled. No Android or
  standalone Travel Desk source was changed; local-only Android log/IDE changes remain untouched.

## Session 181 (main-chat) - separate SV outcome state from fleet proof/billing
- Clarified the required lifecycle after the initial Ramya fix: transport progress must remove an SV
  from Scheduled, but missing external-fleet KM/images/billing must not be treated as the SV outcome.
  A picked-up trip is Enroute; once it reaches the site (including return/drop timestamps), the SV is
  held Onsite until the Site Incharge records an outcome. After outcome recording, the same stored
  return/drop milestones can move it to Returning home.
- Updated the MMS effective SV status derivation and enriched rows with `outcomePending`. Added a
  visible `Pending Outcome` badge to list and calendar rows and renamed the empty Outcome action from
  `Pending` to `Pending Outcome`. The Site Incharge outcome queue now accepts effective Onsite rows.
  Expanded the regression to verify Picked-up/Enroute and Onsite pending tags before outcome, then
  Dropped/Returning-home behavior after outcome.
- Validation passed: targeted Vitest completed 15/15 tests across SV history visibility and cab
  lifecycle override coverage; focused backend/test/page ESLint passed with the backend file's known
  `no-explicit-any` debt disabled; `git diff --check` passed apart from line-ending warnings. Changes
  remain local and uncommitted/unpushed and require Convex plus MMS web deployment before Ramya's
  production row reflects the corrected Onsite/Pending Outcome state.

## Session 182 (main-chat) - publish corrected SV lifecycle
- User requested all changes be pushed. Audited MMS, Mconnect, and standalone Travel Desk. MMS has
  only the three intended SV backend/test/list-page files; Travel Desk is clean; Mconnect has only
  local-only `AGENT_LOG.md` plus the unrelated Android Studio deployment-target file, neither of
  which will be staged.
- Fetched `origin/max` and found the remote 32 commits ahead. Rebasing with autostash fast-forwarded
  MMS from `b5a78233` to upstream `c04388f8` and reapplied the SV changes cleanly without conflicts.
  Post-upstream validation passed: targeted Vitest completed 15/15 SV tests; focused backend/test/UI
  ESLint passed with only the backend file's known `no-explicit-any` debt disabled; and diff checks
  passed.
- Committed the three MMS files as `6eadf38aefd6f3c223a62896bf384697b814ec17`
  (`fix(site-visits): align fleet progress with outcome state`) and pushed `max` to `origin/max`.
  Verified local HEAD and remote `origin/max` match exactly and the MMS worktree is clean. Standalone
  Travel Desk remains clean. Mconnect retains only local-only `AGENT_LOG.md` and the unrelated IDE
  deployment-target file; neither was committed or pushed. Convex deployment is still required for
  the production SV lifecycle behavior to change.

## Session 183 (main-chat) - investigate SV Confirmed By attribution
- Began tracing the management export report where Direct SV rows frequently show the same staff in
  `LMO` and `Confirmed By`. Confirmed this is not an Excel-only formatting issue: direct SV creation
  currently auto-confirms the visit and stores the logged-in creator (commonly the LMO) in
  `confirmedByStaffId`, while CP-origin and out-of-station routes use separate CP/GM confirmation.
- Found an additional reporting problem in the MMS list/export/WhatsApp views: `Confirmed By` falls
  back from the actual confirmer to the pending confirmation assignee and then the SV creator. This
  can falsely attribute legacy records even when no staff member actually performed confirmation.
- Confirmed the direct-SV workflow copy explicitly documents automatic confirmation; LMOs do not
  have or use a separate self-confirm action for these rows. Added a shared MMS confirmation
  attribution helper so direct/legacy automatic confirmations display `Auto-confirmed` and
  `System`, while CP/GM routes display only the actual `confirmedByStaffId` staff member.
- Replaced the incorrect creator/assigned-verifier fallbacks in the SV table, Excel export, and
  WhatsApp summary. A missing CP/GM approval stamp now remains `—` instead of falsely naming the LMO,
  BDO, or pending verifier. Updated `docs/sv-flow.md` with the reporting rule; no schema, mutation,
  approval permission, Android, or Travel Desk behavior was changed.
- Added five focused unit cases covering direct auto-confirmation, CP confirmation, GM confirmation,
  missing approval attribution, and pending CP separation. Validation passed: Vitest 5/5, focused
  ESLint, TypeScript `tsc --noEmit`, and `git diff --check` (line-ending warnings only). MMS changes
  are local and uncommitted/unpushed; production requires the MMS web deployment after publication.
- User clarified that GM approval is mandatory even for DSV. Re-audited the creation flow and found
  the root workflow defect: `direct_sv` was explicitly allowed to bypass the handoff and was inserted
  immediately as Scheduled, so the previous attribution-only correction was insufficient.
- Changed new DSV creation across the main Site Visits form, External Leads outcome form, and legacy
  dialer form to create a GM approval handoff instead of a `siteVisits` row. Extended the handoff
  schema/task/dialog to support `direct_sv`; GM confirmation now materializes the Scheduled DSV while
  preserving its route and stamps `confirmationRequiredBy: gm` plus the actual GM confirmer.
- Added a backend guard that rejects every direct `siteVisits.create` attempt, preventing omitted or
  older frontend paths from bypassing approval. Existing historical DSVs are not rewritten; their
  reporting remains `Auto-confirmed / System`, while all newly fixed DSVs stay in Fixed/Pending GM
  and cannot reach Scheduled or Fleet before approval.
- Expanded the Convex regression to prove direct creation is rejected, no SV exists before approval,
  and assigned-GM task completion creates the Scheduled DSV with the correct GM attribution. Focused
  Vitest passed 11/11 across handoff and attribution coverage; TypeScript and focused ESLint passed.
  Broader related SV validation passed 21/21 tests across GM handoffs, CP conversion, lifecycle
  visibility, management outcomes, and attribution. The test harness emitted its known asynchronous
  IRIS scheduled-function transaction warning while all assertions passed. Final diff review and
  `git diff --check` passed (line-ending warnings only), and the workflow documentation was aligned.
  All MMS changes remain local and uncommitted/unpushed; Convex and web deployment are both required
  before the approval gate applies in production.

## Session 184 (main-chat) - bind DSV approval to the assigned GM
- Closed the remaining DSV approval ownership gap. Pending DSV handoffs now display
  `Pending approval by <GM name>` in both the Fixed list and resolution dialog, so staff can see the
  exact approver instead of a generic pending state.
- Restricted approval at both backend entry points to the handoff's current `managerStaffId`. Admin,
  broad IAM permission, another GM, and the originally assigned GM after reassignment can no longer
  approve that GM's DSV. The frontend also exposes Confirm only to that exact assigned GM.
- When the GM is changed before approval, saving the handoff transfers the unfinished approval task
  to the new GM and creates an `SV approval assigned` notification; the old GM immediately loses
  approval access. An attempted one-click confirm with an unsaved replacement GM is rejected and
  asks the editor to save the GM change first.
- When the GM is changed after approval, the already-created SV retains its approval and does not
  create another pending handoff or approval request. Updated `docs/sv-flow.md` to document these
  pre-approval and post-approval rules.
- Validation passed: focused Vitest completed 17/17 tests covering the DSV gate, exact-GM ownership,
  task reassignment, notification delivery, old-GM rejection, replacement-GM approval, post-approval
  GM edits, attribution, and dialog behavior. Focused ESLint and `git diff --check` passed (line-ending
  warnings only). Full TypeScript validation remains blocked by unrelated existing repository errors
  outside this change. MMS changes remain local and uncommitted/unpushed; Convex and web deployment
  are required before the rule applies in production. No Android or Travel Desk source was changed.

## Session 185 (main-chat) - restrict SV role pickers and preserve LMO/BDO identities
- Added a shared Sales & Marketing department matcher and applied it to every inspected MMS web SV
  assignment surface: Create SV, GM handoff approval, CP-to-SV conversion, legacy dialer, lead-detail
  fixing, external-lead fixing, and SV edit/reassign. LMO, BDO, Site Incharge, field staff, AVP, GM,
  and Senior Manager now list only active Sales & Marketing staff; HOD intentionally retains all
  active staff as requested.
- Corrected SV enrichment and presentation so LMO and BDO are independent role values. The backend
  now exposes an explicit `lmoStaff`, while detail, list, search, Excel export, and WhatsApp summary
  prefer that LMO value and read BDO only from `bdoStaff`. This removes the previous detail-page bug
  where the BDO label borrowed the telecaller/LMO record.
- Strengthened the GM handoff regression with different people for LMO and BDO and verified approval
  preserves both IDs. Added 11 department-normalization tests. Focused Vitest passed 28/28 across
  department filtering, handoff ownership, direct-SV approval, management outcomes, confirmation
  labels, and the resolution dialog. `git diff --check` passed apart from line-ending notices; focused
  ESLint passed with existing warnings. Full repository TypeScript remains blocked by the known broad
  baseline errors. Changes are local and uncommitted/unpushed; MMS web and Convex deployment are
  required before production reflects them. No Android or Travel Desk source was changed.

## Session 186 (main-chat) - publish pending MMS SV workflow changes
- Inspected both repositories before publishing. The MMS web repository was on `max` and exactly
  synchronized with `origin/max`; the Android repository contained only this local agent log plus an
  unrelated `.idea/deploymentTargetSelector.xml` modification, so neither Android file was staged.
- Staged the complete pending 19-file MMS web/Convex SV change set, including mandatory assigned-GM
  approval for DSV, approval attribution, exact approver ownership/reassignment, Sales & Marketing
  role filtering with the HOD exception, explicit LMO/BDO role display, regression tests, and updated
  SV flow documentation. `git diff --cached --check` passed with line-ending notices only.
- Created commit `18c85416afd52c4f0f8ca2fd95f71cda53abd22a`
  (`fix(site-visits): enforce GM approval and staff roles`) and pushed it successfully to
  `origin/max`. Post-push verification confirmed local `HEAD` and `origin/max` both resolve to that
  commit and the MMS working tree has no remaining source changes. The earlier focused validation
  remains 28/28 passing tests. Production behavior still requires the normal MMS web and Convex
  deployment pipeline; this turn did not deploy services.

## Session 187 (main-chat) - restrict CP Others outcome by category
- Audited the Android CP completion routes and found the shared outcome picker appended `Others`
  unconditionally, while Gift Distribution and Old Client bypassed that picker through dedicated
  completion actions. Added a reusable canonical `cpType` policy allowing `other` only for
  `booking_cp`, `gift_distribution`, and `old_client`.
- Passed the saved CP type through Home, Trip Navigation, CP Visits pending-reopen, and Completed
  Visit detail-reopen paths. The shared picker now hides `Others` for Collection, Follow-up,
  SV-cum-CP, Direct CP, unknown, and legacy untyped CP rows. Pure SV retains its separate existing
  `Others` outcome as previously required.
- Added a compact normal-action-versus-`Others` chooser to the dedicated Gift Distribution and Old
  Client flows. Choosing `Others` requires remarks, records `outcome=other`, marks the client met,
  preserves the available arrival proof, and finalizes only after backend success. Their original
  gift-photo and old-client-remarks completion paths remain unchanged when selected.
- Added `CpOutcomePolicyTest` covering approved, disallowed, blank, and null CP types. The first
  Gradle attempt was blocked only because this shell lacked `JAVA_HOME`; reran with Android Studio's
  bundled JBR. Focused unit test plus `:app:assembleDebug` completed successfully (48 tasks), with
  existing deprecation warnings only. `git diff --check` passed with line-ending notices. Changes
  remain local and uncommitted/unpushed; unrelated `.idea/deploymentTargetSelector.xml` remains
  untouched.

## Session 188 (main-chat) - close legacy SV-cum-CP Others links and enable eligible web flow
- Traced the reported database mismatch to MMS Convex: `sv_cum_cp` creation pre-creates a linked
  Site Visit with pending CP confirmation, but the historical `other` outcome completed only the CP
  and left that linked SV scheduled/pending. No app-only change could repair those persisted rows.
- Added an internal, paginated, re-runnable repair mutation in
  `convex/marketing/clientPlaceVisits.ts`. It scans completed `sv_cum_cp` CP rows whose outcome is
  `other` and cancels only an untouched linked SV that is still confirmation-pending and
  nonterminal. It clears the pending confirmation state, carries the CP remarks into a machine-
  readable closure marker, and cancels related Site Visit daily tasks. Already confirmed,
  progressed, completed, postponed, or independently cancelled SVs are preserved.
- Enforced the current policy at the backend boundary: new `other` CP outcomes are accepted only for
  `booking_cp`, `gift_distribution`, and `old_client`, and nonblank remarks are mandatory. New
  SV-cum-CP, Collection, Follow-up, Direct, unknown, and legacy-untyped CP submissions cannot bypass
  the Android visibility rule through an older client or direct API request.
- Restored a narrowly scoped MMS web action on eligible CP detail pages. Booking CP, Gift
  Distribution CP, and Old Client CP display an `Others` button that opens a required-remarks dialog
  and completes through the existing audited backend action. Other CP types do not render it, while
  historical `other` remarks continue to display in the read-only Outcome section.
- Expanded `convex/siteVisitCumCpLink.test.ts` to cover disallowed new SV-cum-CP Others, allowed
  Booking CP Others with mandatory remarks, one linked-SV invariant, historical repair, and
  preservation of confirmed SVs. Focused Vitest passed 11/11 across linked-SV and completion-proof
  suites; focused frontend/test ESLint and `git diff --check` passed. The test harness still emits
  its known asynchronous push-notification transaction warning while returning success. Full repo
  TypeScript and whole-backend ESLint remain blocked by broad pre-existing errors, with no new
  error reported in the changed backend implementation.
- MMS changes are local and uncommitted/unpushed. Production needs the normal MMS/Convex deployment,
  followed once by
  `npx convex run marketing/clientPlaceVisits:repairOtherOutcomePendingSiteVisits '{"cursor":null}'`
  against the deployed environment. Mconnect source was not changed in this turn; Session 187's
  Android changes remain local, and the unrelated IDE deployment-target file remains untouched.

## Session 189 (main-chat) - publish CP Others policy and legacy repair
- User requested all pending changes be pushed. Audited Mconnect, MMS web, and Travel Desk: Travel
  Desk was clean; Mconnect contained the intended seven Android CP policy/UI/test files plus the
  local-only agent log and unrelated IDE deployment target; MMS contained only the intended three
  web/Convex/test files. Both active branches were exactly synchronized with their remotes before
  committing.
- Revalidated the fetched tips. Mconnect's focused `CpOutcomePolicyTest` plus
  `:app:assembleDebug` passed (48 tasks). MMS focused Vitest passed 11/11 linked-SV and completion-
  proof tests; focused frontend/test ESLint and `git diff --check` passed. The known non-failing
  `convex-test` scheduled push-notification transaction warning remained in stderr.
- Committed the Android changes as `845c0db3` (`fix(marketing): restrict CP others outcomes`) and
  pushed branch `merge` successfully to both configured Mconnect push destinations
  (`manjugroupsdev/Mconnect` and `D-A-R-X/Mconnect`). Committed MMS web/Convex changes as
  `cdab6e70` (`fix(cp-visits): close legacy others outcomes`) and pushed branch `max` successfully to
  `manjugroupsdev/manjusitedevelopment`.
- `AGENT_LOG.md` and `.idea/deploymentTargetSelector.xml` remain local and were not staged or pushed.
  Travel Desk remains unchanged. Production still requires the normal MMS/Convex deployment and the
  one-time production repair command:
  `npx convex run marketing/clientPlaceVisits:repairOtherOutcomePendingSiteVisits '{"cursor":null}' --prod`.

## Session 190 (main-chat) - pull latest MMS max
- User requested `pull max`. Inspected `C:\Users\surya\Projects\manjusitedevelopment`: the MMS
  worktree was clean on branch `max`, with no local divergence and 26 commits behind `origin/max`.
- Ran `git pull --ff-only origin max`, fast-forwarding from `27efd336` to
  `979e2d0bcb8ff30fd443c473f3ca180f1a16110a` (merge of PR #860). The update changed 22 upstream
  files with no conflicts and created no local merge commit.
- Verified local `HEAD` exactly matches `origin/max` at `979e2d0b` and the MMS worktree remains
  clean. No Mconnect or Travel Desk source was changed; this local agent log remains uncommitted.

## Session 169 (main-chat) — FIX: SV cancel crashes (undefined refs in siteVisits.ts)
- Report: travel-desk "Cancel site visit" → "Uncaught ReferenceError: resolveAuditStaffId is not
  defined at cancelSiteVisitCore (convex/marketing/siteVisits.ts:5173)". SV cancellation broken.
- Fix 1: resolveAuditStaffId was never defined (leftover/typo). Replaced with the real resolver —
  cancelledByStaffId = fallbackStaffId ?? (sessionToken ? getStaffFromSessionToken(...)?._id : null).
  The actor-less external travel-desk flow resolves to null (no staff attribution), as intended.
- Fix 2 (found via tsc): deriveCoordsFromMapsLink used at siteVisits.ts:653 but NOT imported
  (defined in ./lib/locationFromMapsLink, imported in clientPlaceVisits.ts). Same class of runtime
  ReferenceError. Added the import.
- Validation: tsc clean on siteVisits.ts (no TS2304/undefined-name; only repo-wide TS7006 baseline).
  Pushed to max (27efd336). MUST be DEPLOYED to prod for the live crash to clear.

## Catch-up — responses that were missing an AGENT_LOG entry
- (web pull) Pulled origin/max into the web repo (fast-forward 8e54f657→aca8f6f6, 12 commits: post-sale
  outstanding rollup, staff pagination, attendance exports, mobile-logout-only-mobile-sessions).
  git-pull only; never-deploy still in force. No app change.
- (push) Session 162 — committed District/CP-address + SV rejection tab + SV staff-chain + CP-completion
  proof fixes: app→both remotes on merge (df085f2a), web→max (e6dc87cc).
- (push) Session 168 "push all changes" — app→both remotes on merge (b0342576: geotrack resume,
  fixed footers, booking parity, reliable logout); web logout reorder→max (b7b63a54).
- (push) Session 169 — SV-cancel crash fix→max (27efd336).
- REMINDER TO SELF (standing rule): append a concise AGENT_LOG entry on EVERY response — fixes,
  audits, pulls, pushes, status updates, and questions alike. AGENT_LOG.md stays local (never committed/pushed).

## Session 170 (main-chat) — WEB Fleet page: filter layout + "This week" crash (investigating)
- Report (mg.theairix.com/marketing/fleet): (1) search + date filters should sit to the RIGHT of the
  Assigned/In-progress/Complete/Cancelled tabs and never shift; (2) clicking "This week" crashes the
  tab and redirects to Dashboard. Locating the Fleet page component + the This-week handler.

## Session 170 (cont) — WEB Fleet page: fixed "This week" redirect + filter layout + H-scroll
- "This week" crash/redirect ROOT CAUSE: useFleetShellController.setQuery({range}) didn't write the
  `tab` param; on a URL without an explicit ?tab, the redirect useEffect (invalid tab → visibleTabs[0]
  = dashboard) then bounced to Dashboard. Fix: setQuery now always writes sp.set("tab", next.tab ??
  activeTab) (+ activeTab dep) so a range change preserves the active tab. Backend rangeValidator
  already accepts "week" — not a validation crash.
- Layout: assigned-tab.tsx filter row used flex-wrap + justify-between → filters wrapped below the
  tabs. Removed flex-wrap (outer: overflow-x-auto; filter group: shrink-0) so search + Today/This week
  + date range stay to the RIGHT of the tabs and don't move.
- H-scrollbar (mid-turn ask): the nowrap row overflowed → visible horizontal scrollbar. Shrunk widths:
  search w-52→w-36, date inputs w-32→w-[112px] each, so the row fits and the scrollbar disappears.
- PRE-EXISTING (not mine): tsc TS2339 at assigned-tab.tsx:230/235 — AssignedFleetVisit lacks
  clientName/mobileNumber/clientPhone (search-filter type mismatch). Non-crashing (search just won't
  match those fields). Flagged, not fixed (needs the correct field names on the visit type).
- Validation: tsc clean on my two edited files (className/deps-only changes add no type errors).
  NOT pushed. AGENT_LOG updated (standing rule).

## Session 170 (cont2) — Fleet "This week shows no data": diagnosed (working) + likely Complete-tab gap
- Traced client→backend: AssignedTab passes range="week", fromDate/toDate=undefined; backend
  resolveAssignedDateWindow → Monday–Sunday window; listAssigned returns rows. The screenshot's
  "In progress: 1" count IS this-week data → the week filter works. User was on the Complete
  sub-tab, which is legitimately 0 (no trips completed in-window); the 1 trip is in-progress.
- LIKELY REAL GAP (unconfirmed): Complete sub-tab windows by scheduledDate (fleet.ts listAssigned,
  ~L525-604) not completion date, so a trip completed THIS WEEK but scheduled earlier won't appear
  under Complete+This week. Fix would window Complete by travelDeskEndedAt. NOT changed yet —
  asked the user to confirm the scenario before an untested backend edit to the working query.
- Also noted: two divergent week helpers exist — resolveDateWindow (today+6) vs
  resolveAssignedDateWindow (Mon–Sun). listAssigned uses Mon–Sun.
- No code change this step. AGENT_LOG updated (standing rule).

## Session 171 (main-chat) — CP pin-drop reverse-geocode uses Nominatim (wrong pincode) — fix to Google
- Report: Drop-a-pin at 13.04446,80.21209 (should be pincode 600083) fills District/pincode from a
  Nominatim (OSM) reverse-geocode response (postcode 600026, "CMWSSB Division 132", "Zone 10
  Kodambakkam"). Wrong. Want the fields filled from the Google geocode of the dropped pin.
- Investigating the reverse-geocode route + PinDropDialog/applyPickedLocation in unified-address-fields.tsx.

## Session 171 (cont) — FIX: CP pin-drop reverse-geocode now uses Google (accurate pincode)
- Root cause: app/api/map/reverse-geocode/route.ts used ONLY OSM Nominatim → wrong Indian
  pincodes (600026 vs the correct 600083). The client (PinDropDialog.reverseGeocode) tries the
  browser Google Geocoder first but it's REQUEST_DENIED (browser Maps JS key not authorized for
  the Geocoding service) → apiAvailability.geocoder flips false → every pin drop falls back to
  this Nominatim route.
- Fix: the route now calls Google Geocoding server-side first (GOOGLE_MAPS_SERVER_KEY, which IS in
  the web .env/.env.local and authorized), maps Google's address_components into the SAME loose
  Nominatim JSON shape the client's reverseGeocodeViaNominatim already parses (display_name +
  address.{house_number, road, neighbourhood, suburb, city, state, state_district, postcode}), so
  NO client change. Falls back to Nominatim on no-key / non-OK / timeout (no regression).
- Result: dropped-pin District/pincode/street now come from Google (600083 correct). Client
  browser-key authorization for Geocoding would also help but isn't required (server route covers it).
- Validation: tsc clean on the route. NOT pushed/deployed. Web change → needs a web deploy; user
  should restart their local dev server to see it locally. AGENT_LOG updated (standing rule).

## Session 172 (main-chat) — CP "client seen = No" → "Others outcome is available only for …" error
- Report: Trip Details, selecting client-seen = No shows "Uncaught Error: Others outcome is
  available only for …". The not-seen path sends outcome="other"; backend setOutcome rejects it.
- Root cause: setOutcome (convex/marketing/clientPlaceVisits.ts:3313) gated outcome="other" to
  CP_TYPES_WITH_OTHER_OUTCOME = {booking_cp, gift_distribution, old_client}. But the app's
  client-not-seen path (TripNavigationFragment.kt:2775) closes a plain Follow-up/Direct CP with
  terminalOutcome="other" + notes="Client not seen" → rejected for those cpTypes.
- Fix (web, staged — needs prod deploy): allow outcome="other" when visit.clientMet===false
  (any cpType), keeping the cpType gate only for the manual "Others" selection (client met).
  markClientMet(false) runs before setOutcome, so visit.clientMet is already false; notes is
  non-empty ("Client not seen") so the remarks check still passes. No app change required.

## Session 173 (main-chat) — CP visit client name not resolved (web shows blank, app shows wrong name)
- Report: CP visit 9941234046 shows LEAD Name blank on web (title/place name = the mobile number),
  but the client PRAKASH exists in Clients with mobile 9941234046. Mobile app shows an unrelated name.
- Investigating how clientPlaceVisits resolves the client display name on web + app.

- Root cause: clientPlaceVisits stores only FKs (leadId/clientId), no denormalized name.
  Shared enrichVisit (web get + app getForMobileId) + listMobileCompact (app list) resolved the
  client ONLY via visit.clientId and NEVER matched the clients table by mobile. So web LEAD Name =
  lead.contactName (blank) → "—"; app card = client.clientName from a null/stale clientId → wrong
  name. The real client (PRAKASH, clients.by_mobileNumberNormalized) was never consulted.
- Fix (web, staged — needs prod deploy): added reconcileDisplayClient(ctx, lead, client) —
  matches clients by phoneLast10 of the visit's mobile; prefers it over a null/mismatched clientId,
  never blanks a correct link. Wired into enrichVisit + listMobileCompact client projection.
  Web detail page (cp-visit-detail-page.tsx): LEAD Name + header now fall back to client.clientName.
  App needs NO change (HomeViewModel already reads client.clientName first). tsc clean.
- Note: web CP-visits LIST page still labels rows from its own projection; reported surfaces
  (web detail + app trip card) are fixed. Effective only after a Convex prod deploy.

## Session 174 (main-chat) — Remove "Others" outcome for Old Client CP
- Request: for Old Client CP the "Others" option is redundant (both old_client_visited and Others
  prompt for remarks), so remove it from Old Client CP.
- Dropped "old_client" from CP_TYPES_WITH_OTHER_OUTCOME in 3 places:
  - web convex/marketing/clientPlaceVisits.ts (setOutcome gate) + error text now "Booking CP and
    Gift Distribution CP".
  - web features/marketing/pages/cp-visit-detail-page.tsx (detail-page Others button gate).
  - app CpOutcomePolicy.kt cpTypeSupportsOtherOutcome() (covers TripNavigationFragment Yes-path
    menu + CompleteCpVisitBottomSheet, both go through this one helper).
- Now only Booking CP + Gift Distribution CP offer Others. Test (booking_cp) unaffected. tsc clean.
- Web parts staged (need prod deploy); app part needs a fresh APK.

## Session 175 (main-chat) — App CP-create form parity with web + HTTP 500 on create
- Report: mobile CP Creation form doesn't match web CP create form (fields/rules/dropdowns/
  conditional flow); and creating a CP on mobile throws HTTP 500 (toast + inline under Address L1).
- Plan: (1) trace the mobile CP-create HTTP route → find the 500 root cause; (2) audit web CP
  create form spec vs app CreateCpVisitBottomSheet and align fields/validation/flow.

- Findings: web calls createFromMobile DIRECTLY with all args; mobile goes via HTTP route
  /api/marketing/clientPlaceVisits/create which DROPPED projectId & lmoStaffId → mobile CPs were
  project-less. The "HTTP 500" toast = createCpVisitRows business errors (assertStaffNotBusy /
  assertNoDuplicateCpVisitSlot / "Collection CP requires booking" / invalid assignedStaffId) which
  the route returns as 500 {error}; the app's catch showed e.message (bare "HTTP 500") not the body.
  Inline "HTTP 500" under Address L1 = opportunistic /api/address/parse (OpenAI splitter) failing.
- Fixes:
  - web convex/http.ts create route: forward projectId + lmoStaffId to createFromMobile (parity).
  - app CreateCpVisitBottomSheet: added serverErrorMessage(e) to surface the real 500 body in the
    create catch; softened the address-parse failure text (no raw "HTTP 500").
  - app: added LMO / Channel Partner / BDO picker (required, parity with web) — new etLmo layout
    field, selectedLmo state, pickLmo/showLmoPicker + isEligibleLmo (mirrors cpVisitLmo.ts:
    active Telesales LMO / Channel Partner / Sales&Marketing BDO), validation gate, sends lmoStaffId;
    CreateCpVisitRequest gained lmoStaffId. Sourced from /api/hr/staff (listPicker returns
    designation/department/status) so the filter populates.
- Compile checks (web tsc + app compileDebugKotlin) running. Web route + LMO forward staged
  (need prod deploy); app changes need a fresh APK.

## Session 176 (main-chat) — CP create: phone-field icon, and name not fetching
- Report: Client Phone Number field has no icon + box looks different from others; name not
  fetching after entering the number.
- Icon: ic_phone_outline.xml used a white stroke (#FFFFFF, transparent fill) → invisible on the
  white input box. Switched the phone field to @drawable/ic_cp_phone (the colored CP icon family
  the other fields use) — fixes the missing icon + the visual mismatch.
- Name not fetching: autofill only read telecallerLeads.contactName. For numbers that exist as a
  client (existing buyer) with no lead — or a lead whose contactName is blank (same class as the
  earlier PRAKASH bug) — the name never filled. Added a clients-master fallback: after the lead
  autofill, call api/clients/search-by-phone (searchClientByPhone → ClientProfile) and blank-fill
  name + address + pin. All app-side; no backend change; needs a fresh APK.

## Session 177 (main-chat) — Client arrival OTP not received (SMS + WhatsApp) — investigation
- Report: some clients not receiving the arrival OTP on EITHER SMS or WhatsApp.
- Tracing the OTP send pipeline (generate → SMS provider → WhatsApp) for edge cases:
  phone normalization/country code, provider fallback, silent failures, rate limits, templates.

- Root cause (both channels fail for SOME clients): messy stored contact phones. SMS
  (bhashSms) stripped a leading 91 only when the result was exactly 10 digits and NEVER stripped a
  leading 0; WhatsApp (normalizeIndianWhatsAppNumber) returned null for anything not 10 / 0+10 /
  91+10. So an 11-digit non-0, 13-digit +91 typo, extension, or two-numbers-in-one-field failed
  BOTH → client got nothing.
- Fix (web convex/lib/otpDelivery.ts, staged — needs prod deploy): added toIndianMobile10() —
  collapse any format to the subscriber's last 10 digits (drops 0/91/+91), require 6-9 prefix;
  SMS gets the clean 10-digit, WhatsApp gets 91+10. Falls back to legacy behavior when not a
  recognizable Indian mobile (no regression). Centralized so login OTP benefits too.
- Secondary risks flagged (NOT code-fixable here, affect ALL not some): SMS DLT template text says
  "signup on AIVIDA" (verify the registered transactional template under sender MNJUGR matches, else
  operator silently drops); bhashSms falls back to hardcoded default user/pass "123456" if
  BHASH_* env vars are unset on prod; clients on DND won't get promotional-classified SMS.

## Session 178 (main-chat) — Mobile Site Visit shows PROJECT name as client name
- Report: SV detail on mobile shows Client = "GS - TMZ 4.0 Phase II" (the project) instead of
  the real client "Ravikumar" (web shows Ravikumar correctly). Phone correct (8610034400).
- Tracing app SV detail client-name resolution + the SV mobile endpoint field it reads.

- Root cause: getForMobileId case 3 (pure-SV synthesis, clientPlaceVisits.ts:2199) set client=null
  and clientPlace.name=project.name. The app's SV client-name chain fell back to clientPlace.name →
  showed the PROJECT ("GS - TMZ 4.0 Phase II") as the client. Web uses siteVisits.get which resolves
  client via sv.clientId ?? clientFromPlace ?? clientByLeadPhone → shows "Ravikumar".
- Fix (web, staged — needs prod deploy): getForMobileId case 3 now resolves the client
  (sv.clientId, else reconcileDisplayClient by lead mobile) and projects it into the envelope
  instead of null. Fix (app, needs APK): SiteVisitOverviewFragment.bindEnriched client-name chain
  no longer falls back to clientPlace.name (project name for pure SVs) — client/lead only, else "—".
  Also corrects the header/Visitors/Call-Client label (all derive from the same displayName).

## Session 179 (main-chat) — Add full-width "Call client" button to CP Trip Details
- Added a full-width "Call Client" button on the CP trip screen (fragment_trip_navigation.xml),
  placed ABOVE the Client Address card (between tripInfoCard and clientAddressCard; address card's
  top constraint re-pointed to it). Styled to match the SV overview call button (bg_completed_
  section_card, blue phone icon + "Call Client" #0B61CA).
- Wired in TripNavigationFragment: binds btnCallClient, dials clientMobile via ACTION_DIAL
  (no CALL_PHONE permission); hidden (GONE) when the trip has no 10-digit mobile so the layout
  collapses cleanly. clientMobile already supplied by HomeFragment.openTripNavigationForVisit
  (visit.leadPhone). App-only; needs a fresh APK.

## Session 180 (main-chat) — CP for new client must require name + create a clients row (like SV)
- Report: a CP was created for a number with NO name and the client never landed in the clients DB.
  Want: when the entered number isn't an existing client, name+number become required and the CP
  details are stored as a new client — same logic SV uses for a new client. Web + mobile.
- Investigating upsertClientByMobile (does CP create a client + with what name) and the SV
  new-client creation path to mirror.

- Root cause: client name was optional on the CP form; upsertClientByMobile defaults clientName to
  the PHONE number when none is given (clients.ts:355) → CP created a nameless (phone-named) client.
- Fix (name required for a NEW client + always store a named client):
  - web convex/marketing/clientPlaceVisits.ts createFromMobile (staged): guard — reject a brand-new
    client (no existing clients-by-mobile row) with no name (typed or lead contactName); and pass the
    lead's contactName as the upsert clientName fallback so lead-linked CPs store a named client.
  - web cp-visits-list-page.tsx: require effective name (lead contactName || typed) before submit;
    Client name field now shows whenever the effective name is blank (covers no-lead AND
    lead-with-blank-name) and is marked required (*).
  - app CreateCpVisitBottomSheet: Client Name now required at submit (autofills from client/lead
    lookup for existing numbers); layout label "Client Name *".
- Backend staged (needs prod deploy); app needs a fresh APK.

## Session 181 (main-chat) — Pushed all changes
- App (Mconnect) → origin `merge` (both remotes: manjugroupsdev + D-A-R-X). Commit fc7a4ee2.
  8 source files (excluded .idea + AGENT_LOG). Call-client button, CP-create parity/500-reveal/LMO
  picker/phone-icon/name-autofill/required-name, Old Client "other" removal, SV client-name fix.
- Web (manjusitedevelopment) → origin `max`. Commit dfe59bba. 8 files. CP+SV client resolution,
  required CP client name, OTP phone normalization, create-route projectId/lmoStaffId, reverse-geocode,
  fleet fixes. STILL NEEDS PROD CONVEX DEPLOY to take effect (never-deploy gate).

## Session 182 (main-chat) — Many users can't complete CP: "Move within 500 m to complete"
- Report: CP completion blocked by a 500m geofence; toast shows huge distances (79.3km, 42.1km).
  Screenshot 1: pin near AP border for a Chennai 600126 address → stored CP coords are wrong/far.
- Investigating the CP completion geofence gate in TripNavigationFragment (reference point,
  radius, what happens when coords are bad).

- Clarified by user: NOT the client pin — the staff HAS reached the CP, but the app's CURRENT
  location shows a wrong (stale) point → geofence reads "79km away" and blocks completion.
- Root cause: fetchCurrentLocation() used getCurrentLocation(PRIORITY) with no max-age → could
  return a cached fused location from where the trip STARTED; and the geofence + OTP paths fell back
  to the even-staler `currentLocation` field when the fetch returned null.
- Fix (app, needs APK): fetchCurrentLocation now uses CurrentLocationRequest with
  maxUpdateAge=10s + duration=20s + HIGH_ACCURACY (rejects the stale cache; GeoTrackService keeps a
  fresh fix ready during the trip). checkReachingAndAskClientSeen + requestArrivalOtpThenOpenCamera
  no longer fall back to the cached currentLocation — a fresh fix is required, else "couldn't get a
  fresh GPS fix, retry" instead of a wrong-distance block. SV overview doesn't use this gate (no
  change). No server change — a fresh accurate fix passes the existing 300m/500m gates at the client.

## Session 183 (main-chat) — Pushed CP completion GPS fix
- App (Mconnect) → origin `merge` (both remotes). Commit 6e898edd. TripNavigationFragment.kt only.
  Fresh-GPS-fix fix for CP completion geofence (no more stale-location "79km away" block).
- No web changes this round (web already pushed at dfe59bba; still awaiting prod Convex deploy).

## Session 184 (main-chat) — SV outcome buttons stay disabled at DROPPED (after QR)
- Report: after the client QR / DROPPED stage, the Outcome options (Converted/Not Interested/
  Follow up) must be OPEN (enabled) until an outcome is given; currently they look disabled.
- Investigating SiteVisitOverviewFragment outcome-enable logic vs SV status.

- Root cause: outcomeStatusEligible was derived ONLY from effStatus (sv.status). For CAB visits the
  fleet return leg advances the stepper to DROPPED via travelDesk* timestamps while sv.status lags
  at an earlier value not in the eligible set → outcome buttons greyed even at DROPPED.
- Fix (app, needs APK): SiteVisitOverviewFragment.bindEnriched — outcomeStatusEligible now also true
  when the client has reached the site per fleet timestamps (travelDeskOnSiteAt / PickedFromSite /
  Ended). Opens the outcome after the client QR (on-site) and stays open through DROPPED until an
  outcome is recorded (isOutcomeLocked still closes it once given). Own-vehicle unchanged.

## Session 185 (main-chat) — Everyone sees all projects in mobile pickers + completed at bottom (CP)
- Request: enable "view all projects" for everyone in mobile; CP picker shows all ongoing first,
  completed at the bottom.
- Chose the SAFE mechanism (NOT the global projects.viewAll grant, which would expose project DATA
  across all features): web convex/http.ts /api/marketing/projects now returns api.projects.list
  (ALL projects) to everyone — same set the web CP picker uses — instead of the per-staff
  linked/scoped list. Lightweight name/scope/status only; global permission untouched.
- app ProjectFilters.ongoingThenCompleted() (ongoing first, completed at bottom); CreateCpVisit
  picker uses it. Other mobile pickers keep ongoingOnly (now unscoped → all ongoing).
- Web route staged (needs prod deploy); app needs APK.

## Session 186 (main-chat) — Staff single-device login block persists after logout
- Report: after logout, staff still can't log in on another phone — web staff login shows
  "already logged in on another device". Session isn't clearing / block doesn't self-heal.
- Investigating getActiveSessionBlockForDeviceType (lib/authSessionLimit.ts), logout mutation
  (deactivateMobileSessionsForStaff), and how the block is enforced at login.

- Decision (user): single-device login = NEW LOGIN TAKES OVER (not block).
- Fix (web, staged — needs prod deploy): added takeOverActiveSessionsForDeviceType() in
  lib/authSessionLimit.ts — on a fresh OTP/password login (ownership already proven), sign out any
  existing active session of that device type for mapped (dialer) agents instead of blocking; marks
  old session loggedOutByLabel="Signed in on another device". Wired into authFunctions.ts (OTP
  login) + passwordAuth.ts (password login), replacing getActiveSessionBlockForDeviceType. Eliminates
  the stuck block after a failed/absent logout (lost/uninstalled/crashed phone). Non-mapped staff
  unchanged (multi-device). getActiveSessionBlockForDeviceType now unused (kept for reference).
- NOTE: this + the earlier logout-reorder fix are staged on max; both need the prod Convex deploy.

## Session 187 (main-chat) — Dialer enabled but call not forwarding via Aster cloud
- Report: newly-pulled dialer feature is enabled but calls don't forward through Aster cloud.
- Investigating the dialer→Aster call-origination path (DialerFragment, ModernDialer*,
  WebViewBridge, ApiService Aster endpoints) to find the break.

## Session 188 (main-chat) — Dialer must use modern-dialer, not Doocti — build missing config route
- Root cause: the app's modern-dialer gate needs GET /api/mobile/dialer/config to return
  configured:true + token + extension. That route was ABSENT from the web backend, so
  getMobileDialerConfig 404'd → config null → the app fell back to Doocti on every call.
- Fix (web convex/http.ts, staged — needs prod deploy): added GET /api/mobile/dialer/config
  (+ OPTIONS). Reads the staff's active asterAgentMapping (getByStaffId) and returns the exact
  MobileDialerConfigResponse shape the app expects — configured:true with mapping.token
  (modernDialerEmbedToken) + mapping.extension (modernDialerExtension), apiUrl from
  telecaller.modernDialerApiUrl setting. So the app takes the modern (WebRTC) path.
- Requires: the staff's asterAgentMapping must carry a valid modernDialerEmbedToken + extension
  (populated by admin/POST). If empty, still configured:false → Doocti. Token must be valid for
  dialer.theairix.com or the softphone won't register (external). Coordinate with teammate so this
  route isn't duplicated.

## Session 189 (main-chat) — Pushed all changes (app + web)
- App (Mconnect) → origin merge (both remotes): bf0b7e18 (SV outcome-open + CP picker ordering +
  merged teammate dialer feature).
- Web (manjusitedevelopment) → origin max: b9b544a3 (modern-dialer config route + single-device
  takeover + all-projects mobile picker). Push initially rejected; pulled origin max (merged
  teammate's tests/legal-docx POC + their otpDelivery WhatsApp messageId validation — auto-merged
  with my toIndianMobile10 normalization, no conflicts), merge commit e1ba07e7, then pushed.
- NOTE (dialer): pushed at user's explicit instruction as "necessary but not verified" — the config
  route is required + type-clean, but end-to-end call success still depends on the staff's
  asterAgentMapping having a valid modernDialerEmbedToken+extension and the external
  dialer.theairix.com softphone registering with Aster. All web changes STILL need the prod deploy.

## Session 190 (main-chat) — Modern dialer: shows calling but no audio, call not forwarding
- Config route deployed → app now on modern-dialer path (not Doocti). But call shows "calling",
  destination never rings, no audio. Classic WebRTC-in-detached-WebView failure.
- Investigating whether ModernDialerWebViewBridge's WebView is ever attached to a window
  (WebRTC media needs an attached WebView) + the service/controller architecture.

- Confirmed: ModernDialerWebViewBridge creates the softphone WebView with applicationContext and
  NEVER attaches it to a window (no addView anywhere; service also uses applicationContext). Android
  WebRTC (mic + audio) requires an attached WebView → detached = no media, no audio, originate never
  completes → destination never rings.
- Fix (app, needs APK + device test): attachToActivityWindow() adds the WebView (1x1, alpha 0) to
  the foreground Activity's decorView on every ensureLoaded, re-attaching if the Activity changed.
  Fixes OUTBOUND calls placed while the dialer screen is open. No-op from the background service
  (no Activity) — incoming/background calls still need the WebView hosted in an overlay-window
  foreground service (SYSTEM_ALERT_WINDOW). May also need: wait for a "registered" event before
  sending call (timing), and a valid embed token (external dialer.theairix.com/Aster). Compiles.

## Session 191 (main-chat) — Adapt working web-dialer logic into app + add ringback tone
- Request: inherit the WORKING web dialer's embed/postMessage logic into the app WebView bridge
  (web dialer forwards calls + audio works); add a ringback ring sound while forwarding/ringing.
- Locating the web dialer embed component (iframe to dialer.theairix.com + postMessage protocol)
  to mirror its exact handshake/command/event contract in ModernDialerWebViewBridge.

- Found (vs working web modern-dialer-provider.tsx): the web QUEUES the outbound call and only sends
  sendDialer("call") once ready && phoneState==="registered" (waits for Aster registration). The app
  sent "call" on page-load, before registration → softphone dropped it → stuck Connecting, no ring.
- Fix (app, needs APK): ModernDialerWebViewBridge now gates command flush on pageLoaded &&
  phoneRegistered — tracks phoneRegistered from the dialer's ready/phone:registered/phone:state
  events (mirrors web phoneState), queues call until registered. onPageFinished no longer flushes.
  AndroidBridge hops to main thread. Plus the earlier WebView window-attach (matches web's attached
  1px iframe with allow="microphone; autoplay").
- Ringback (app): DialerFragment plays ToneGenerator TONE_SUP_RINGTONE on call:ringing-out (agent
  hears it forwarding/ringing), stops on picked-up/answered/ended/error/hangup/reset/destroy.

## Session 192 (main-chat) — SV outcome open on web but still disabled on app at DROPPED
- Report: web "Site incharge outcome" (Convert to Booking/Followup/Not Interested) is OPEN at
  DROPPED; app outcome buttons still greyed. Session 184 fix (fleetReachedSite) may be insufficient
  or isOutcomeLocked is wrongly locking. Inherit the web's exact enable condition.

## Session 193 (main-chat) — Remove "Others" outcome option from SV overview
- Request: SV outcome should not offer "Others" (web SV has only Convert to Booking / Followup /
  Not Interested). Hiding btnOutcomeOther in the SV overview layout.

## Session 194 (main-chat) — SV outcome STILL locked on new APK at DROPPED
- New APK confirmed (Others button gone). Outcome buttons still greyed → isOutcomeLocked is true,
  not eligibility. Hypothesis: CP-linked SV — getForMobileId case 2 leaks the linked CP's outcome
  into visit.outcome, so isOutcomeAlreadyRecorded locks the SV outcome. Web uses siteVisits.get
  (SV's own null outcome) so it's fine.

- Root cause CONFIRMED: CP-linked SV — getForMobileId case 2 leaks the linked CP's outcome into the
  top-level envelope. isOutcomeAlreadyRecorded read visit.outcome (the CP's, always set) → locked.
- Fix (app-only, no deploy): backend already returns proposedSiteVisit: sv (full SV row) — added
  outcome/convertedBookingId/cancelledAt to the app ProposedSiteVisit model (parsed from existing
  JSON), and isOutcomeAlreadyRecorded now uses the SV's OWN fields (proposedSiteVisit) when present,
  never the leaked CP values. SV outcome now unlocks at DROPPED. Needs fresh APK.

## Session 195 (main-chat) — Make Call Client/Driver buttons filled blue + white text (SV + CP)
- Request: SV overview Call Client/Call Driver + CP trip Call Client button → active blue bg,
  white text + white icon (currently outlined card bg with blue #0B61CA text).

## Session 196 (main-chat) — Offline attendance (capture real time, queue, sync) + app offline caching
- Request: whole-app offline access via cache; CRITICAL: attendance clock-in must work offline,
  capturing the REAL punch time on-device and syncing when network returns (staff getting marked
  late due to network lag). Implement local cache without regressions.
- Investigating: current punch flow + timestamp handling (app + backend), existing offline infra
  (GeoTrack Room store-and-forward, LocalCache) to mirror the pattern for attendance.

- Scope: user chose "Offline attendance queue". Implemented Phase 1:
  - web (staged, needs deploy): staffAttendance.punchIn/punchOut accept clientPunchTime (ISO),
    validated (recent, <=2min future, <=24h old) via resolveClientPunchTime; used as punch time +
    date key. http.ts forwards body.clientPunchTime.
  - app: PunchRequest.clientPunchTime; HomeViewModel.punch captures isoNow() at tap, sends it; on
    network failure enqueues PendingPunchEntity (Room, GeoTrackDatabase v4->v5 + migration) with real
    tap time + photo copied to filesDir/punch_queue, shows "Punched ... offline — will sync".
    PunchSyncWorker (WorkManager, CONNECTED constraint) drains queue: upload photo -> punch API with
    clientPunchTime -> delete row (drop on business-rejection to avoid loops, retry on network).
    Enqueued on punch-success + loadHomeData. Online path unchanged except added timestamp.

### Session 107 (cont.) - Back-nav lag fixed app-wide (view retention)

- Complaint: "some back buttons take too long." Audit finding: the back arrows
  are ALL already async (navigateUp() migration complete). Real cause: all 4 tabs
  share one fragmentContainer (add/hide/show), and detail nav used
  replace(fragmentContainer, X) — replace() removes EVERY fragment in the
  container (active tab + 3 hidden tabs), so popping back recreated all of them
  (Home re-inflates every visit card in a loop = the freeze).
- Fix: new FragmentManager.pushDetail(next, allowStateLoss) + Fragment.pushDetail
  in ui/common/NavigateUp.kt — pushes with setReorderingAllowed(true) + hide(current
  container fragment) + add() + addToBackStack, so views underneath are retained
  and pop reveals them instantly (no re-inflation, no reload). Mirrors the existing
  tab hide/show pattern → no new lifecycle model. Built-in double-tap dedupe
  (replaces commitOnce at these sites).
- Converted EVERY back-stack detail push (55 sites / ~27 files) from
  replace(fragmentContainer,X).addToBackStack to pushDetail (agent did 51; I did the
  4 setReorderingAllowed(true) ones in HomeFragment + AgencyDriverTripsFragment).
  All-or-nothing: a stray replace() would tear down the retained stack. Only 2
  replace(fragmentContainer) remain = root swaps (MainActivity 534/549, no
  addToBackStack, savedInstanceState-gated) — correct.
- Build: :app:compileDebugKotlin BUILD SUCCESSFUL (needed one-off
  -Pkotlin.daemon.jvmargs=-Xmx3072m; the daemon OOMs at the default heap on this
  machine — NOT a code issue).
- Behavior note to sanity-check on device: covered screens now stay RESUMED while
  hidden (same as tabs always have). Leaf camera/map screens (SelfieCamera,
  TripNavigation, MapViewer) are popped (destroyed) on back = unaffected. The one
  forward-nav-from-a-camera path is QrScanner→QrHistory: scanner now stays warm
  behind history (instant back, camera not re-init) instead of being torn down.
- Compile-verified only (no Android runtime in this session).

## Session 197 (main-chat) — GeoTrack: verify offline buffer zero-loss + accurate flight-mode/location-off tamper detection
- Report: GeoTrack shows many "HEARTBEAT MISSED" (ambiguous — no-network area vs tampering). Want:
  (1) offline cache buffers ALL location + tamper pings and syncs later so no heartbeats/paths
  missed even in no-network areas; (2) detect FLIGHT MODE and LOCATION OFF as distinct tamper
  signals (accurate) instead of generic heartbeat-missed.
- Mapping GeoTrackService tamper/heartbeat/buffer/sync + tamper event types (app+backend).

- Note: store-and-forward for location+heartbeats+tamper already existed (buffer + replay w/ original
  timestamps). Added the MISSING accurate-tamper signals:
  - app GeoTrackService: heartbeat now carries airplaneMode+locationEnabled (live+buffered);
    isLocationEnabled() master toggle; emitTamperOnStateChange() reports flight-mode/location-off on
    change AND catches missed broadcasts each heartbeat tick + initial state at service start;
    distinct LOCATION_DISABLED/LOCATION_ENABLED. HeartbeatRequest gained airplaneMode/locationEnabled.
  - web (staged, needs deploy): /api/tracking/heartbeat + geotrack.heartbeat.ping accept+store
    airplaneMode/locationEnabled; geoHeartbeats schema + tamperEvents union + tamper.ts validator +
    severity maps gained LOCATION_DISABLED/LOCATION_ENABLED. Store-and-forward untouched.

## Session 198 (main-chat) — Dialer STILL no audio/no call after attach+register-gate+ringback
- App-side fixes (attach/register-gate/ringback/timeout) didn't resolve it → break is in the
  softphone↔Aster layer (dialer.theairix.com embed / SIP registration / WebRTC), which is external
  and untestable from here. Added instrumentation to pinpoint: WebView console→logcat
  (onConsoleMessage), log every command sent + event received + page load + embed URL, and a
  fallback flush (send queued call after 6s if registration never confirms, so it's not stuck).
  Capture: `adb logcat -s ModernDialer` during a call attempt.

## Session 199 (main-chat) — Land Procurement mobile Inspection: land-location map + competitors (add/preview map)
- Request: mobile inspection should show the land location on a map (web parity, land has lat/lng);
  and allow ADDING competitors + PREVIEWING competitor locations on a map (web has promoter/project/
  location/google-map-link/map-view/adjust-pin/extent/approval/stage/amenities).
- Mapping mobile Land Procurement inspection screens + property/competitor data model/API.

## Session 200 (main-chat) — Land inspection: map view/adjust for land + competitors (user chose tap-to-open)
- Mobile inspection form already captured land + competitor locations (Google Map Link -> latLong)
  and add-competitor already worked. Added the missing MAP UI:
  - "View on map / Adjust pin" button on the land location (btnLandViewMap) + each competitor card
    (btnCompetitorViewMap). Opens the reusable full-screen MapPinDropBottomSheet centered on the
    parsed coords (view + search + drop/adjust); dropped pin writes "lat,lng" back to the field.
  - parseLatLng() handles "lat,lng" and Google Maps URLs (@lat,lng / ?q= / !3d!4d). openLocationMap()
    helper. No fragile in-form MapView. App-only; needs APK.

## Session 201 (main-chat) — SV cum CP completion: outcome options not shown in dialog
- Report: completing an SV cum CP should show the SV outcome options (Converted/Not Interested/
  Follow up...) in a dialog; the options aren't appearing.
- Tracing the outcome-options build for sv_cum_cp in CompleteCpVisitBottomSheet / TripNavigationFragment.

## Session 201 fix — SV cum CP outcome dialog now shows
- Root cause: sv_cum_cp opens CompleteCpVisitBottomSheet via newInstance with isSvFixedHint=true
  (visitCategory=="sv_cum_cp"). The hint synchronously set outcomeChosen=true + locked to Site Visit
  to avoid flicker. detectAndApplyLockedSvMode's "no SV-fix signal -> normal mode" branch just
  return@launch'd WITHOUT reverting the hint -> picker suppressed (maybeShowOutcomePicker returns
  when outcomeChosen). So an sv_cum_cp with no pre-fixed SV stayed locked with NO options.
- Fix (app, needs APK): normal-mode branch now reverts the hint (outcomeChosen=false, restore
  tabs) and calls maybeShowOutcomePicker(). Also enabledOutcomeOptions treats sv_cum_cp as SV-style
  (Booking / Follow up / Not Interested / Others; hides "Site Visit"), matching the expected SV outcome.

## Session 202 (main-chat) — Pushed all my feature work
- App (Mconnect) -> origin merge (both remotes). Commit d17351f0. 14 files: offline attendance
  (Room queue + PunchSyncWorker + clientPunchTime), GeoTrack flight-mode/location tamper signals,
  dialer diagnostics, land-inspection map view/adjust, SV-cum-CP outcome-dialog fix.
- Web (manjusitedevelopment) -> origin max. Commit f63094c8. 6 files: punch clientPunchTime,
  heartbeat airplaneMode/locationEnabled + LOCATION_DISABLED tamper.
- LEFT UNPUSHED (not mine): many pre-existing local changes in the app tree from before this
  conversation (MainActivity, ui/chat/*, ui/hr/*, Loans/Tasks/Profile/etc.) — real content changes
  I never touched; flagged to the user rather than blindly pushed. Web still needs prod deploy.

## Session 203 (main-chat) — Dialer: restructure to iframe-host (mirror working web) — connect-timeout confirmed live
- New APK confirmed (connect-timeout message showed). Register-gate never flushed + timeout fired =>
  softphone never reported registration/progress. Strongest remaining hypothesis: the app loaded the
  embed TOP-LEVEL and self-posted; the embed is built to talk to a PARENT window.
- Fix (app, needs APK): ModernDialerWebViewBridge now loads a HOST page (origin mg.theairix.com via
  loadDataWithBaseURL) that embeds the dialer in an IFRAME (allow="microphone; autoplay") and relays
  postMessages both ways (window.__mdSend -> iframe.contentWindow; iframe 'modern-dialer' events ->
  native) — EXACTLY like web modern-dialer-provider.tsx. evaluateCommand posts via __mdSend; page-load
  no longer injects the self-post bridge. If this still fails it's definitively the softphone<->Aster
  registration side (external).

## Session 204 (main-chat) — Dialer audio + config reliability; CP-completion timeout root cause
- Device logcat (OPPO 7ceb9213) after iframe-host fix: iframe loaded, events flowed, call command reached
  softphone, but WebRTC failed "Could not start audio source" / "Unable to select communication device".
  Root: app never set VoIP audio mode.
  App fix (DialerFragment.kt + AndroidManifest.xml): added MODIFY_AUDIO_SETTINGS perm; startCallAudio()
  sets AudioManager.MODE_IN_COMMUNICATION + requests audio focus (USAGE_VOICE_COMMUNICATION) on call
  placement; stopCallAudio() resets on resetCallState/onDestroyView. Rebuilt+installed. Green in-call bar
  confirmed a call connected on retest.
- 2nd test hit "Network error: HTTP 404" + station showed a phone number: dialerConfig was NULL at call
  time (config GET failed under 40-60s backend latency), so app fell back to the DEAD legacy Doocti
  endpoint (mms.aivida.in/api/doocti-call -> 404). Endpoint itself is healthy (curl: configured=true,
  ext 1030, valid token). App fix (DialerFragment.kt): fetchDialerConfigWithRetry() (3 tries), onCall
  now fetches config inline if null before routing, routeCall() shows "couldn't reach dialer service"
  instead of dialing dead Doocti when config unreachable. Rebuilt+installed.
- Measured prod api-mfpl latency directly: iam/my-permissions 41.7s, hr/attendance/my 61.1s (trivial
  queries, cfEdge 6ms, cfOrigin ~27-61s) => WHOLE Convex prod backend degraded; app's 30s OkHttp timeout
  => "Network error: timeout" everywhere. Needs Convex dashboard/ops (NOT an app bug).
- CP-completion-SPECIFIC root cause (user clarified screenshot is CP, not dialer): "Swipe to Complete Trip"
  -> geoApi.requestArrivalOtp -> requestArrivalOtp action -> sendOtpSmsAndWhatsApp (parallel) + optional
  geocodeAddress. sendBhashSms (bhashSms.ts:61) and sendAirixWhatsAppTemplate (airixWhatsApp.ts:166) and
  geocode (trackingMaps.ts:297) all did BARE `await fetch` with NO timeout. A hung provider -> action hangs
  -> app 30s timeout -> CP completion fails. Structural, recurs whenever a gateway stalls.
  WEB FIX (max branch, convex/): new convex/lib/fetchWithTimeout.ts (AbortController, default 10s);
  applied to sendBhashSms, sendAirixWhatsAppMessage, and geocodeAddress fetch. tsc: no new errors (only
  pre-existing app/ baseline). NEEDS CONVEX DEPLOY to take effect (never-deploy rule — flagged to user).
- Dialer call outcome (device logcat): modern path now fully works — mic granted, AudioFlinger thread
  ready (audio fix effective), softphone replied. Call rejected with call:error "This agent is already
  open on another device." (ext 1030 registered elsewhere — web dialer/other session). Not an app bug;
  user must free the Aster agent seat.
- "App crashing after making call" = ANR, NOT a crash. OPPO Quality watchdog dumped main thread
  TIMED_WAITING in runBlocking at GeoTrackService.onDestroy(GeoTrackService.kt:344). Root: onDestroy ran
  runBlocking(Dispatchers.IO){ syncPoints(); syncEvents() } ON THE MAIN THREAD; ending a call tears down
  GeoTrackService, the 40-60s backend latency stalled the sync past the ANR window -> system killed app.
  APP FIX (GeoTrackService.kt): removed the main-thread runBlocking final sync; now unconditionally
  GeoTrackFlushWorker.enqueue(applicationContext) — the existing durable, network-constrained, off-thread
  worker drains the SAME point+event buffers with retry/backoff (no data loss). Compiles; APK installed
  to 7ceb9213. NOT yet pushed to merge.
- "Old UI still used?" audit: screenshot 1 (Chandra Mouli SV-cum-CP, inline outcome-tab find-client form)
  vs screenshot 2 (Murali Follow-up, floating "What happened with the client?" OutcomeSelectionDialog).
  Findings: CompleteCpVisitBottomSheet IS the current sheet used from 7 entry points; OutcomeSelectionDialog
  (new picker) IS wired; ONLY genuinely-dead old UI = BookingCreateFragment.kt + fragment_booking_create.xml
  (0 launch sites). Root of "form instead of dialog": sheet skips the chooser when opened with a pre-set
  STANDARD outcome (cpOutcome = visit.cpVisit?.outcome, via outcomeFromArg -> outcomeChosen=true). A
  stray/leaked outcome on a still-PENDING row therefore bypassed the picker. User chose "Harden picker".
  APP FIX (CompleteCpVisitBottomSheet.kt): new flag outcomeArgPreselected (set only for standard outcomes);
  new visitAlreadyDecided(visit) (status/fieldVisit completed|cancelled|completedAt); in
  detectAndApplyLockedSvMode normal-mode branch, if outcomeArgPreselected && !visitAlreadyDecided ->
  outcomeChosen=false + maybeShowOutcomePicker() (mirrors the existing isSvFixedHint revert). Genuine
  completed re-opens keep their recorded-outcome form; locked pre-fixed SV path untouched; pure-SV/standalone
  unaffected. Compiles; APK installed to 7ceb9213. NOT pushed. BookingCreateFragment left in place (user
  did not opt to delete).

## Session 205 (main-chat) — validate + push all
- Validation: :app:assembleDebug GREEN; :app:testDebugUnitTest initially FAILED on
  CpOutcomePolicyTest ("others available only for approved CP categories") — STALE test asserted
  Old_Client supports "Others", but policy was intentionally narrowed to {booking_cp, gift_distribution}
  (web parity, earlier this session). Updated the test (Old_Client -> assertFalse). Re-run: GREEN.
- WEB (max): committed OTP fetch-timeout fix (4 files) 5a293a4e, pushed origin/max (0 behind, ff).
  STILL NEEDS A CONVEX DEPLOY to take effect (never-deploy rule).
- APP (merge): committed c6cc7def (all app/src: dialer audio+config-retry, GeoTrack onDestroy ANR fix,
  CP/SV outcome picker hardening, CpOutcomePolicy test fix, + pre-existing in-progress UI work across
  chat/hr/tasks/library/profile). Pushed to origin/merge which fans out to BOTH manjugroupsdev + D-A-R-X;
  darx/merge already up-to-date. Excluded .idea/, AGENT_LOG.md, gradle/ (toolchain bumps), .kotlin/ per
  standing rules.
- OUTSTANDING (not code-fixable by push): prod api-mfpl backend latency 40-60s on trivial queries =>
  timeouts app-wide (ops/Convex dashboard). Dialer "agent already open on another device" = free the Aster
  seat (close web dialer). Device-side verification of picker-hardening + ANR fix still pending (user lacks
  device now).

## Session 206 (main-chat) — complete-anywhere (no geofence) + Comp Off badge
- Issue A: web/app showed different coordinates -> "Move within Nm to complete/verify arrival" blocked
  staff who WERE with the client. Fix = remove the visit-completion geofence (client arrival OTP is the
  real proof of presence). APP: TripNavigationFragment.checkReachingAndAskClientSeen — dropped the
  distance > REACHING_RADIUS_METERS block (still captures a fresh fix for the record). WEB (max):
  convex/hr/fieldVisitOtp.ts requestArrivalOtp — removed the `if (!devBypass && distance > radius) return`
  block; distance/radius still computed + returned for audit/travel-allowance. verifyArrivalOtp only
  records distance (no block), untouched. Attendance/home clock-in geofence + travel-allowance rules NOT
  touched ("others work as before").
- Issue B: attendance "comp-off" showed Absent on mobile. Root: backend AttendanceApproval includes
  "comp-off" but the app status mappers didn't -> fell through to punch-derivation -> no clock-in ->
  Absent. APP: added comp-off cases (label "Comp Off", neutral like Week Off) to AttendanceStatusBadge
  (My Attendance log + HR dashboard strip) and AttendanceHistoryFragment.applyAttendanceStatus (HR list),
  and excluded comp-off from the daysPresent count.
- Validation: :app compileDebugKotlin GREEN, testDebugUnitTest GREEN, assembleDebug GREEN (device not
  connected -> no adb install this time). web tsc: no fieldVisitOtp errors.
- Pushed: WEB commit 62c5f64c to origin/max (rebased onto teammate's +24 commits; my earlier
  fetchWithTimeout preserved, merged cleanly with their airixWhatsApp apiKey additions). APP commit
  73051f21 to origin/merge -> both manjugroupsdev + D-A-R-X.
- DEPLOY REQUIRED: the geofence removal in fieldVisitOtp.ts needs a Convex deploy to take effect (app-side
  gate is live in the new APK, but the server still blocks until deployed). Never-deploy rule -> flagged.

## Session 207 (main-chat) — sv_cum_cp outcome sheet reveal-then-picker glitch
- Symptom: completing sv_cum_cp flashed the "What happened with the client?" picker, closed it, and
  auto-opened a CP filling form; staff couldn't pick Follow up / Not Interested.
- Root cause (CompleteCpVisitBottomSheet): for isSvFixedHint the sheet synchronously set outcomeChosen=true
  (+ activeOutcome=SITE_VISIT + faded tabs) to dodge a first-paint flicker. The onCreateDialog show-listener
  then `revealSheet()`-ed a form (outcomeChosen true), while async detectAndApplyLockedSvMode reverted to
  maybeShowOutcomePicker — two paths racing the same sheet.
- Fix (onViewCreated): compute isSvFixedHint first; for sv_cum_cp DON'T pre-commit an outcome (removed the
  synchronous outcomeChosen=true + Site Visit + tab-fade) AND suppress the ARG_CP_OUTCOME preselect. Now
  outcomeChosen stays false -> show-listener shows the picker over the hidden (alpha 0) sheet (no form
  paints). detect's applyLockedSvMode (which ALREADY dismisses the picker at line 6733) is the sole path
  that swaps to the locked SV Reject/Confirm form, and only for a genuine telecaller-fixed SV. Trade-off:
  a genuine locked SV now shows a brief picker flash before locking (minor; acceptable under normal
  latency). detect's now-dead hint-revert branch left as a harmless safety net.
- Validation: compileDebugKotlin + testDebugUnitTest + assembleDebug GREEN. Device NOT connected -> not
  installed/verified on device. Pushed app commit 6e323c56 to origin/merge (both remotes). Device
  verification of the picker-stays-put behavior still pending.

### Session 107 (cont.) - Trip Details header under status bar (fixed)

- Bug: back button/title on "Trip Details" sat under the status bar. Cause:
  edge-to-edge shell keeps fragmentContainer top padding 0 (MainActivity only
  paints a status-bar colour strip, doesn't offset content), and the trip topBar
  had just paddingTop=14dp.
- Fix: new BottomActionInsets.applyStatusBarTop(header) adds statusBars().top on
  top of the header's XML paddingTop (idempotent). Wired into TripNavigationFragment
  (topBar) and DriverTripDetailFragment (added detailHeaderBar id in
  fragment_driver_trip_detail.xml). AgencyDriverTripDetailFragment already did this
  (untouched).
- :app:compileDebugKotlin BUILD SUCCESSFUL. Compile-verified only (no runtime).
  Not committed.

## Session 208 (main-chat) — Out-of-geofence CP completion → GM approval (backend done)
- SCOPE (after user corrections): OTP MANDATORY (no "Request completion" / no-OTP path). Geofence no longer
  hard-blocks; an OTP-verified completion that is OUT of the client geofence → status pending_gm_approval.
  GM approve → completed; GM reject+remark → reopen SAME visit for SAME staff (remark shown on reopen + on
  eventual completion). Reassigned/pending don't inflate per-client CP count (reopen = same row, so
  patchCpVisitStatsForChange totalVisits unchanged — no code needed).
- BLOCKER discovered: web `clientPlaceVisits` Doc/`api` type is at TypeScript's HARD ceiling — even +1
  status literal cascades ~1500 implicit-any errors (api degrades to any) into features/*.tsx + *.test.ts.
  Verified deploy scripts (deploy.sh/dev/new) build with NEXT_IGNORE_BUILD_CHECKS=true and convex uses tsgo
  (convex/ non-test only) → the cascade is COSMETIC for their pipeline; my convex/ files are locally clean.
  Designed setOutcome to add NO new mutation args (derives out-of-geofence from stored
  fieldVisit.arrivalDistanceFromPlaceMeters) to minimize api impact regardless.
- BACKEND DONE (web max, staged; NEEDS CONVEX DEPLOY): schema.ts clientPlaceVisits +pending_gm_approval
  status + completionApproval nested object (lat/lng/distance/outOfGeofence/gmStaffId/requestedAt/
  rejectRemark/reassignedFromRejection). clientPlaceVisits.ts: statusValidator +literal; setOutcome
  body-only branch (out-of-geofence → pending, resolve GM via resolveHandoffManagerStaffId [now exported
  from outOfStationHandoffs.ts], store approval, notify GM; else completed via extracted
  applyCpCompletionEffects); approveCpCompletion + rejectCpCompletion mutations (assigned-GM gate);
  listPendingCpCompletionApprovals query; notifyCpApprovalRequested/Decision helpers. http.ts: GET
  /api/marketing/cp-visits/pending-approvals + POST approve + POST reject (+ public-paths registered).
  tsc: NO errors in my convex/ files (verified).
- APP DONE: CpVisitsFragment status pill — pending_gm_approval → amber "Pending Approval" / "Awaiting GM"
  (tap=read-only detail). compileDebugKotlin GREEN.
- REMAINING (not started): app GM approve/reject queue UI + GeoTrackApi endpoints/models + push routing;
  web approvals surface (approve/reject on web). Nothing pushed. Backend non-functional end-to-end until a
  GM approve UI exists somewhere + a Convex deploy.
- USER DECISIONS: GM surface = APP queue (not web); pending tag shows the approver GM NAME to the staff;
  hold push until GM surface done (now done).
- APP GM SURFACE DONE: new CpApprovalQueueBottomSheet (lists this GM's pending out-of-geofence completions —
  client, staff, place + distance, outcome, photo — with Approve / Reject-with-remark). GeoTrackApi:
  getPendingCpApprovals + approveCpCompletion + rejectCpCompletion + models (CpApprovalItem etc.).
  MainActivity: cp-approval-needed push (targetTab="approvals") opens the queue. PushTokenManager:
  cp-approval-needed→CHANNEL_APPROVALS, cp-approval-decision→CHANNEL_VISITS. Staff card: pending_gm_approval
  → amber "Pending Approval" / "Awaiting: <GM name>" (approvalGmName threaded top-level through
  listMobileCompact → TodayVisit + rejectRemark/reassignedFromRejection). compileDebugKotlin +
  testDebugUnitTest + assembleDebug GREEN.
- TSGO/DEPLOY: convex codegen needs auth (stale env) so tsgo not runnable here, but team continuously grows
  this schema + deploys → tsgo does NOT hit the tsc api-ceiling; cascade is tsc-only (3 http.ts implicit-any
  are pre-existing site-visit handlers touched by the tsc cascade, tolerated). My convex/ routes: clean.
- DEFERRED: web cp-visits page shows pending_gm_approval as an unmapped status label (minor cosmetic).
- NEEDS CONVEX DEPLOY to function. Confirm `convex deploy` succeeds before relying on it.

## Session 209 (main-chat) — CP visit "on web not on mobile" = super-admin 200-cap
- Symptom: a CP visit (07 Aug, scheduled, assigned MADHAN RAJ.M) shows on web CP Visits but not on the
  mobile CP Visits page. Diagnosed: mobile user is SUPER-ADMIN (viewAll) → listMobileCompact returns the
  200 most-recent company-wide visits (by scheduledDate desc); a 5-day-old visit falls beyond 200 and the
  client-side search can't reach it. assignedStaffId IS set (web reads assignedStaffId, no fallback), so
  not an assignment mismatch.
- FIX (server-side search): listMobileCompact +search arg → uses clientPlaceVisits.searchIndex("search_text")
  (no staff filter field, so non-viewAll callers are narrowed by assignedStaffId in JS; viewAll sees all
  matches; date range ignored on search). http.ts /my forwards ?search=. App: getMyMarketingCpVisits +search
  query param; CpVisitsFragment search box now fires a 350ms-debounced backend reload with the term (instant
  local filter still applies on top). Reaches ANY client regardless of the recency cap.
- Validation: :app compileDebugKotlin GREEN; convex/ files (clientPlaceVisits, http) no non-7006 errors.
  NEEDS CONVEX DEPLOY (server-side search lives on max, not prod) — the app APK alone won't help until
  the backend deploys.

## Session 210 (main-chat) — production-correctness pass on the CP approval updates
- Reviewed the staged CP updates for prod; found + fixed real bugs that would misbehave live:
  1. STRANDING: if resolveHandoffManagerStaffId returns undefined (staff has no reporting-chain GM), an
     out-of-geofence completion would sit in pending_gm_approval forever with NO approver. FIX (setOutcome):
     resolve GM BEFORE deciding status; needsApproval = outOfGeofence && !!gmStaffId → fail-open to completed
     when no GM (OTP already proved presence; distance still recorded).
  2. PENDING PILL never showed: mobile mapper toCpListVisitOrNull used effectiveStatus = fieldVisit.status
     ?? cpStatus, and the app calls completeVisit after EVERY outcome, so fieldVisit="completed" masked the
     CP hold → card read "Completed". FIX: pending_gm_approval (CP status) now wins in effectiveStatus.
  3. REOPENED (rejected) visit read as "completed" (fieldVisit stayed completed) → staff couldn't redo. FIX
     (rejectCpCompletion): reset CP to "scheduled" (clear outcome/clientMet) AND reset the fieldVisit
     (status scheduled; clear startedAt/completedAt/arrivalVerifiedAt/arrivalRequestedAt/
     arrivalDistanceFromPlaceMeters/arrivalLat/Lng/arrivalPhotoStorageId) so it's a clean in-geofence redo.
  4. approvalGmName never populated: I'd added it to TodayVisit, but /my deserializes into CpVisitDetail.
     FIX: added approvalGmName/rejectRemark/reassignedFromRejection to CpVisitDetail + copy them into
     TodayVisit in the mapper. "Awaiting: <GM name>" now resolves.
- Verified OK: arrivalDistanceFromPlaceMeters is stored on OTP verify (fieldVisitOtp) before setOutcome
  reads it (fail-open when place has no coords); searchText maintained on create/assign/setOutcome + a
  re-runnable backfillSearchText for old rows; schema additions are additive-optional (backward compatible,
  no migration); status checks elsewhere are targeted (not exhaustive) so pending_gm_approval doesn't break
  aggregates; per-client count unaffected (reject reopens same row).
- Validation: :app compileDebugKotlin GREEN (after KSP-cache/OOM env retries with -Xmx4g); convex/ files no
  non-7006 errors.
- MINOR/known (not blockers): app shows "Visit completed" toast even for a pending completion (the CP card
  still reads "Pending Approval"); web cp-visits page shows raw pending_gm_approval label.
- DEPLOY CHECKLIST: (a) confirm `convex deploy` (tsgo) succeeds; (b) run `npx convex run
  marketing/clientPlaceVisits:backfillSearchText` so older rows are searchable; (c) ensure staff→GM
  reportingTo is set where GM approval is expected (else out-of-geofence completions fail-open to completed).

## Session 211 (main-chat) — pin re-drop doesn't update address (app + web)
- Bug: dropping a pin fills the address the first time, but a SECOND pin drop leaves the old address /
  pincode / district stale. Root = blank-only / keep-old fill on both surfaces; a deliberate re-drop should
  REPLACE the address.
- APP (CreateCpVisitBottomSheet.kt): pin-drop listener filled etAddressLine1 only if blank. FIX: on drop,
  clear etDoorNo/etStreet/etAddressLine2/etCity/etState/etPincode + reset lastEnrichedPincode + always set
  etAddressLine1 = new address → the Line1 paste-parse (re-runs, address >25 chars & differs) + pincode
  enrich repopulate every field from the NEW location.
- WEB (components/unified-address-fields.tsx applyPickedLocation): was `pin.X || current.X` (kept old when
  the new geocode field was empty). FIX: when the geocode resolved (pincode||city||state present), REPLACE
  all address fields with the new pin's components (clearing what it doesn't return); on a failed geocode,
  keep current + just update coords (never blank a good address on a lookup miss). Shared component, so all
  pin-drop forms benefit.
- Validation: :app compileDebugKotlin GREEN; web tsc no non-7006 errors in unified-address-fields.

## Session 212 (main-chat) — out-of-geofence completion warning (app-only)
- Request: when completing a CP OUTSIDE the client geofence, show a warning "You're not near the client
  location — want to complete?" with Cancel / Complete, then the normal photo + OTP flow.
- FIX (TripNavigationFragment.checkReachingAndAskClientSeen): after the fresh GPS fix, compute distance to
  dest; if > GEOFENCE_APPROVAL_RADIUS_METERS (300.0, matches backend CP_GEOFENCE_DEFAULT_RADIUS_M) show an
  AlertDialog (Cancel aborts + resets swipe; Complete runs the extracted `proceed` → CpClientSeenBottomSheet
  → photo/OTP as normal). In-geofence completes with no warning. App-only, no backend/deploy needed.
- Validation: :app compileDebugKotlin GREEN.
- FOLLOW-UP: the warning now also captures a REQUIRED remark (reason) shown to the approving GM. WEB:
  completionApproval +staffRemark; new setCpCompletionRemark mutation (stashes it at warning-confirm time);
  setOutcome preserves prior.staffRemark into completionApproval; listPendingCpCompletionApprovals returns
  staffRemark; POST /api/marketing/cp-visits/geofence-remark route (+public-paths). APP: GeoTrackApi
  setCpGeofenceRemark + CpGeofenceRemarkRequest + CpApprovalItem.staffRemark; warning dialog gained a
  required EditText (Complete blocked until non-empty; overridden positive button) that calls
  setCpGeofenceRemark before proceeding; CpApprovalQueueBottomSheet shows "Staff reason: <remark>". Decoupled
  swipe-time store handles both client-seen Yes/No paths. :app compile GREEN; convex/ no non-7006 errors.
  Web backend NEEDS CONVEX DEPLOY; app ships with APK.

- 2026-08-12 — CP out-of-geofence UI made design-system compliant. Replaced the bare AlertDialog
  warning with a styled bottom sheet: new sheet_out_of_geofence_warning.xml (bg_bottom_sheet_white_rounded,
  drag handle, bg_icon_warning amber circle + ic_location_pin, Inter fonts, bg_outcome_field_pill reason
  field with required-reason validation, bg_grey_rounded Cancel + bg_home_trip_action_ready Complete) +
  OutOfGeofenceWarningBottomSheet.kt (onComplete/onCancel; onCancel resets arrivalInProgress; onComplete
  stores reason via setCpGeofenceRemark then runs normal client-seen→photo→OTP flow). Wired into
  TripNavigationFragment.checkReachingAndAskClientSeen (removed the programmatic AlertDialog block).
  CpApprovalQueueBottomSheet restyled to the design system (Inter fonts, bg_bottom_sheet_white_rounded
  root, bg_grey_rounded handle, pill-styled reject input). :app compile GREEN. build.gradle.kts test-backend
  switch (api-mfpl→next-spaniel-814) intentionally left UNSTAGED. Web CP backend still NEEDS CONVEX DEPLOY.

- 2026-08-12 — CP out-of-geofence warning changed from bottom sheet to a centered FLOATING dialog per
  request. OutOfGeofenceWarningBottomSheet now extends DialogFragment (was BottomSheetDialogFragment);
  onStart floats it centered (transparent window, dimmed scrim, width = screen-2*28dp capped at 400dp).
  Layout switched to bg_dialog_card (all-corner rounded), dropped the drag handle. Same
  onComplete/onCancel contract + required-reason validation; TripNavigationFragment wiring unchanged
  (showOnce is defined on DialogFragment). :app compile GREEN.

- 2026-08-12 — CP distance readout now formats as km (1 decimal) at >=1km, whole metres below, in both the
  completed-visit detail (tvCvdGpsDistance) and the GM approval queue. Diagnosed why an out-of-geofence
  completion still showed "Completed" (not pending_gm_approval): the pending/GM-approval flow is 100%
  backend (setOutcome derives out-of-geofence from fieldVisit.arrivalDistanceFromPlaceMeters > radius and
  holds for GM approval) and the max Convex changes have NEVER been deployed to the live backend the app
  hits — so setOutcome has no out-of-geofence branch there and every completion completes directly.
  Second gate even after deploy: fail-open when resolveHandoffManagerStaffId returns undefined (staff has
  no reportingTo GM) → completes directly by design. Both explain the symptom. Awaiting user decision on
  (a) deploying max, (b) fail-open vs fail-closed-with-fallback-approver.

- 2026-08-12 — Decisions from user: admin will run the Convex deploy (I do NOT deploy); out-of-geofence
  must ALWAYS require approval. Implemented fail-CLOSED on web max (convex/marketing/clientPlaceVisits.ts):
  needsApproval = outOfGeofence (unconditional), and gmStaffId = reporting/department GM ?? active
  super-admin fallback (new resolveCpApprovalFallbackApproverStaffId, normalizeRole from ../../lib/iam-model)
  so an out-of-geofence completion is never auto-completed and never stranded. approvalGmName (shown to
  staff as "Awaiting: <GM>") derives from completionApproval.gmStaffId, now always set. tsc clean for
  clientPlaceVisits.ts. Committed+pushed max (f45f8576). REMAINING BLOCKER for it to show live: admin must
  deploy max Convex to the backend the app hits — until then the live backend has no out-of-geofence branch
  and every completion completes directly (this is why the user saw "Completed").

- 2026-08-12 — Diagnosed "outcome stays locked after scanning SV QR" (evidence-backed trace). ROOT CAUSE:
  markOnCounsellingFromQr never advances the SV to on_counselling (route from commit af137cfc "secure SV QR"
  not on the backend the app hits, OR canStartQrCounselling authorization rejects) AND the app SILENTLY
  swallowed the failure — QrScannerFragment.markSiteVisitOnCounselling opened the outcome page even on
  success==false, stranding the user on a greyed dead-end (setSiteVisitOutcome then rejects on_site). Confirmed
  my OTP/geofence CP changes did NOT regress this: SV outcomes route entirely through setSiteVisitOutcome /
  createBooking (isSiteVisitMode), never clientPlaceVisits.setOutcome; the saveOutcome SV-via-CP branch is dead
  code (0 callers); assertRequiredCpCompletionProof exists only in clientPlaceVisits.ts. FIX (app): on
  counselling-start failure, surface the REAL error and resumeScanning() instead of navigating to a greyed
  outcome page; only open the outcome on success. Also hardened SiteVisitOverviewFragment.isOutcomeAlreadyRecorded
  to read sv.status (not top-level CP status) for the lock check. DEFINITIVE unlock still requires the admin to
  deploy the secure-SV-QR backend (af137cfc: markOnCounsellingFromQr + /markOnCounselling + /siteVisits/setOutcome
  routes). COUNT: audit confirms pending/approve/reject accounting is exactly-once; no per-client >3 completed
  gate exists to skew. (Pre-existing, unrelated: convertToSiteVisit + rejectAsFinanciallyIneligible omit the
  rollup diff — flagged, not fixed.)

- 2026-08-12 — SV overview stepper now shows INCOMPLETE steps as amber outline instead of blue. New
  bg_trip_progress_figma_pending drawable (amber ring, soft-amber fill); added a "pending" state to both the
  cab (9-node) and own-vehicle (5-node) steppers in SiteVisitOverviewFragment.updateStepper. A step is amber
  when it's the step the visit is AT but not yet completed (in-progress current step) OR a fleet-gap step
  (consulting reached while REACHED CP / PICKED FROM CP / ON SITE travelDesk* stamps are missing); genuinely
  completed steps stay filled blue, and the final DONE node stays blue only for an actually-completed visit;
  future steps stay grey. Added onResume re-fetch (skipping the first resume) so the stepper + outcome gate
  update immediately when the user returns after a QR scan / fleet advance. Verified the SV list tab bucketing
  (SiteVisitsFragment effStatus = rawStatus ?? status → Enroute/Onsite/Returning/Completed/Cancelled/Postponed/
  Rejected) is already correct — no change needed; it just needs fresh data. :app compile GREEN.

- 2026-08-12 — Fixed two Home/trip/attendance bugs (evidence-backed trace).
  BUG A (trip startable while clocked out): trip-start gate used the LENIENT source-agnostic
  isClockedInForToday (stays true all day after ANY punch incl. biometric), so a mobile-clocked-out staffer
  could start an untracked trip. Added a strict HomeUiState.hasOpenSessionNow (raw att/day hasOpenSession),
  wired it into createVisitItem canStartTrip + the render signature + HomeViewModel.startVisit/startTripToPlace
  guards. The field-staff trip-card tap also ignored canStartTrip → now routes to clock-in.
  A2 (clock-out is FINAL by design): added a finality warning to dialog_clock_out_confirm.xml ("you won't be
  able to clock in again today from here; use Start Trip on that visit"). All clock-outs funnel through
  HrDashboard's ClockOutConfirmBottomSheet, so it's covered.
  A3 (re-entry via trip): openClockInForTrip(visit) now remembers the visit + passes targetVisitId through
  ClockInAreaFragment.newInstance → SelfieClockInDetailFragment; on PUNCH_IN success it emits a DISTINCT
  RESULT_KEY_CLOCK_IN_FOR_TRIP (so HrDashboard's PUNCH_COMPLETED listener is untouched); HomeFragment listener
  auto-opens that trip's navigation. So clocked-out → Start Trip → clock in → trip starts immediately.
  BUG B (bottom nav gone + white top strip on all tabs after a trip): activity recreation while the trip
  fragment was on the back stack left the trip's chrome (hidden nav + white status strip) stuck because the
  back-stack listener only fires on CHANGES and Home+trip raced over shared chrome on restore. Added
  MainActivity.syncChromeToBackStack() (derives chrome from backStackEntryCount) called from the back-stack
  listener, the onCreate restore branch, and onResume; guarded HomeFragment.onResume chrome asserts behind
  !isHidden. :app compile GREEN.

- 2026-08-12 — Bottom nav "still gone" on Home: the cause was the scroll-driven auto-hide
  (setBottomNavScrollState) used app-wide (Home/HR/Library/Chat/Fleet) — the nav faded out as the staffer
  scrolled their trips and could get stranded (a GONE bar can't be recovered by a scroll-to-top show;
  setBottomNavScrollState(true) early-returns when visibility != VISIBLE). Made the nav PERSISTENT on root
  tabs: setBottomNavScrollState now ignores the hide (visible=false → no-op) and only honours a re-show when
  the bar is on-screen. Also fixed a flag desync: setTabBarVisible(false) now sets isBottomNavVisible=false
  (previously left true, which could early-return a later show and strand the nav). Complements the earlier
  syncChromeToBackStack() recreation fix. :app compile GREEN.
