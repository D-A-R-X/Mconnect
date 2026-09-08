# Mobile Storage Read Resolver Backend Gaps

Date: 2026-09-08

This report covers the remaining server work found while implementing the
mobile storage upload/read handoff. No additional endpoint is required. The
existing resolver must return the documented result consistently.

## Endpoint

```http
GET https://mg.theairix.com/api/storage/files/{storageId}
```

The mobile clients now use this route for profile photos, chat attachments,
attendance and CP/SV photos, collections, loan documents/signatures, project
media, daily logs, and other stored-file previews.

The supplied mobile-storage handoff does not attach the MFPL bearer token to
this read URL. Access control must therefore be enforced by the resolver's
opaque ID/signed-redirect policy. This supersedes earlier local checks that
expected an unauthenticated read probe to return HTTP 401.

## Working behavior

The following non-mutating request returned a redirect:

```http
GET /api/storage/files/64ceeb75-bfb4-4ed7-aabb-ae5f4297190a
```

Observed first response:

```http
HTTP/1.1 307 Temporary Redirect
Location: https://api-mfpl.theairix.com/api/storage/serve?storageId=64ceeb75-bfb4-4ed7-aabb-ae5f4297190a
Cache-Control: private, max-age=60
```

This proves that the route exists and that redirect handling is reachable.

## Gap 1: compatibility marker returned instead of file bytes

Following the redirect returned:

```http
HTTP/1.1 200 OK
Content-Type: application/vnd.airix.external-storage-marker+json
```

```json
{
  "kind": "external-storage-marker",
  "version": 1,
  "fileName": "upload",
  "contentType": "image/jpeg",
  "sizeBytes": 79864,
  "purpose": "mobile.generic",
  "createdAt": 1788789626377
}
```

That JSON is compatibility metadata, not the requested JPEG. Native image
loaders therefore cannot display the file.

### Required server correction

1. Resolve the external-file mapping by the compatibility `storageId` before
   using the Convex fallback.
2. When the mapping is ready, return `307` to the short-lived external object
   URL whose response body is the original file bytes and whose content type is
   the original MIME type.
3. Use the Convex fallback only for a real historical Convex blob.
4. Never redirect a mobile read to an
   `application/vnd.airix.external-storage-marker+json` object.
5. Repair/backfill missing mappings for compatibility markers already created.

## Gap 2: unknown file returns HTTP 500

Observed:

```http
GET /api/storage/files/contract-probe
HTTP/1.1 500 Internal Server Error
```

The handoff requires `404` when neither external storage nor the enabled legacy
fallback has the file.

Expected response:

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
```

```json
{
  "success": false,
  "error": "File not found"
}
```

Do not convert a normal missing-file lookup into HTTP 500.

## Acceptance checks

```powershell
$env:MCONNECT_STORAGE_READ_ID='<known-image-storage-id>'
node scripts/check-mobile-api.mjs storage-contracts --storage-base-url https://mg.theairix.com/
```

The check must confirm:

- create, complete, and abort reject missing authentication with structured
  HTTP 401 responses;
- a known storage ID returns HTTP 307 with a non-empty `Location` header;
- following that location returns the original file MIME type and bytes, never
  compatibility-marker JSON;
- an unknown storage ID returns HTTP 404;
- legacy Convex files continue to load while fallback remains enabled.

No storage-service API key, MinIO credential, or S3 credential may be returned
to either mobile client.
