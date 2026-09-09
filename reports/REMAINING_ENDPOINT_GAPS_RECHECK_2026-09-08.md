# Remaining Endpoint Gaps Recheck - 2026-09-08

This is a new gaps-only report. Existing endpoint documents were not modified.

## No New Route Is Required

All previously missing login, device recovery, bulk device reset, Joint CP,
completion count, completion repair, CP/SV, booking and collection routes are
deployed and pass their non-mutating route/auth contracts.

## Existing Storage Read Route Still Requires Correction

Endpoint:

```http
GET https://mg.theairix.com/api/storage/files/{storageId}
```

Two production behaviors remain incorrect:

1. A known compatibility storage ID redirects to
   `https://api-mfpl.theairix.com/api/storage/serve?...`, but that target now
   returns HTTP 404 instead of the original image bytes.
2. An unknown storage ID returns HTTP 500 with
   `{"success":false,"error":"failed to load file"}` instead of a structured
   HTTP 404.

Required backend behavior:

- resolve legacy compatibility markers to the external object mapping;
- redirect known IDs to a short-lived URL that returns the original bytes and
  original MIME type;
- return JSON HTTP 404 for a genuinely missing ID;
- never return a compatibility-marker JSON document to an Android/iOS image
  loader;
- backfill old compatibility IDs whose external mapping is missing;
- preserve the current create, complete and abort upload contracts.

Acceptance command:

```powershell
$env:MCONNECT_STORAGE_READ_ID='<known-image-storage-id>'
node scripts/check-mobile-api.mjs storage-contracts --storage-base-url https://mg.theairix.com/
```

Acceptance requires all checks to pass and a direct follow of the known-ID
redirect to return image bytes with an image MIME type.
