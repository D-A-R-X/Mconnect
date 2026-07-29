# AGENTS.md

## Mandatory Agent Log Protocol

Every AI agent working on this project must read `AGENT_LOG.md` before doing
work and update it during **every response/turn**, without exception.

- Record every code, configuration, documentation, schema, API, UI, build,
  test, Git, deployment, and investigation change made in that turn.
- If no files were changed, still add a concise entry describing what was
  inspected, learned, answered, or why no change was required.
- Update the log incrementally after meaningful steps, not only at the end of
  a long task.
- Before sending any final response, confirm the current turn is represented
  accurately in `AGENT_LOG.md`, including validation performed, failures,
  remaining risks, and required follow-up actions.
- Never claim work is complete when the corresponding log entry is missing.
- Keep `AGENT_LOG.md` local-only. Do not commit or push it.

## Repo Overview

This repository contains a single Android app module for **Mconnect / Manju Groups PMS**.

It is a Kotlin Android app built with:

- Android Gradle Plugin `9.0.1`
- Kotlin `2.1.20`
- XML layouts + ViewBinding
- ViewModel + `StateFlow` in most feature screens
- Retrofit + OkHttp + Gson for API access
- Room for GeoTrack local buffering
- EncryptedSharedPreferences for session storage

This is **not** a Compose app, **not** a multi-module repo, and **not** using Navigation Component, Hilt, or a repository/data-layer abstraction.

## Project Shape

Top-level structure:

- `app/` — the only Android module
- `app/src/main/java/com/manjugroups/m_connect/` — app code
- `app/src/main/res/` — layouts, drawables, fonts, themes, strings
- `gradle/libs.versions.toml` — central dependency versions
- `app/build.gradle.kts` — Android config and `BuildConfig.BASE_URL`
- `README.md` — useful, but partly stale

Important package areas:

- `auth/`
  - OTP login flow
  - session persistence via `SessionManager`
- `network/`
  - `ApiService` for auth, HR, attendance, storage, IAM, chat
  - `GeoTrackApi` for tracking, consent, visits, heartbeat, tamper reporting
  - most request/response models live inline in these files
- `ui/home/`
  - dashboard, punch in/out, photo upload, visit/trip controls
- `ui/hr/`
  - HR dashboard, staff, leaves, permissions, attendance screens
- `ui/chat/`
  - channels, DMs, message thread UI
- `ui/profile/`
  - profile and logout
- `geotrack/`
  - consent activity
  - foreground service
  - Room database for pending location points
  - boot/activity-recognition receivers

## Build And Test

Use Gradle from repo root:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

If you only want a quick compile-level sanity check:

```bash
./gradlew :app:assembleDebug
```

## Runtime Architecture

### Entry flow

Launch activity is `auth/LoginActivity`.

High-level app flow:

1. `LoginActivity` accepts phone number and requests OTP.
2. `OtpActivity` verifies OTP and stores session details in `SessionManager`.
3. `MainActivity` is the post-login shell.
4. If GeoTrack is enabled for the user and consent is still pending, `MainActivity` redirects to `GeoTrackConsentActivity`.
5. If GeoTrack is enabled and consent already exists, `MainActivity` starts `GeoTrackService`.

### Main shell and navigation

`MainActivity` owns a custom bottom tab bar and swaps fragments manually with `FragmentManager`.

Primary tabs:

- `HomeFragment`
- `HrDashboardFragment`
- `ChatListFragment`
- `ProfileFragment`

Secondary screens also navigate with manual `FragmentTransaction.replace(...).addToBackStack(null)`.

There is **no Navigation Component graph**. When changing navigation, preserve the current fragment-container pattern unless the task explicitly includes a navigation refactor.

### Session model

`SessionManager` is a central dependency throughout the app. It stores:

- bearer token
- name / role / phone
- IAM permissions
- staff ID
- GeoTrack flags and consent state

Most screens instantiate `SessionManager(requireContext())` directly rather than receiving it via DI.

## Feature Notes

### Auth

Auth is handled by `AuthViewModel` using `StateFlow<AuthUiState>`.

Patterns already used:

- optimistic screen transitions after successful OTP send/verify
- `repeatOnLifecycle(Lifecycle.State.STARTED)` collection in activities
- session persistence immediately after OTP verification

### Home / Attendance / Punch

`HomeFragment` and `HomeViewModel` are the operational center of the app.

Responsibilities include:

- loading today attendance and monthly stats
- punch in / punch out
- optional last-known location capture
- optional camera capture for punch photo
- storage upload through generated upload URL
- GeoTrack start/stop hooks
- today visit loading and trip start/complete actions

When editing this area, watch for side effects:

- punching can start or stop `GeoTrackService`
- camera flow uses `FileProvider`
- location capture is currently best-effort and based on last-known location

### HR

HR screens mostly follow a simple MVVM pattern:

- fragment binds UI
- viewmodel calls `ApiService` directly
- state is held in `MutableStateFlow`
- success/error toasts often come from `SharedFlow`

Notable subfeatures:

- dashboard permission gating from IAM + policy
- paginated staff loading and local search mode
- leave balance/history/application
- permission usage/history/application

### Chat

Chat is more ad hoc than the HR flow.

`ChatListFragment` and `ChatMessagesFragment` call `ApiService` directly from fragment coroutines instead of routing through a dedicated ViewModel. If you touch chat, preserve behavior carefully and avoid assuming the same architecture conventions as the HR screens.

Current message ownership logic is based on `senderName == session.userName`, which is weaker than a stable ID-based comparison.

### GeoTrack

GeoTrack is the most operationally sensitive part of the app.

Key pieces:

- `GeoTrackConsentActivity`
- `GeoTrackService`
- `BootReceiver`
- `ActivityRecognitionReceiver`
- Room DB under `geotrack/data/`

`GeoTrackService` currently does all of the following:

- foreground notification lifecycle
- wake lock management
- fused location registration with `LocationManager` fallback
- activity recognition transition registration
- tamper signal reporting
- periodic heartbeat
- local Room buffering of location points
- periodic sync to backend
- final sync and stop call on teardown

Treat this service as high-risk code. Small edits can affect battery usage, permissions, background behavior, and attendance/trip flows.

## Networking Conventions

`ApiService.create()` and `GeoTrackApi.create()` build Retrofit clients inline with:

- `BuildConfig.BASE_URL`
- OkHttp logging interceptor at `BODY`
- 30 second connect/read timeouts

Current base URL comes from `app/build.gradle.kts`:

- `https://opulent-cricket-895.convex.site/`

Important: `README.md` still mentions a different base URL. Prefer the Gradle config over README when working on network-related changes.

There is no shared repository layer or DI container. ViewModels and fragments create API clients directly.

## UI And Styling Rules

The app has a defined XML design system and it should be preserved.

Use:

- semantic theme attrs like `?attr/colorAccentPrimary`
- text appearances from `res/values/type.xml`
- shared dimensions from `res/values/dimens.xml`
- bundled fonts from `res/font/`

Avoid:

- hardcoded hex colors in layouts/drawables
- raw `@color/dk_*` / `@color/lt_*` references in UI files when a semantic attr exists
- arbitrary spacing literals when a shared dimen already exists
- switching fonts away from the existing `Inter` / `Geist Mono` system

The app follows system dark/light mode via `MconnectApp`.

## Working Agreements For Changes

When making changes in this repo:

1. Preserve XML + ViewBinding patterns unless the task explicitly asks for a Compose migration.
2. Prefer matching the surrounding feature style instead of forcing a repo-wide architectural rewrite.
3. For HR/home/auth screens, keep `StateFlow` + lifecycle-aware collection.
4. For chat, be extra careful because it is fragment-driven and more tightly coupled to current UI behavior.
5. For GeoTrack, verify permission flow and service lifecycle assumptions before refactoring.
6. Keep manual fragment navigation consistent with the existing `fragmentContainer` approach.
7. Be cautious with session fields because many screens read directly from `SessionManager`.

## Known Sharp Edges

- `README.md` is partly stale and should not be treated as source of truth for all runtime details.
- The repo currently has very light automated test coverage.
- Network models are large and colocated in API files, so edits can have wide compile impact.
- Chat does not yet follow the same ViewModel/state structure as most other features.
- GeoTrack behavior depends on multiple Android permissions, process state, and boot/service flows.
- This workspace currently does not appear to be a Git worktree, so do not assume Git commands will work.

## Best Places To Start When Debugging

- Login/session issues:
  - `auth/LoginActivity.kt`
  - `auth/OtpActivity.kt`
  - `auth/AuthViewModel.kt`
  - `auth/SessionManager.kt`
- Main tab/navigation issues:
  - `MainActivity.kt`
- Attendance/punch/trip issues:
  - `ui/home/HomeFragment.kt`
  - `ui/home/HomeViewModel.kt`
- HR policy/permission/staff issues:
  - `ui/hr/HrDashboardFragment.kt`
  - `ui/hr/HrDashboardViewModel.kt`
  - `ui/hr/LeavesViewModel.kt`
  - `ui/hr/PermissionsViewModel.kt`
  - `ui/hr/HrStaffViewModel.kt`
- Chat issues:
  - `ui/chat/ChatListFragment.kt`
  - `ui/chat/ChatMessagesFragment.kt`
- GeoTrack issues:
  - `geotrack/GeoTrackConsentActivity.kt`
  - `geotrack/service/GeoTrackService.kt`
  - `geotrack/service/BootReceiver.kt`
  - `geotrack/data/*`

## Suggested Default Validation After Edits

For typical UI or Kotlin code changes:

```bash
./gradlew :app:assembleDebug
```

For logic changes in viewmodels or utilities:

```bash
./gradlew testDebugUnitTest
```

For manifest/resource/theme changes:

```bash
./gradlew lintDebug
```
