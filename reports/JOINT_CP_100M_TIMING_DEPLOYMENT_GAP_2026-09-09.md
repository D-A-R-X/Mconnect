# Joint CP 100 m and timing deployment gap

Date: 09 Sep 2026

## Result

No new endpoint is required. The existing production routes are reachable, but the replacement Joint CP backend implementation is only partially live.

## Confirmed live

An authenticated, read-only call to:

```http
GET /api/marketing/clientPlaceVisits/joint-workflow?id={existingJointCpId}
Authorization: Bearer <session-token>
```

returned HTTP 200 and included all required explicit workflow fields:

- `actorReady`
- `canRequestOtp`
- `canReview`
- `canCompleteReview`
- `requiredRadiusMeters`
- `state`
- `actorRole`

This confirms the explicit-readiness response needed to avoid the old waiting-for-partner deadlock is deployed.

## Still missing in production

The same successful response returned:

```json
{
  "requiredRadiusMeters": 50
}
```

The replacement backend handoff requires a strict-under-100-metre radius. Production must return:

```json
{
  "requiredRadiusMeters": 100
}
```

Production also omitted both promised timing headers from an authenticated HTTP 200 workflow response and an authenticated HTTP 400 response:

```http
Server-Timing: auth;dur=..., db;dur=..., serialization;dur=..., total;dur=...
X-Api-Trace-Total-Ms: ...
```

## Latency observed

- `GET /api/health`: HTTP 200 with approximately 20.7 seconds to first byte.
- Serial invalid-bearer Joint workflow: HTTP 401 with approximately 41.5 seconds to first byte.
- Final concurrent 16-route CP/Joint CP contract sweep: 16/16 routes passed, but each response took approximately 14.6-14.8 seconds.
- Earlier 20-second and 60-second sweeps received no response before timeout.

TCP and TLS completed in under 0.3 seconds during the health probe. Most delay occurred after the secure connection was established, so this is server/upstream processing or capacity latency rather than mobile DNS/TLS setup.

## Required admin action

1. Deploy the exact revision described by `joint-cp-proximity-api-latency-backend-fix-2026-09-09 (1).md`.
2. Confirm `JOINT_CP_COMPLETION_RADIUS_METERS` or the equivalent shared constant is 100 and is imported by both workflow enforcement and HTTP error metadata.
3. Confirm successful and business-error responses retain `Server-Timing` and `X-Api-Trace-Total-Ms` through the production proxy/CDN.
4. Re-run an authenticated workflow read and verify `requiredRadiusMeters: 100`.
5. Run the disposable two-account Joint CP check and verify readiness, preflight, OTP ownership, outcome handoff, reviewer completion, idempotent retry and one completed credit for both staff.

## Mobile rollout safety

Android and iOS now use the radius returned by the workflow response for displayed guidance, with 100 metres as the fallback. This prevents the app from showing 100 metres while a partially deployed server still enforces 50, and it automatically displays 100 after the backend rollout completes.
