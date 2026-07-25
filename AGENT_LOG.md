# AGENT_LOG.md

> **⚠️ LOCAL ONLY — DO NOT COMMIT OR PUSH**
> This file is listed in `.gitignore` and must stay that way.
> It is a running logbook for AI agents (Antigravity / Claude / Gemini, etc.)
> working on this repository so each session picks up exactly where the last left off.

---

## What Is This File?

`AGENT_LOG.md` is a **local AI agent session log** for the **Mconnect / Manju Groups PMS** Android app
and its companion web backend (`C:\Users\surya\Projects\manjusitedevelopment`).

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

**Date:** 2026-07-25
**Agent:** Google Antigravity (Gemini / Claude Sonnet 4.6 Thinking)
**Conversation ID:** `d74cf9ca-c170-4525-9e09-0709fa204589`

### What Is Working
- Full booking form field wiring between web and mobile (API → app fields).
- Home address / phone number fetching correctly.
- Recomplete / Offline-Completed flow fully implemented (see Session 4 below).

### Open Items / Next Steps
- None critical. The recomplete flow is end-to-end and the Android debug build passes.
- If the Convex backend schema migration hasn't been deployed yet (`npx convex deploy`), run it.
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
