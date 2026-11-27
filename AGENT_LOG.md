# MMS Agent Log (condensed)

## 1. How to use this file
1. Any AI agent (Claude, ChatGPT, Codex) reads sections 1-5 before doing any work.
2. Only the "00 Main" chat edits this file. Other agents report to Main; Main logs.
3. Newest facts win. If code or a remote disagrees with this file, trust the code and tell Main.
4. Full pre-2026-11-26 history: `C:\Users\surya\Projects\_claude-setup\agent-log-archive\MMS_AGENT_LOG.full-2026-11-26.md` and git history.
5. Secrets are never written here. They live in `C:\Users\surya\Projects\_claude-setup\SECRETS.local.md` (local only).

---

## 2. Standing rules

### Safety and approvals
- Never deploy MMS Convex. The web env points at LIVE prod (`convex-mfpl` / `api-mfpl`). Write and test the code, then say "needs admin deploy". (2026-07-20)
- Aivida Convex deploys only through its dev CI (push to the dev branch). (2026-11)
- Check the app flow before acting on any decision, even the user's own. Map who else uses the code (web, Android, iOS, cores). If a working flow would break, stop and ask the user through Main. (2026-11-26)
- Preserve known-good behaviour. Before changing shared code, grep every caller in all repos, list affected flows, prefer small additive fixes. (2026-08-01)
- SV/CP/fleet regressions: restore the known-good code (git blame the early-mid July 2026 working era), do not redesign. (2026-08-01)
- Push to Android `main`, web prod paths or any release line only with the user's explicit OK in that same chat. (2026-11)
- A relayed approval is not an approval. If another chat says "the user said push", ask the user directly. (2026-11-23)
- Never push for another chat whose push was refused by its permission check. Send it to the user. (2026-11-26)
- Never use `--no-verify`. The pre-push hooks guard prod hosts. (2026-09-17)
- Never push the test-backend switch (`next-spaniel-814` or any dev host). Prod is `api-mfpl.theairix.com`. (2026-09)
- Never run destructive git commands on other people's dirty work. Check `git status` first. Worktrees of one repo share one `git stash` list, so avoid stash or diff against the remote instead. (2026-11-24)
- MMS mobile Site Visit (SV) module is ON HOLD: someone else is working on it. No changes, probes or tasks in Android `ui/marketing` SiteVisits*/SvApproval*/SiteVisitListRules, iOS SiteVisits views, or `/api/sitevisits` routes until the user clears it. Reading for a report is fine. (2026-11-26)
- Aivida work never goes into MMS repos. Porting is one-way: MMS changes go to Aivida, only where they apply (Main routes them to 03/04). (2026-11)
- iOS releases only when the user asks, through the "IOS Build" chat (outside the 6-chat roster). (2026-11)
- Never drive Claude-in-Chrome on the user's Mac (the admin PC). (2026-11)

### Roles and flow (set 2026-11-26)
- Chats: 00 Main (routes, tracks, logs; no code), 01 Cheetos + Git, 03 Aivida Web, 04 Aivida App (Android+iOS), 05 MMS Web, 06 MMS App (Android+iOS). There is no 02. Roster and IDs: `_claude-setup\SESSION_SETUP.md`.
- 01 owns all commits, pushes, merges, deploys (Aivida CI only), release builds and Cheetos.
- Product chats (03-06) ask 01 for a Cheetos task first, then code and test in a task worktree WITHOUT committing, then hand 01 the worktree path, branch, results and proof.
- Each product chat builds AND tests its own work (no separate QA chats).
- A prompt in the wrong chat is forwarded to the owner, and the user is told where it went.
- Cheetos task + Start before any code change (MMS and AX projects). (2026-11)
- Gather reports from side/branch chats and get their finished work pushed (via 01). (2026-11-25)
- Every Android fix is also checked on iOS (FoundationChat `darx`), fixed there if needed and xtool-compiled. Every mobile behaviour change covers both platforms. (2026-08-28)

### Git identity
- Commit and push as `D-A-R-X` in every repo (repo-local git config already set; check before committing elsewhere). (2026-09-29)
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

### Privacy and secrets
- Never put phone numbers, lead/patient/customer names or AI call-summary text in Cheetos, proof images, logs or chat. Blur them in screenshots.
- Proof images go through a temporary secret gist that is deleted right after upload. Text-only proof = a rendered card (PNG).
- Never type passwords or OTPs to sign in. The user signs in on the browser pane or phone.
- Never echo keys. If a key is pasted in chat, store it only in the gitignored env file and ask for rotation.
- Secrets, logins and signing info: `C:\Users\surya\Projects\_claude-setup\SECRETS.local.md`, `~\mconnect-build-kit\secrets`. Never commit or paste them.
- Browser checks on MMS web are read-only: it shows live prod data.

### Phone (OPPO CPH2729, adb serial 77cd83cc)
- One chat drives the phone at a time. 04 and 06 announce "taking" and "released". Never `adb kill-server` while the other chat is on it.
- Never uninstall a Play build without the user's OK in that chat.
- Tests that create records on live prod need the user's OK.
- A debug-signed build has a different ANDROID_ID, so device binding refuses the login or locks the real app out until an admin reset. It also shares the Play package name, so it cannot sit next to the Play app. Prefer the installed Play build for read-only checks.
- Heavy builds: one Gradle/xtool/tsc/vitest run at a time (scratchpad `heavy.ps1` lock, Gradle `--max-workers=2`). The PC has ~16 GB RAM and WSL is heavy.

### UI conventions (Android XML + ViewBinding, iOS SwiftUI)
- Reuse the Mconnect UI system: semantic theme attrs, `type.xml`, `dimens.xml`, Inter / Geist Mono. No hardcoded colours.
- Never use stock dialogs. Android: `AppBottomSheets.showConfirm(cancelLabel)`, `RejectWithReasonBottomSheet`. iOS: `Views/Shared/MconnectConfirmSheet` (`.mconnectConfirm`), `ActionReasonSheet`. (2026-11-19)
- Buttons: `Widget.Mconnect.Button*` styles, primary = brand blue. If a styled MaterialButton keeps `android:background`, set `app:backgroundTint="@null"`. Clock-in/out buttons keep the original design. iOS: `MconnectButtonStyle`.
- Skeleton placeholders on all data loading (`SkeletonLoader`). Keep the skeleton until first data.
- Value pickers use `SearchableSelectionDialog`, not plain AlertDialog lists.
- Media capture reuses chat's `CustomCameraBottomSheet`.
- Error/notice UI = the Floater capsule (own window, below the header). Button busy state = `ButtonLoader`.
- Detail screens open with `pushDetail` (view retention), not `replace()`.
- Cache-first loading via `LocalCache` (filesDir JSON) for instant paint.
- Gson models: new optional fields nullable, polymorphic fields `Any?`. One bad field kills a whole list parse.

### Product rules the user set
- GeoTrack captures and uploads only between clock-in and clock-out.
- Mobile clock state: a biometric gate punch-in counts as clocked in; only a mobile clock-out clocks out; a gate punch-out mid-shift does not stop tracking.
- Mobile Issues (web Settings > Mobile > Issues): no Pending status; "Copy as Markdown" marks Under dev; an issue becomes Fixed only after the next release (or server) proves the fix. Group same-cause issues.
- Travel allowance: trips over 1 km qualify; 100 m office geofence exclusion anchored at the day's punch-in point.
- Agent logs: every response gets a log line (Main writes them). Keep this file under ~700 lines; prune daily.

---

## 3. Project map

### Repos (all under `C:\Users\surya\Projects\`)
| Product | Local path | Branches / remotes | Push rule |
|---|---|---|---|
| MMS Android (Mconnect) | `Mconnect` | `origin` pushes to manjugroupsdev + D-A-R-X | `main` (fast-forward) and `merge` (separate line, `cherry-pick -x`), prod hosts only, user OK for main |
| MMS iOS (FoundationChat, "FC") | `FoundationChat` | origin | `darx` only, only when FC is named |
| MMS web + Convex backend | `manjusitedevelopment` | origin | branch `zeus`; merge origin/dev + origin/prod into zeus at every web task start and end; never deploy Convex |
| Travel Desk portal | `travel-desk` | origin | `aizen` |
| MMS geo service (Go) | `geo-tracking-service` | origin | `dev` (auto-deploys dev-api-geo only); prod deploy by the team |
| MMS telecaller core (Go) | `telecaller-core` | origin | per task, user OK |
| WhatsApp sales bot | `whatsapp-apis-main` | origin | `lex` (push to `main` deploys PROD) |
| Aivida apps/web | `Mconnect-Aivida`, `Aivida-Airix-Ios`, `AividaAirix-web`, `telecaller-core-aivida`, `geo-tracking-service-aivida` | see SESSION_SETUP s2 | Aivida work; own log `Mconnect-Aivida\AIVIDA_AGENT_LOG.md` |

- Task worktrees: `Projects\Mconnect-mms-NNN`, `Projects\FoundationChat-mms-NNN`; web worktrees in Temp `zeus-*` with a node_modules junction to the main checkout. Copy gitignored files in (`google-services.json`, `secrets.properties`, `local.properties`, `.env.local`).
- Main Mconnect checkout is on `merge` and dirty with local-only files (.idea, .claude, reports, AGENT_LOG). Don't commit those.
- MMS web main checkout stays on `dev` with uncommitted 16 Oct siteVisits WIP. Do not touch it; work on zeus in a worktree.
- Release build tree: `Projects\Mconnect-release` (clean worktree at origin/main).
- Prod web (mg.theairix.com) is built by the team from `origin/prod`. Web pushes to `dev` auto-deploy DEV. Pushing web code never deploys Convex by itself.
- origin/dev on web was rewritten once (after 11 Oct) and dropped commits. When pulling, check patch-ids before assuming your commits are there.

### Hosts
| Use | Prod | Dev |
|---|---|---|
| Convex HTTP actions (app BASE_URL) | `api-mfpl.theairix.com` | `dev-cvx-http-mg.theairix.com` |
| Convex client (web only, not the app) | `convex-mfpl.theairix.com` | `dev-cvx-mg.theairix.com` |
| Web + storage (APP_URL, MFPL_API_BASE_URL) | `mg.theairix.com` | `dev-mg.theairix.com` |
| GeoTrack (`GeoTrackApi.DIRECT_BASE_URL`) | `api-geo.theairix.com` | `dev-api-geo.theairix.com` |
| Dialer | `dialer.theairix.com`, `api-dialer.theairix.com` | none (stays prod) |

- Dev hosts live in 6 places: `app/build.gradle.kts` (BASE_URL, APP_URL, MFPL_API_BASE_URL), `GeoTrackApi.DIRECT_BASE_URL`, `ModernDialerWebViewBridge.HOST_BASE_URL`, `TaskNavRouter.WEB_APP_URL`. Switch all together. iOS: `FoundationChat/Config/AppConfig.swift`.
- Local default = DEV hosts. Switch to prod only to commit/push, then back. If the user asks for prod as the resting state, leave it on prod.
- Before every app commit: grep for `dev-cvx|dev-api-geo|dev-mg`. `GeoTrackDirectContractTest` and `MobileStorage*Test` fail on non-prod hosts: treat that as a stop sign.
- Setup doc for another PC: `Mconnect\reports\MMS_MCONNECT_DEV_BUILD_SETUP.md` (includes a dev/prod switch script).

### Build and test
- Android: `.\gradlew.bat :app:assembleDebug`, `:app:testDebugUnitTest` (~650 tests). Set JAVA_HOME to Android Studio's JBR. `lintDebug` may hang (old ObjectAnimatorDetector NPE) and has pre-existing errors.
- Android release: `~\mconnect-build-kit\release.ps1 <versionCode>` from `Projects\Mconnect-release`. Output AAB + R8 mapping on the Desktop. The user uploads to Play. Next versionCode **112**.
- iOS: no Mac. Compile check via WSL + xtool + Xcode 27.2 SDK: `Projects\ios-xtool-check\fc_build.sh` (generic `wt_build.sh <repo> <bundle>`). Swift helper tests: `run-mobile-helper-tests.sh`. Real device/TestFlight needs the IOS Build chat.
- Web: `npx tsc --noEmit` (compare to the baseline, e.g. ~155 errors in convex on zeus; never expect zero) and focused `vitest`. The full Convex suite has ~110 pre-existing failures; compare against a clean baseline.
- Geo/core Go services: `go test ./...`; integration tests on WSL Postgres 17 + Timescale (scripts in `ios-xtool-check`).
- Telemetry: PostHog project "MMS Mobile" (mobile endpoints + exceptions, client IP discarded). A daily read-only check `check-mobile-posthog-diagnostics` (10 AM IST) and a daily Cheetos check-in task exist; both often fail when the built-in browser is unavailable.

### Cheetos (cheetos.kosal.io)
- Projects MMS and AX. Owner DARX, reviewer Manoj Prabhakar. Signs in via GitHub in the built-in browser.
- Selects must be set by real clicks (script-set values don't save). Submit needs a reviewer and the acceptance box ticked.
- FoundationChat has GitHub Issues disabled, so "create issue & branch" can fail for FC tasks.

### Logs, notes and handoff docs
- MMS log (this file, condensed) and full archive in `_claude-setup\agent-log-archive\`. Aivida log: `Mconnect-Aivida\AIVIDA_AGENT_LOG.md`.
- Live memory notes: `C:\Users\surya\.claude\projects\C--Users-surya-Projects-Mconnect\memory\` (backup in `_claude-setup\memory-backup\`).
- Side-session notes (investigations, handovers, proof cards) are in the `side-session-notes` folder named in each entry.
- Useful docs in `Mconnect\reports\` (local, untracked): `MMS_MCONNECT_DEV_BUILD_SETUP.md`, `MOBILE_OUTSTANDING_API_REQUIREMENTS.md`, `CP_CREATION_COMPLETION_API_VERIFICATION.md`, `JOINT_CP_ROLE_INVERSION_FIX.md`, `LENS_ENROLLMENT_ADMIN.md`, `LENS_FACE_CAPTURES_API.md`, `Mobile_Modern_Dialer_API_Handoff.txt`, `DIALER_VOICE_BREAKING_NOTE.md`, `web-commits-zeus-dev.md`.
- Untracked CI drafts exist: `Mconnect\.github\workflows\android-play-internal.yml` (gated by repo var `PLAY_PUBLISH_ENABLED`) and FC `.github/workflows/ios-testflight.yml`. Do not commit them by accident.

### Probing prod safely
- Prefer reading code and logs. Any live probe must be read-only.
- GET probe: 401 means the route exists, 404 "No matching routes" means it is not deployed. POST-only routes answer 404 to a GET even when deployed.
- Prod deploys lag the branches. Before retiring an app fallback, verify the exact route/field on prod.
- Never send test traffic that creates records, sends WhatsApp/SMS, or punches attendance without the user's OK.

---

## 4. Architecture and sharp edges

### Server capacity (affects every flow)
- Prod Convex is capped at 16 concurrent actions ("TooManyConcurrentRequests"). Peaks give 429, then 503; Cloudflare blips give 502/520; Convex queries also hit a 1 s limit.
- Most 429/500/502/503/504 Mobile Issues are this overload or bad phone networks. Usually no code change.
- App rules: share duplicate reads (single-flight), retry 429/502-504 quietly with backoff (honour Retry-After), keep the cached list on busy errors, never poll hidden screens, gate prefetches on permission.
- An iOS app open fires ~12 calls at once. A few phones opening together can fill all 16 slots.
- Open: Convex plan upgrade (admin decision), server-side query speed.

### Login and session
- Phone OTP login (SMS + WhatsApp) and Employee ID + password. Server now replies before delivery finishes; app polls `POST /api/auth/otp-status`.
- OTP autofill: SMS consent + Retriever zero-tap + WhatsApp one-tap. Play app hash for MMS is `xgzivmg5VXG`. Test autofill only on Play-signed builds.
- Device binding: one account per phone. Android ANDROID_ID, iOS Keychain UUID. Enforced at login and on push/register and device sync. Admin reset in web staff Security tab (`staff.resetDeviceBinding`).
- `SessionInvalidationPolicy`: log out only on a 401 with a real token from the MMS host. A geo 401 must never clear the MMS session.
- Accounts auto-freeze after 2 consecutive working days absent (send-otp 400). That is the rule working.
- Logout must stop GeoTrackService and clear notifications.
- Old app id `com.manjugroups.m_connect` (pre June 2026) can coexist with `com.manjugroups.mconnect`; `OldAppUninstallGate` handles it.
- Required-update gate persists offline and covers every activity (`RequiredUpdateStore`, `UpdateRequiredActivity`).

### Clock-in, attendance, clock-out (HR)
- Selfie clock-in/out. The confirm screen shows a short place name ("Area, City") with coordinates as fallback; the punch payload is unchanged (MMS-280).
- Offline-first punches: saved locally with the tap time, replayed oldest-first, filed under the tap's IST day (7-day window). A server 500 on punch-in is treated as an outage and queued. Never hard-block on GPS before queueing.
- "Already punched in" from the server is shown as already recorded, not as an error.
- Midnight auto-close (23:59:59) must not show as a punch-out.
- Today card: shows "in - out" after a mobile clock-out, "--" while clocked in (MMS-286).
- Attendance reads (`attendance/today`, `day-sessions`) are single-flight shared (Android `AttendanceReads`, iOS `HRAttendanceReadSharing`). iOS bypasses the URL cache and trusts a punch < 180 s old (`LocalPunchState`).
- HR policy: explicit 0 in templates is honoured (prod policy may show only Unpaid leave; that is parity, not a bug).
- Leaves/permissions: no past dates; per-id in-flight guard on cancel; prefetch of attendance/all and fines only with permission.
- Mobile attendance remark/correction requests are disabled server-side (410).
- Pitfall: `getMyAttendance` and HR review are heavy and hit the 1 s Convex limit; HR review uses the paged query.

### GeoTrack (highest risk)
- `GeoTrackService`: foreground service, wake lock, fused location with fallback, activity recognition, tamper reports, heartbeat, Room buffer, periodic sync, final sync on stop.
- Window: only between clock-in and clock-out. Cold starts fail closed: no GPS/heartbeat/tamper until attendance confirms an open session. A null/failed bootstrap is "unknown", not "off shift".
- App punch-out stops the service at once (the in-window tail still flushes).
- Store-and-forward: every point and event buffered in Room and replayed with original timestamps. Events go before points on reconnect.
- Heartbeat is Doze-proof (alarm + wall clock). GPS is motion-adaptive; HIGH_ACCURACY whenever `SessionManager.fieldActivity()` is set.
- Tracking notification adapts to `fieldActivity` (onduty/cp/sv/fleet). Missing permissions show a red non-closable notification and a non-dismissible gate (incl. activity recognition).
- `BootReceiver` restarts tracking on boot and `MY_PACKAGE_REPLACED` (uses goAsync). An app update while tracking gives a ~17 s gap, buffered.
- iOS: `pausesLocationUpdatesAutomatically = false`, or iOS suspends and staff go Offline.
- Off-duty tamper events are ignored (session covers the instant with 2 min grace; an open session counts only on its start IST day).
- Prod geo runs with auth disabled: `sessions/current` needs a staffId fallback, and `api-geo/api/tracking/live` is publicly readable (open security item). Ending an already-ended session returns 404 (handled by a stop guard).
- `GET /api/geotrack/snapped-route` exists only on geo dev, not prod. Prod web/apps draw cleaned raw fixes (`TrackCleaner`, stays collapsed).
- Geo Timeline/session-route reject ranges > 48 h; read per IST day.
- Flight mode: Android reads the broadcast extra and rechecks after 1.5 s; geo marks AIRPLANE_MODE; iOS reports ON only when cellular is off too.

### Site Visits (SV) and Client Place visits (CP)  [SV module on hold]
- Shared server code: `setOutcome`, `postponeSiteVisitCore`, `cancelSiteVisitCore`, arrival OTP (`fieldVisitOtp`), `completeVisit`, `clientPlaceVisits` to `siteVisits` links, `isCpVisit` routing. A change to one hits SV, CP, SV-cum-CP and fleet.
- CP types: new client, old client, collection, booking, joint, SV-cum-CP, other. `follow_up` is retired as a create type.
- Rules: one CP per client per day; a New Client CP is rejected if the phone already exists; follow-up date caps (collection 5 days, booking 7).
- CP completion: client arrival OTP is mandatory; client not met = photo only, no OTP. Completion outside the client geofence is held as `pending_gm_approval`. Only the assigned GM (`completionApproval.gmStaffId`) can approve.
- Joint CP: outcome owner = lower numeric IAM level. Old rows may be inverted; admin repair tool `jointCpRoleRepairPreview/Apply`.
- SV approvals = out-of-station handoffs (out_of_station, immediate_pickup, direct_sv) waiting for the GM. Super admins see Mine/Team/All; others one list. Reject needs a reason.
- SV cancel is disabled once the client is on site (server 409 CLIENT_ON_SITE; web admins can still cancel).
- SV outcome "Booking Follow Up" (stored as follow_up) with reasons and per-reason fields.
- QR scan: an already-started visit opens the outcome page; QR can be read from the gallery.
- MMS-263: outcome is required before completion; GM-hold lock. Known bypass found 2026-11-21 in `TripNavigationFragment` (any incoming cpOutcome or a linked SV id counts as "decision captured").
- SV "All" list: `GET /api/sitevisits/my` -> `listMobileAdvanced` (SVs plus CP field visits, newest first, 100 per page; app drops CP rows and auto-fills 10 pages). Keep sending pageSize: the unpaged admin path once broke the 16 MiB read limit.
- iOS lowercases request ids: the server lowercases idempotency keys.
- 21-Nov `SV_and_CP_Flow_Requirements.pdf` + `Booking_Requirements_1.pdf` are NEW requirements, mostly not built even on web. Booking doc is a teammate's task (MMS-267).

### Fleet and Travel Desk
- Travel Desk portal (`travel-desk`) is external-agency only; internal agencies/drivers sign in through MMS. Agency kind lives on `travelAgencies.kind`.
- MMS owns the schema and `/api/travel-desk/*` contract. Order of work: MMS backend, then Travel Desk web, then Mconnect.
- Billing is gated server-side for staff without billing access. Client OTP is minted at "arrived". Cancellation billing has its own path. Trips expire after 48 h.
- App: driver trip lifecycle, odometer photos (camera or gallery), fleet type incl. own vehicle, external agency screens.

### Chat
- Fragment-driven (no ViewModel). Message ownership uses `senderName == session.userName` (weak).
- Polling is gated to visible screens with backoff after 429/503 (MMS-257, Android 52f9a2d2). Conversation/channel polls still run every 5 s while visible.

### Dialer (MMS)
- Modern Dialer runs as a hidden WebView/iframe from `dialer.theairix.com`. Web dialer fixes 50fd67e9 + 6310bbb7: telecaller-core JWT errors are retryable, and the iframe key follows phoneIdentity (not the session token) so a session renewal doesn't hang up.
- Mobile dialer work was deferred on 2026-09-07 (admin config not ready). Past no-audio failures were provider ICE/RTP issues, not app code. "Voice breaking" reports point at the outbound leg after Asterisk (gateway/SIM).
- MMS telecaller-core has the same follow-up replacement bug Aivida fixed in AX-191/AX-198 (`internal/followups/service.go` ~334). Not fixed (user said Aivida only).

### VP dashboard, Lens, alerts, issues
- VP dashboard: Home "Today's Overview" tiles gated by `vpDashboard.view`, backed by `/api/dashboard/vp|calls|registrations`. `registrations` is MMS-only.
- Lens face enrollment: HR > Lens Enrollment for super admins or `lens.enroll`. Lens key only in the Convex env (`LENS_API_KEY`), never in apps or repos. Photos are kept on the staff profile and can be sent later ("Send to Lens"). Delete/retake never calls Lens DELETE. A `lensTest` build type does dry-run capture. Docs: `reports/LENS_ENROLLMENT_ADMIN.md`, `LENS_FACE_CAPTURES_API.md`.
- Mobile Alerts (web Settings > Mobile > Alert): templates, groups, 20 s Unsend window, withdraw push. Version tab records builds from request headers.
- Apps auto-report crashes, HTTP and flow failures to Mobile Issues; fix verification uses a queued watch list (one 30 s batch per isolate) to avoid hot-document overload.
- Biometric device punches push a notice to the staff phone (server `sendToStaff`).

### Other modules (smaller notes)
- Loan Desk: documents can change only at the collection/verification stage; mobile sends `currentLoanStage`.
- Bookings: web-parity booking form (sections, calc engine, KYC uploads). A source-linked SV booking completes the SV only on submit, not on Save Draft.
- Daily Log / DPR: attachments are stored server-side; old entries made by older builds have none.
- Front desk QR scanner: blocked on Aivida. Start counselling counts the telecaller only when there is no BDO (server rule).
- Collections, Post-sales, Land procurement inspection, Expenses and Queries exist on MMS and are hidden or blocked on Aivida.
- Offline HTTP cache: OkHttp returns a synthetic 504 "only-if-cached" after a real failure. Those reports are network noise, not server bugs.
- Notifications: taps route by `referenceType`; high-priority alerts use the alerts channel.

### Shared code to watch
- `SessionManager` is read everywhere; don't rename fields.
- Network models sit inline in `ApiService.kt` / `GeoTrackApi.kt`; edits there compile widely.
- Navigation is manual `FragmentManager` in `MainActivity` (no Navigation Component).
- Web `convex/http.ts` is huge and serves every mobile route; one bad edit breaks many clients.

### Where to start debugging
- Login/session: `auth/LoginActivity`, `OtpActivity`, `AuthViewModel`, `SessionManager`, `SessionInvalidationPolicy`; web `convex/staffDeviceBinding.ts`, auth OTP functions.
- Attendance: `ui/hr/AttendanceFlowViewModel`, `HrDashboardFragment`, `network/AttendanceReads`, `AttendanceTrackingGate`; iOS `AttendanceTrackingGate.swift`, `HRDashboardView`.
- GeoTrack: `geotrack/service/GeoTrackService`, `BootReceiver`, `TrackingNotification`, `geotrack/data/*`; iOS `LocationManager.swift`; Go `geo-tracking-service`.
- SV/CP: `ui/home/TripNavigationFragment`, `CompleteCpVisitBottomSheet`, `ui/marketing/CpVisitsFragment`, `SiteVisitOverviewFragment`, `SvApprovalQueueFragment`, `QrScannerFragment`; web `convex/marketing/clientPlaceVisits.ts`, `siteVisits.ts`, `outOfStationHandoffs.ts`, `hr/fieldVisitOtp.ts`.
- Errors/telemetry: `IssueReporter`, `ApiErrors`, `Floater`; web `convex/mobileIssues.ts`.

---

## 5. Current state and open work (as of 2026-11-26)

### Latest known heads (verify on the remote before use)
- Android `main` eba41bf1, `merge` fab14a97 (both include MMS-286). Play: v111 built 2026-11-25 (bump c25880bf); next versionCode 112.
- iOS FC `darx` b79781f (MMS-283 fix). Last TestFlight prompt was build 71 (2026-11-17); later iOS fixes need a new build.
- Web `zeus` e7080a8a on origin (2026-11-24, level with dev + prod then). The local zeus checkout is older; merge dev + prod at the next web task.
- Geo `dev` 77131e4 (off-duty tamper gate) on dev-api-geo only.

### Done and in review (nothing to do unless sent back)
- MMS-286 Today card shows clock-out time. In Review (text proof, not phone-run).
- MMS-283 SV Approvals "server busy" on first open. In Review. Android fb747b9f, iOS b79781f.
- MMS-280 selfie place name; MMS-271 CP trip map staff tracks; MMS-250 iOS request load; MMS-256/257 Android load fixes. In Review.
- MMS-269 SV/CP flows: Done on Cheetos (Android 01b7ce76 / merge e3c9dcd0, iOS fbe434a, web 83d091b7).
- Clock Out Notes label fix: main 59e2106c / merge 3a9a1cdf (user asked for it on MMS too).

### Waiting for an admin Convex deploy (code is on zeus)
- MMS-269 server half: arrival OTP stripped from mobile payloads, SV-cum-CP closures, postpone/cancel sync, idempotent lifecycle routes, approval WhatsApp, CP cancel refused once client is on site.
- MMS-271 route `GET /api/marketing/cp-visits/staff-tracks` (the app remembers a 404 per session).
- SV reject reason stored as `cancelReason` (until deployed, prod writes "Cancelled via task dropdown").
- Off-duty tamper gate in `tamper.report`.
- Check per route before relying on anything else: Lens routes and the 18 Nov zeus batch were live on prod by 2026-11-19; the Mobile Issues queue commit is in prod git history but runtime is unverified.

### Waiting for other admin/team actions
- Geo prod deploy: snapped-route and the off-duty gate (both dev only).
- Security: prod geo runs with auth disabled (public tracking API); `GOOGLE_MAPS_SERVER_KEY` unset.
- Convex plan upgrade decision (16-concurrent-action cap).
- `LENS_API_KEY` was not set on prod as of 2026-11-18. Recheck before using Lens.
- 3D Layout Maps backend is on web dev only (PR #2700), so the prod picker shows 404 until a web prod deploy.

### On hold
- All MMS mobile SV work (user, 2026-11-26), including:
  - "Site Visits All is empty" for a VP-level test account. Read-only trace + 05 web probe done: server-side causes ruled out (account is super-admin with viewAll; the server returns SVs). The lead suspect is the app's offline cache replaying an empty copy after a prod 5xx. After clearance: re-open Site Visits on the phone (read-only) while prod is healthy. A debug build would trip device binding (different ANDROID_ID), so avoid it. Notes: `side-session-notes/sv-all-empty-investigation.md`, `sv-all-empty-05-web-probe.md`. Separate web bug, no task yet: Settings → Mobile → Issues / App Installs time out on prod ("too many system operations").. A web probe by 05 (Mobile Issues + IAM) was stopped.
  - MMS-269 phone test section B (creates prod records; also needs the user's OK).
  - MMS-263 stays In Progress until the on-phone SV/CP + GM acceptance test (user, 2026-11-24).
- MMS-269 section A items A1/A4/A5 need a field-staff login.

### Blocked / not started
- MMS-260 iOS GitHub Actions to TestFlight (FC 3d21f2d): native validation passes; upload needs six signing secrets added to GitHub.
- MMS-248: no implementation yet.
- The 21-Nov SV and Booking requirement PDFs: mostly not built anywhere (SV is on hold; booking is a teammate task).
- MMS-269 leftovers not built: S5 Booking Follow Up task, S8 out-of-geofence collection follow-up, S11 completed-CP setOutcome guard, CP list effective status, OTP attempt limit.

### Next steps
1. Wait for the user to clear the SV module hold; then resume the SV All investigation from the notes.
2. When the admin deploys Convex, verify each pending route on prod (token replay or 401-vs-404 probe on GET routes) and update this section.
3. At the next web task: merge origin/dev + origin/prod into zeus first.
4. Next Play release uses versionCode 112 from `Mconnect-release` at origin/main.
5. Port any applicable MMS change to Aivida through Main (one-way).

---

## 6. Key decisions (newest first)
- 2026-11-26: SV module on hold for MMS mobile until the user clears it.
- 2026-11-26: Check every flow before acting, even on the user's own decisions.
- 2026-11-26: 6-chat roster; 01 does all git, deploys and Cheetos; product chats never commit; only Main writes logs.
- 2026-11-26: Keep `pageSize` on `/api/sitevisits/my`; the unpaged admin path risks the 16 MiB read limit.
- 2026-11-26: Debug-build phone probes are avoided because of device binding (different ANDROID_ID).
- 2026-11-26: MMS-286 Today card: out time = latest mobile clock-out; a queued offline clock-out also shows.
- 2026-11-25: SV Approvals: one shared single-flight read for the badge and the screen; quiet busy retries 1.5/4/10/20 s.
- 2026-11-25: Selfie screen shows a short place name, display only.
- 2026-11-24: Root cause of CP tab 429s = prod Convex 16-action cap; app keeps cached lists, retries with backoff, alert poll 5 s -> 30 s, CP Visits loads once.
- 2026-11-24: CP trip map: trips open since an earlier day draw and count today's travel only.
- 2026-11-24: Off-duty tamper events are not recorded (geo + Convex gate), both MMS and Aivida.
- 2026-11-24: Geo route reads chunked per IST day (> 48 h ranges are rejected).
- 2026-11-23: SV reject reason is compulsory on mobile, using the shared reason sheets.
- 2026-11-23: 21-Nov SV/Booking PDFs are new requirements, not regressions; Aivida needs no SV/CP.
- 2026-11-21: MMS-263: outcome required before SV/CP completion; GM-hold lock kept.
- 2026-11-21: Chat polls only while visible, with 429/503 cooldown.
- 2026-11-19: Never use stock dialogs; shared confirm/reason sheets on both platforms.
- 2026-11-19: Mobile Issues fix verification moved to a queue (hot-document overload came from per-request verification writes).
- 2026-11-19: Attendance today/day-sessions reads are shared single-flight on both apps.
- 2026-11-18: Login OTP: server replies before SMS/WhatsApp delivery; app starts the SMS listener before sending.
- 2026-11-18: Geo batch insert uses ON CONFLICT DO NOTHING (one duplicate killed the whole batch).
- 2026-11-18: "Manage app if unused" row became "Keep app active"; only real restriction states redirect.
- 2026-11-18: Required-update gate persists and works offline.
- 2026-11-18: SV cancel blocked once the client is on site.
- 2026-11-18: Lens: keep photos on the profile when Lens is not configured; send later.
- 2026-11-17: Button sweep root cause: style backgroundTint recoloured android:background drawables; clock-in buttons restored to the original design.
- 2026-11-17: Convex capacity texts are replaced with "server is busy" wording in the apps.
- 2026-11-16: GeoTrack clock-in gate polls every 5 min (30 s polling caused timeout storms).
- 2026-11-16: Prefetch of permission-gated lists only for holders (stops 403 storms).
- 2026-11-14: Keep every location fix in a batch (not only the most accurate one).
- 2026-11-13: Floater capsule is the one notice UI for HTTP errors.
- 2026-11-13: iOS CP create failed on uppercase request ids; ids are lowercase now.
- 2026-11-06: Web pushes go to `zeus`, kept level with dev and prod.
- 2026-11-03: Clock-in slowness/500s came from an inline escalation-penalty scan in punch-in.
- 2026-10-28: Geo "reporting but Offline" fixed in the geo service (same-day ended session).
- 2026-10-27: After a gate punch-in, the app shows Clock Out with a gate-aware confirm.
- 2026-10-22: Attendance is local-first (punch shows at once, syncs later).
- 2026-10-08: SV-cum-CP with the client out of station sends a handoff to the GM instead of creating a CP.
- 2026-09-29: All commits as D-A-R-X.
- 2026-09-24: iOS Offline-after-5-min root cause = paused location updates.
- 2026-09-17: App is always pushed with prod hosts; pre-push hooks guard it.
- 2026-09-15: Android pushes go to main + merge.
- 2026-09-11: Joint CP owner = lower IAM level; legacy inverted rows need the admin repair tool.
- 2026-09-07: Mobile dialer work deferred until admin config is ready.
- 2026-09: Cold GeoTrack starts fail closed until attendance confirms an open session.
- 2026-09: A geo-host 401 must never log the user out of MMS.
- 2026-08-28: Every mobile change covers Android and iOS.
- 2026-08-17: Device binding by ANDROID_ID / Keychain UUID; reset severs live sessions.
- 2026-08-17: Offline clock-in must never block on GPS.
- 2026-08-17: CP completion: OTP mandatory, geofence not a hard block, outside geofence goes to GM approval.
- 2026-08-17: VP CP epic: new_client_cp/other_cp, referral outcome, follow-up caps, SV-cum-CP Cancel.
- 2026-08-01: Preserve known-good behaviour; SV/CP/fleet regressions are restored, not redesigned.
- 2026-07-20: Never deploy MMS Convex.
- 2026-07-16: VP dashboard gate fixed (it leaked to all admins via a phantom permission).
- 2026-07-10: Biometric gate punch-in counts as clocked in; only mobile clock-out ends the day.
- 2026-07-09: HR policy honours explicit 0.

---

## 7. Recent activity
Main appends one line per notable event; entries older than 7 days get folded into sections 5/6.

- 2026-11-26: 05 web probe (read-only, finished before the hold reached it): the SV All-empty cause is NOT server-side. The staff is super-admin with viewAll, and /api/sitevisits/my returns SVs (232 SV vs 268 CP over 5 pages). The cause points to the app side (cache/account/build). It is held under the SV-module hold. Separate web bug: Settings → Mobile → Issues / App Installs time out on prod ("too many system operations"); no task yet. Notes: sv-all-empty-05-web-probe.md.
- 2026-11-26: 06 lead suspect for SV All-empty: the offline cache replaying a stored empty response after a prod 5xx. Held. After clearance, step 1 is a read-only re-open of Site Visits on the phone while prod is healthy.
- 2026-11-26: Agent logs condensed by Main (MMS 16,864 → ~370 lines; Aivida 4,728 → ~300). Only 00 Main writes these logs now. Full history: _claude-setup/agent-log-archive/ (and git history for this file).
- 2026-11-26 (user: "Yes, with next push"): the condensed AGENT_LOG.md rides along with the next MMS Android push as its own docs commit (01). The push itself still needs the user's OK.
- 2026-11-26 (user: "handle all chats permission askings ... you approve it if youre sure about the flow and think same as me or else just wait for me to answer in here (main)"): chats send implementation questions to Main. Main answers routine in-scope ones when sure of the flow. Main/prod pushes, deploys, real records, phone installs/sign-ins, messages and deletes stay with the user.
- 2026-11-26 (user, Trip Details screenshot): the keyboard stays open after search + select in BOTH apps. 04 fixes Aivida and 06 fixes MMS (SV screens excluded while on hold), centrally in the components/helpers. Also: the "Call Client" style becomes the primary button component across the Aivida app (04); flat attendance buttons are kept unless they match. Tasks via 01.
- 2026-11-26 (user: "Yes, MMS too"): the Call Client style primary button component also goes into the MMS app (06, Android + iOS FC), after the keyboard fix. SV screens excluded; deliberately styled buttons are flagged before any change; the spec is shared with 04 via Main.
- 2026-11-26: MMS-288 created for 06 (keyboard stays open after search+select; Bug P2, #116, due 11-28, SV excluded).
- 2026-11-26: Main approved MMS-288 fixing the shared pickers centrally (iOS NativeSearchableSelectionSheet/AdvancedListFilterView, Android SearchableSelectionDialog). No SV files are edited, so the SV hold is respected; SV screens also get keyboard-closes-on-select. Told the user.

- 2026-11-26 (Codex disk cleanup): User requested help with nearly full C: drive (screenshot 3.07 GB free of 399 GB). Read project log and instructions; beginning read-only disk/cache inventory. No app, Git, or service changes; no deletions yet. Log remains local-only.
- 2026-11-26 (user): added DARX, a read-only logic-and-flows chat (plan mode; never edits, pushes or messages anyone; answers only the user; nobody routes to it). SESSION_SETUP.md updated for portability (rules, step-by-step rebuild, briefs, DARX). Memory backup refreshed (83 files).
- 2026-11-26 (Codex disk cleanup, inventory): C: initially 2.95 GB free; F: 328.82 GB free. Read-only sizes: Temp 9.91 GB (contains zeus worktrees; preserve), Downloads 4.24 GB, npm content cache 3.27 GB. Pagefile 38.33 GB with 17 GB in use; leave system settings alone. Development processes active, so preserve Gradle caches, SDKs and worktrees. Proceeding only with npm/pip download-cache files older than seven days; no personal files or project outputs.
- 2026-11-26 (Codex disk cleanup, completed): Deleted only npm _cacache files older than seven days (6,545 files, 3.25 GB) and pip download-cache files older than seven days (768 files, 0.28 GB), zero reported deletion failures. Exact cleanup total approximately 3.53 GB; latest C: free-space check 10.85 GB (other system activity also changed free space during investigation). Validated literal allowlisted roots, rejected reparse roots, skipped reparse descendants, checked every file stayed beneath its root. Preserved personal files, Temp worktrees, projects, SDKs, Gradle caches, pagefile and hibernation settings; no Git actions or app changes. Stopped only own two broad inventory processes after scans proved slow; inventory incomplete beyond stated paths. Temporary diagnostic scripts created and removed. No app build/tests needed. For lasting headroom, user can separately choose relocation to F: (328.82 GB available).
- 2026-11-26 (Codex disk cleanup follow-up): Explained relocation candidates: Gradle dependencies/caches, Android SDK/emulators, generated project build outputs, and potentially whole project folders. Distinguished these from the npm/pip download caches already cleared. Sizes for SDK/Gradle/projects remain unmeasured; no further deletion or relocation performed.
- 2026-11-27 (user: "Yes, stagger builds"): STANDING RULE: one heavy build/test at a time via _claude-setup/build.lock; gradlew --stop after. Memory + SESSION_SETUP updated, all chats told.
- 2026-11-27 (user-pasted plan from DARX): C: has about 3 GB free. Routed the cleanup to 01: git worktree remove of clean/pushed/In Review worktrees (others kept, 03/04 asked about theirs), emulator AVD + system images, old Gradle caches/dists + old Android Studio caches, WSL cache clean + vhdx compact (last; pauses iOS), and the CrossDevice folder after the user turns it off. Free space reported per step.
- 2026-11-27 (user): STANDING RULE: after a verified push, 01 removes the task worktree (git worktree remove, only if clean + pushed) and ff-only updates the main checkouts. Memory + SESSION_SETUP updated.
- 2026-11-27: 01 cleanup: C: 45.58 → 91.48 GB free. 48 worktrees removed (junctions unlinked first, shared node_modules intact), emulator + system images deleted. Gradle 9.3.1 kept (manju-customer-luffy uses it); 9.4.1 + old Android Studio caches queued behind the lock. CrossDevice skipped (user keeps it). WSL compact last.
- 2026-11-27: cleanup batch 2 done; C: 93.46 GB free (from 45.5). Main checkouts fast-forwarded (Mconnect-Aivida 8df920fb, Aivida-Airix-Ios 8df16ff, AividaAirix-web 256e2a8, cores, manjusitedevelopment origin/dev). NOT FF: Mconnect branch merge is ahead 11 / behind 21 of origin/merge (local commits look like cherry-pick twins). The old dirty Aivida-Airix-Ios-AX-263 (superseded by 54197b3) is kept. Both need a user decision; asked.
- 2026-11-27 (user: "Yes, backup + align"): 01 backs up Mconnect merge to backup/merge-local-2026-11-27, verifies all 11 local commits have origin twins (stops if not), then reset --keep to origin/merge, keeping the local edits incl. this condensed log (a safety copy is in the archive).
- 2026-11-27: the user gave DARX permission (in its chat, "you can have permission to send tasks to main session 00", "always") to send tasks to Main only; recorded in memory + SESSION_SETUP. DARX round-2 cleanup: 01 does the safe items (ybe-orgtype, devbase, queued round-1 caches/WSL). Gradle 9.3.1 + CrossDevice stay. Asked the user about removing the release worktrees, the patch-then-remove of the 4 dirty old worktrees, and FoundationChat-placename.
- 2026-11-27 (user: "Yes, remove them"): 01 removes Mconnect-release + Mconnect-Aivida-release (gitignored files backed up; re-add at the next release) and patch-then-removes mms-101, mms-263, FC-mms-264 and web dev-leads (patches in _claude-setup/patches, verified). FC-placename is removed after confirming its commit is on its branch.
- 2026-11-27: Mconnect merge align STOPPED at verify. 10/11 local commits have origin twins. The exception, 39e0f257 (versionCode 110 bump), is on origin/main + darx/main + backup/merge-local-2026-11-27, but not on origin/merge. Nothing reset. Asked the user: align anyway or leave.
- 2026-11-27 (user: "Yes, align anyway"): 01 aligns Mconnect merge to origin/merge (reset --keep; local edits incl. this log kept; the 110 bump is safe on main + the backup branch).
- 2026-11-27: round-2 cleanup done; C: 100.08 GB free (from 45.5). Patches for mms-101, mms-263, FC-mms-264 and web dev-leads are in _claude-setup/patches (verified). Release worktrees removed (gitignored files in _claude-setup/release-gitignored; release notes updated to re-add from origin/main). FC-placename removed (commit 100a9af on task/mms-280-ios-place-name). The align + old AX-263 approvals were relayed (queued). Caches → AX-263 xtool → WSL compact still queued.
- 2026-11-27: Mconnect merge aligned f58346bd → fab14a97 (= origin/merge) via reset --keep; the 4 local edits are byte-identical; backup/merge-local-2026-11-27 = f58346bd. Old Aivida-Airix-Ios-AX-263 patch-saved + removed. C: 100.10 GB free.
- 2026-11-27 10:27: build.lock was free with nothing running (stalled queue). Main set the order: 06 MMS-288 → 03 app tsc → 01 caches + AX-263 xtool → 04 AX-271/267/262/270 → WSL compact. Mconnect merge alignment verified by Main (fab14a97 = origin/merge, backup f58346bd).
- 2026-11-27: MMS-288 Android build done (650/650); the kbtest APK is installed on the OPPO next to the untouched Play app. The FC xtool compile is queued after 03. The phone test waits for the user to sign in to the kbtest app (DEV account).
- 2026-11-27 (user: "Signed in"): the kbtest app is signed in (DEV). 06 runs the MMS-288 phone test.
- 2026-11-27: memory crossdevice-never-delete. CrossDevice is Phone Link's live phone mirror (online-only placeholders, ~0 GB real); deleting removes files on the phone. Never delete it or count it.
- 2026-11-27: Gradle 9.4.1 + old Studio caches deleted (+5.04 GB, C: 105.00 GB free); 9.3.1 and CrossDevice kept. 04 took the lock for the AX-271 xtool. Order after: 01 AX-263 xtool → 06 FC xtool → 04 AX-267 → AX-262/270 → WSL compact.
- 2026-11-27: MMS-288 Android phone test PASSED (picker select/add, sheet, drag close the keyboard; typing keeps it); nothing saved; phone released to 04. Main approved adding the "no keyboard re-show on screen return" fix to MMS-288 + Aivida AX-266/267. The iOS FC xtool is queued.
- 2026-11-27: MMS-288 iOS xtool clean + Android 650/650 on PROD hosts (incl. the resume fix); kbtest rebuilt; 06 re-testing on the phone. Lock passed to 04 (AX-267 → AX-262/270).
- 2026-11-27: AX-267 follow-up approved: keep the keyboard open on multi-pick ticks in 6 spots (Forward, New Conversation, filter options, Invite Member), close on confirm/navigation/drag. Asked 06 to check MMS-288 and 04 to check AX-266 for the same multi-pick regression.
- 2026-11-27: MMS-288 DONE and handed to 01. The resume re-test PASSED (keyboard stays closed on return, text kept, tap reopens); Android 650/650 prod hosts, iOS xtool 0. Proof: mms-288-proof*.png. Phone with 04. 06 next: the Call Client button system (spec to Main first).
- 2026-11-27 (user): created 07 WhatsApp Config (local_205ba9f3), the user's chat for WhatsApp/Qikberry configuration across MMS + Aivida. Added to SESSION_SETUP and memory.
- 2026-11-27 (user: "Yes, push it"): relayed to 01 to push MMS-288 to main + merge (Android) and darx (FC), plus the condensed AGENT_LOG.md as a docs commit; then review, worktree cleanup and ff main checkouts.
- 2026-11-27: MMS-288 committed (Android 32de62a4 on its task branch; iOS 14eae11 local). The user's "Yes, push it" was re-sent to 01 (messages crossed). Behaviour notes for the user: SV pickers also close the keyboard after a pick; the chat composer returns with the keyboard down after camera/permission dialogs.
- 2026-11-27 (user: "if no leave or permission is left for a user to apply then show the button grey and show them unavailable for both mms and airix app"): routed to 06 (MMS) + 04 (Airix). Apply is disabled + "Unavailable" + reason when no balance; 0-balance leave types show Unavailable; never block while loading/failed or for unlimited/always-allowed types; the server still enforces. Tasks via 01.
