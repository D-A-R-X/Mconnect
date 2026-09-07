# Mobile Storage Migration: Deployment Blockers

Date checked: 2026-09-07

## Mobile implementation status

Android and iOS are prepared to use the documented external-storage flow:

1. `POST https://mg.theairix.com/api/storage/uploads`
2. `PUT <uploadUrl>` using only the returned required headers
3. `POST https://mg.theairix.com/api/storage/uploads/{fileId}/complete`
4. Best-effort `DELETE https://mg.theairix.com/api/storage/uploads/{fileId}` on a failed PUT

Both apps keep the returned `storageId` in all existing attendance, CP/SV,
chat, staff-document, project and post-sales business requests. Neither app
contains or calls the storage-service API key.

To avoid breaking currently working uploads before backend rollout, a `404` or
`503` from the create route falls back to the existing authenticated MMS
`POST /api/storage/upload` call. Other 4xx responses do not fall back.

## Missing production routes

The reusable checker currently reports:

| Route | Expected without bearer | Actual |
| --- | --- | --- |
| `POST /api/storage/uploads` | structured `401` | `404` |
| `POST /api/storage/uploads/{fileId}/complete` | structured `401` | `404` |
| `DELETE /api/storage/uploads/{fileId}` | structured `401` | `404` |
| `GET /api/storage/files/{storageId}` | structured `401` or documented redirect policy | `404` |

The preferred mobile flow cannot be enabled end to end until these handlers
are deployed on `mg.theairix.com`.

The same unauthenticated create-route probe was also run against
`https://api-mfpl.theairix.com/api/storage/uploads` on 2026-09-07 and returned
`404`. The route is therefore not available on either current production host;
changing only the mobile base URL cannot activate the migration.

## Critical compatibility-route finding

`POST https://mg.theairix.com/api/storage/upload` accepted an unauthenticated
empty JSON body and returned HTTP 200 with storage ID
`kg2dqjgxx23amm1rx6ery16kcd8dz063`. This was an unintended two-byte probe
object and is not attached to a business record. The response did not include a
`fileId`, and the documented delete route is absent, so the mobile team could
not safely remove it.

Before using this route for rollout, the backend must enforce:

- MFPL bearer authentication and current staff ownership
- allowed `X-Storage-Purpose` values
- per-purpose size limits, including the 10 MB attendance limit
- MIME allowlists and unconditional SVG rejection
- non-empty/valid file content where required
- a cleanup identifier or server-side cleanup for orphaned uploads

## Read migration

Existing mobile read/get-URL behavior remains unchanged for now because the new
resolver route returns `404`. Switching reads before deployment would break
historical images and documents. After the resolver is deployed and verified,
both apps should move reads to:

`GET https://mg.theairix.com/api/storage/files/{storageId}`

The client must follow the redirect and must not persist the redirected URL.

## Verification commands

From the Android repository root:

```powershell
node --check scripts/check-mobile-api.mjs
node scripts/check-mobile-api.mjs storage-contracts
node scripts/check-mobile-api.mjs contracts
```

After backend deployment, `storage-contracts` must pass all four unauthenticated
authorization-boundary checks. Then use authorized disposable QA data to verify
create, byte PUT, complete, business attachment, redirected read and abort. Do
not test the authenticated success path against a real staff record or real
business visit.
