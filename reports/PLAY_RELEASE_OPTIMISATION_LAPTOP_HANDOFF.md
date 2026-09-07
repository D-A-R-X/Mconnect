# Prompt for the laptop publishing Mconnect

Fix the Google Play Console warning "App optimisation is below our threshold - Obfuscation 1%" and prepare verified, signed Android release artifacts.

Repository: /Users/manojprabhakark/projects/Mconnect

## Current state and constraints

- Read this checkout's AGENTS.md and AGENT_LOG.md first. Maintain AGENT_LOG.md locally; never commit or push it.
- Preserve all existing working features, especially newly added Joint CP, authentication, attendance, GeoTrack, notifications, dialer, document scanner and saved drafts.
- Do not edit the website or iOS repositories for this task.
- The other Windows checkout fetched main and found it current at bb3bc4c3. That commit includes the Joint CP changes. Verify the current remote on this laptop; it may have advanced.
- The Windows R8 edits were NOT committed or pushed. Reapply the necessary changes here after inspecting current source. Do not assume a pull contains them.
- Windows release packaging failed because production Firebase configuration was absent. A subsequent compiler-only R8 check was stopped before completion when publication moved to this laptop. No optimised-release test pass, signed APK/AAB, signer check, or Play warning resolution was established there.
- The previous 146 passing debug tests predate these release changes. They are not evidence that the R8 release works.

## 1. Update safely

Inspect status, current branch, remotes and local changes. Fetch origin/main and integrate latest main without discarding any existing local work. Do not reset, clean, overwrite teammate edits or force-push. Resolve any relevant conflicts by preserving both intended behaviors. Record the exact source revision used.

## 2. Release configuration

Inspect app/build.gradle.kts and gradle/libs.versions.toml.

- Pin Android Gradle Plugin to exactly 9.0.1, which is supported by this laptop's Android Studio. Do not upgrade it to resolve unrelated warnings.
- In release buildType set isMinifyEnabled = true and isShrinkResources = true.
- Keep getDefaultProguardFile("proguard-android-optimize.txt") plus app/proguard-rules.pro.
- Keep applicationId = com.manjugroups.mconnect.
- Choose versionCode >= 70 AND greater than every already uploaded version. Inspect Play Console or the publishing records and any version override scripts. If 70 is already used, increase it. Do not assume the Gradle source value is the final packaged value.
- Keep existing versionName unless a change is required by the publishing process.
- Explicitly set these environment variables for the release build and verify the generated release BuildConfig:

  MCONNECT_BASE_URL=https://api-mfpl.theairix.com/
  MCONNECT_APP_URL=https://mg.theairix.com/

- Correct stale fallback URLs if present. Do not change the session invalidation boundary: only an authenticated authoritative MMS request returning 401 may clear the session. Secondary-host or public/login 401s must not sign staff out.
- Keep the release Firebase configuration guard. Use the real existing app/google-services.json or production Firebase build environment. Never bypass this guard for a distributable release or insert dummy settings.

## 3. Audit reflection before enabling R8

The app uses Retrofit/Gson and many model fields lack @SerializedName. Renaming those fields can break login and API requests only in release builds.

- Preserve generic signatures, inner/enclosing-class metadata, runtime method/parameter annotations and annotation defaults needed by Retrofit/Gson.
- Preserve Retrofit service interfaces and their HTTP methods. Inspect bundled Retrofit/Gson consumer rules as well as app rules.
- Preserve Gson field names and reflectively constructed models in network, auth and geotrack/data, including nested classes and Joint CP requests, responses and participant metadata.
- Audit Gson usage outside those packages: dialer recent calls, chat caches, daily-log drafts/attachments, loan document drafts, booking drafts and list caches. Protect their model fields/constructors too so existing saved drafts survive an update.
- Preserve Gson TypeToken subclasses and generic signatures.
- Preserve @JavascriptInterface methods used by the dialer WebView and any other JS bridges.
- Check enum serialization, reflected constructors, JNI and any resources resolved dynamically by name.
- Use targeted rules. Do not disable optimisation globally or keep the entire application merely to make the build pass. Do not silence all R8 warnings.
- Inspect missing_rules.txt if R8 fails; add only rules justified by the actual dependency usage. Do not blindly copy broad -dontwarn rules.
- Inspect the generated R8 mapping/configuration to confirm code is actually obfuscated while API field names and draft models remain protected. The final Play percentage must be measured after upload; do not promise a specific score in advance.

## 4. Android compatibility checks

Scan all app Kotlin source for removeFirst()/removeLast(). Replace mutable-list calls with equivalent guarded removeAt(0)/removeAt(lastIndex) without changing behavior. The Windows audit found one in CreateSiteVisitBottomSheet.kt's visitor-removal loop; its while condition already ensures the list is nonempty.

CRITICAL: the current manifest declares FOREGROUND_SERVICE_SPECIAL_USE for ModernDialerCallService, which calls startForeground during active calls. Removing just the permission or service type can crash calls on Android 14+.

The desired final manifest has no FOREGROUND_SERVICE_SPECIAL_USE. Achieve this only through a correct, tested service migration if the dialer still uses it. Inspect actual call architecture and Android's requirements. Do not merely relabel it phoneCall, microphone or dataSync, add unrelated permissions, remove the service, or downgrade the target SDK to bypass the requirement. If a suitable migration cannot be completed and device-tested without affecting working calls, report this requirement as unresolved and do not label the release ready. Preserve the location foreground service used by tracking and Joint CP.

## 5. Existing upload key only

Locate the Play upload keystore already used for Mconnect, its existing alias and credentials through the laptop's established signing configuration. Never print passwords or commit signing material. Do not generate a replacement key, use debug signing, reset the Play key, or change app signing ownership.

Expected APK signer SHA1, ignoring case and colon separators:

399860f234e9f1374e8ed42785697169a6fb7baf

Verify the key certificate before packaging. If it differs, stop and report the mismatch. Sign the APK and AAB with that same established upload key through the existing secure signing process. Preserve production Firebase and Maps configuration.

## 6. Build and test

Run SessionInvalidationPolicyTest explicitly, then the full unit suite. Run :app:assembleRelease, :app:bundleRelease and :app:lintRelease using AGP 9.0.1 and the production environment. Investigate failures without weakening release guards or disabling R8. Record actual results, not old reports.

Test the OPTIMISED release on test devices or an internal testing track; debug tests do not prove reflection works after R8. Cover:

- Login/session persistence and correct handling of secondary-host 401s.
- Ordinary CP create/list/OTP/photo/outcome, SV and attendance.
- Joint CP creation with both unique participant IDs, effective IAM template-role pairing, rejected same-template/same-level pairs and new-client category.
- Independent tracking for both Joint CP participants, strict under-50-metre validation, owner-only OTP/photo, Send Review, partner waiting/review/edit and final completion.
- Draft and cache restoration after upgrade.
- Incoming/outgoing dialer calls, two-way audio, notification actions, lock-screen/background behavior and app rotation.
- Scanner uploads and document preview.

Use authorised test records. Do not fabricate production attendance, visits or calls. If backend or device access prevents a scenario, list that scenario as unverified. Do not claim zero bugs.

## 7. Verify artifacts and deliver

- Inspect packaged APK and AAB manifests: applicationId, final versionCode, permissions and service declarations. Verify absence of FOREGROUND_SERVICE_SPECIAL_USE only if the migration is complete.
- Verify generated release BuildConfig and the build environment contain exactly the two required URLs; inspect packaged constants where tooling permits.
- Run Android SDK apksigner verify --verbose --print-certs on the final signed APK and compare SHA1 to the exact expected value.
- Run jarsigner -verify -verbose -certs on the signed AAB; confirm the bundle is signed, signatures are valid and signer matches the upload certificate. Distinguish certificate-chain/self-signed warnings from invalid or unsigned artifacts. Do not apply apksigner to an AAB.
- Confirm R8 outputs exist for this build, including mapping.txt/configuration.txt and bundle obfuscation metadata where supported. Keep mapping.txt for crash deobfuscation.
- Calculate SHA256 hashes of both final signed files.
- Only after these checks pass, copy signed files to this laptop's ~/Desktop using descriptive versioned names, plus the R8 mapping and a verification summary.
- Report artifact paths, source revision, AGP version, applicationId/version, URLs, signer SHA1, AAB verification, permission/source scans, tests and unresolved checks.
- Do not upload or publish to Play, change production configuration, or push source changes unless separately instructed by the user. Preparing signed artifacts is the authorised output here.

Google references:
- https://developer.android.com/topic/performance/app-optimization/enable-app-optimization
- https://developer.android.com/develop/background-work/services/fgs/service-types
