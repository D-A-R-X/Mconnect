# Joint CP Proximity And API Latency Backend Fix

Date: 09 Sep 2026

## Summary

The Android and iOS clients now remove the redundant pre-create duplicate read,
request a fresh high-accuracy location for every Joint CP proximity decision,
refresh the reviewer's readiness immediately before completion, and tolerate a
slow response window for CP creation, OTP, outcome, upload, and completion.

Production timing checks show that the remaining general slowness is on the API
host or its upstream services. DNS, TCP, and TLS complete in less than 0.2
seconds, but the server can take approximately 8 to 30 seconds before returning
the first response byte.

## Existing Endpoints To Fix

No new mobile endpoint is required. Optimize and retain these existing routes:

```text
POST /api/marketing/clientPlaceVisits/create
POST /api/geotrack/visit/start
POST /api/geotrack/visit/arrival-otp/request
POST /api/geotrack/visit/arrival-otp/verify
POST /api/storage/upload
POST /api/marketing/clientPlaceVisits/markClientMet
POST /api/marketing/clientPlaceVisits/setOutcome
GET  /api/marketing/clientPlaceVisits/joint-workflow?id={cpVisitId}
POST /api/marketing/clientPlaceVisits/joint-arrival-preflight
POST /api/marketing/clientPlaceVisits/joint-participant-ready
POST /api/marketing/clientPlaceVisits/joint-submit-review
POST /api/marketing/clientPlaceVisits/joint-complete-review
POST /api/geotrack/visit/complete
GET  /api/marketing/clientPlaceVisits/completed-count?date={activityDate}&staffId={staffId}
```

## Required Proximity Behavior

1. Store the authenticated participant's latest location, accuracy, and capture
   time on `joint-arrival-preflight`, `joint-participant-ready`, and
   `joint-submit-review`.
2. Compare only fresh participant samples. Reject a stale sample with a stable
   code such as `PARTNER_LOCATION_STALE`; do not report it as a 50-metre breach.
3. Reject an inaccurate sample with a stable code such as
   `LOCATION_ACCURACY_LOW`; do not report it as a 50-metre breach.
4. When both samples are fresh and accurate, calculate separation with a
   Haversine/geodesic distance and enforce the configured 50-metre radius.
5. Return the latest workflow state and `outcomeRevision` from every readiness
   mutation so the reviewer can complete without a stale revision.
6. `joint-complete-review` must atomically complete the parent CP, both
   participant field visits, and both participants' completed-count records.
7. Idempotent retries must return the same completed result and must not create
   duplicate count entries.

Suggested location quality contract:

```json
{
  "success": false,
  "error": "A fresh accurate location is required from both staff",
  "code": "LOCATION_ACCURACY_LOW",
  "requiredRadiusMeters": 50,
  "maximumAccuracyMeters": 30
}
```

## Required Performance Behavior

1. Authentication rejection must happen before expensive database, proxy, or
   storage work.
2. Remove sequential internal fetches where independent reads can run in
   parallel, and avoid loading full staff/visit documents when a compact
   projection is sufficient.
3. Add per-route tracing for authentication, database reads/writes, external
   calls, serialization, and total response time.
4. Target a p95 time-to-first-byte below 2 seconds for the workflow above.
5. Keep the deployed implementations for the listed routes in the tracked
   backend repository. The currently inspected source does not contain the
   deployed HTTP handlers for all Joint CP readiness/completion routes, which
   prevents code-level verification of the production distance calculation.

## Verification Required After Deployment

Run the mobile endpoint checker, then execute an authorized two-account test:

1. Create one disposable Joint CP with different designation levels.
2. Start each participant's own field visit.
3. Confirm OTP/photo/outcome are available only to the lower designation.
4. Confirm the higher designation sees the submitted outcome, can edit it, adds
   a remark, and completes only when both fresh accurate samples are within 50m.
5. Confirm both field visits and the parent CP are completed and each staff
   receives exactly one completed count on the activity start date.
6. Retry the final request with the same idempotency key and confirm no duplicate
   completion or count is produced.

Do not use real staff/client visits for this deployment test. Use an approved
disposable fixture and clean it up through an audited admin operation.
