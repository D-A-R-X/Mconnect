# Storage File Read Authentication Gap

## Verification result

The non-mutating production CP/SV contract audit passed `111/112` checks on
2026-09-08. The only remaining failure was:

```http
GET https://mg.theairix.com/api/storage/files/contract-probe
```

Actual unauthenticated response:

```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/json
```

```json
{
  "success": false,
  "error": "failed to load file"
}
```

## Required correction

This existing route must validate authentication and authorization before
looking up or loading the requested storage object.

Expected response when the bearer token is missing or invalid:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json
```

```json
{
  "success": false,
  "error": "Unauthorized",
  "code": "UNAUTHORIZED"
}
```

For an authenticated caller who does not have permission to read the object,
return HTTP 403. For an authorized caller whose object does not exist, return
HTTP 404. A missing/invalid object must not be reported as HTTP 500.

No additional endpoint is needed; only the authentication and error ordering
of `GET /api/storage/files/:storageId` must be corrected.
