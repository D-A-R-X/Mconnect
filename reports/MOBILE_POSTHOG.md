# Mobile Diagnostics: PostHog

MMS-253 (Android) and MMS-254 (iOS) replace the existing automatic issue
reporter transport. Updated apps send directly to the US PostHog `MMS Mobile`
project (750346), not to `/api/mobile/issues/report`. No web/Convex code or
business endpoint behavior changes. Older released apps keep their old behavior.

## Scope and Privacy

- `mobile_endpoint`: normalized operation, service bucket, HTTP status/network
  category, response latency, outcome, app platform/version/build and OS version.
- `$exception`: failed app-flow category and code screen, or unhandled crash
  type. Android includes bounded code-only stack frames without error messages.
  iOS keeps existing MetricKit/Objective-C exception hooks; native reports are
  delayed summaries, not a new fully symbolicated crash-reporting SDK.
- Random installation UUID, no staff ID or person profile. GeoIP is disabled.
  Keep PostHog Project > Privacy > Discard client IP data enabled: disabling
  GeoIP enrichment alone does not prevent PostHog from retaining client IPs.
  The project setting applies to future Android and iOS events, not old data.
  No request/response bodies, headers, query values, raw URLs, error/notice text,
  staff names, phones, photos, precise location, or free-text breadcrumbs.
- Unknown path segments become `:id`; only known app hosts are monitored. The
  dedicated PostHog client has no MMS auth, cookies, app headers or interceptors.
- Legacy unsent diagnostics are discarded, not forwarded to a new third party.
  Attendance queues, sessions, tracking and visit data are never cleared.

## Resource Limits

- Only observes requests the app already makes. No probes, endpoint replay,
  verification requests, SDK remote configuration/feature flags, session replay,
  screen autocapture, or recurring jobs.
- Healthy responses: one in twenty observations. Responses over three seconds
  and failures are eligible without that sampling, still subject to limits.
- Persisted per-installation UTC-day budgets: 20 timing events and 40 errors,
  including at most 8 `$exception` events. Duplicate signatures suppressed while
  queued and for ten minutes after admission. These are bounded diagnostic
  signals, NOT an unsampled traffic/SLA/availability counter.
- Max 60 persisted events, 20 per batch, three-day expiry. Bounded 64-item
  observation ingress drops telemetry instead of blocking app requests/UI.
- Batches no more than once a minute, only with pending events. No foreground
  service or wake lock. OS suspension can defer delivery; immediate delivery is
  not promised. Android sends on its utility worker, iOS uses an isolated session.
- Failure backoff starts at ten minutes and caps at six hours; numeric Retry-After
  honored up to that cap. Failures await a later real observation/resume rather
  than running a retry poll. Redirects and automatic transport retries disabled.
- At 332 installations, daily maxima imply at most 617,520 total events and
  82,336 exceptions in a 31-day month. Additional installations/reinstalls need
  quota review. The capped Free plan can drop data once its allowance is reached.

## Review and Rollout

Capture configuration is mobile-only: `PostHogConfig.kt`. Its `phc_` token is a
public write-only project token, NOT a personal/administration key. The compile-time
`ENABLED` switch can disable reporting without introducing server configuration
polls. iOS has matching `MobilePostHogConfig.swift`.

Android coverage: shared MMS, GeoTrack, daily-log, travel-desk, mobile-storage and
telecaller clients. Signed third-party media hosts are intentionally not inspected.
iOS uses the existing shared request hooks plus its default GeoTrack session;
injected test sessions and request/result semantics stay unchanged.

Existing web endpoint-verification middleware and app build headers are untouched.
This is not a replacement for their server-side business behavior. Web Mobile
Issues does not receive new diagnostics from these builds; use PostHog Activity,
Error Tracking and analytics grouped by endpoint/platform/build. Filter synthetic
validation data out using `verification = true`.

Before public rollout: native Android device/battery comparison, Mac archive and
iPhone smoke/crash checks, appropriate privacy/store data disclosures and Manoj's
review. No production or store release is implied by a successful local build.

## Explicit Handset Verification

`MobileTelemetryDeviceTest` is skipped unless instrumentation receives
`-e posthogVerification true`. It enqueues one labelled, handled synthetic
exception through the real persisted queue and waits for its normal batch
acknowledgement. It does not crash the app, force a flush, or call a business
endpoint. Run only on an authorized debug handset with no active duty/tracking;
the instrumentation runner can stop the app process. Separately verify the
run marker in PostHog Activity/Error Tracking and check that no IP is retained.

References:
- https://posthog.com/docs/api/capture
- https://posthog.com/docs/error-tracking/installation/manual
- https://posthog.com/docs/privacy/data-storage
