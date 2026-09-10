# CP Mobile Number Search Backend Fix

## Existing endpoint

```http
GET /api/marketing/clientPlaceVisits/my?search=<query>&scope=<mine|direct|all>
Authorization: Bearer <AIRIX_SESSION_TOKEN>
```

No additional endpoint is required. The existing endpoint must make mobile-number
search complete and return the same authorized CP membership as the normal list.

The checked backend route currently invokes `listMobileCompact`. Its phone-search
branch uses `search_text` alone, while `listPaginated` already demonstrates the
required merge with `by_mobileNumberNormalized`. Reuse that indexed behavior in
the compact route rather than adding a second public API.

## Required behavior

1. Detect a phone-like `search` value and normalize it to the local 10-digit
   number by removing spaces, punctuation, and an optional `91` country code.
2. For three or more digits, merge results from both:
   - the existing `search_text` search index;
   - the `by_mobileNumberNormalized` prefix index.
3. Apply the authenticated viewer scope after merging. A search must never
   broaden `mine`, `direct`, or company permissions.
4. Return the stored `mobileNumberNormalized` value in every compact visit row,
   in addition to resolved `lead.mobileNumber`, `client.mobileNumber`, and
   `clientPlace.contactPhone` where available.
5. Backfill missing `searchText` and `mobileNumberNormalized` snapshots for
   legacy CP rows using the same resolver used during create/update.
6. Keep pagination stable, de-duplicate by CP visit ID, and do not restrict a
   phone search to only the newest default page.

## Response shape

```json
{
  "success": true,
  "total": 1,
  "visits": [
    {
      "_id": "<clientPlaceVisitId>",
      "mobileNumberNormalized": "9840032837",
      "lead": { "mobileNumber": "9840032837" },
      "client": { "mobileNumber": "9840032837" },
      "clientPlace": { "contactPhone": "9840032837" }
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

Joined objects may be null for legacy rows; `mobileNumberNormalized` is the
stable fallback mobile needs to display and retain the search result.

## Acceptance checks

- Full number, `+91` number, spaces, and hyphens return the same authorized CP.
- A prefix of at least three digits finds rows through the normalized index.
- A legacy row with missing lead/client links is still found from its snapshot.
- Joint CP is returned to either authorized participant.
- Unauthorized staff receive no result for the same phone.
- Search results are not limited to the newest 10 or 200 CP records.

## Connectivity observation

On 10 September 2026, an unauthenticated read-only request with a dummy phone
query returned the expected JSON HTTP 401 response (`success`, `error`) in about
23.5 seconds. The route is reachable and protected, but this response time is a
material latency issue that should be investigated independently of the search
index correction.
