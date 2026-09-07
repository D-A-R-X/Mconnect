# Mconnect Google Map Search API Documentation

Status: Integration handoff only. This document does not implement or deploy
the API.

## 1. Recommended architecture

Android, iOS, and web should call authenticated Mconnect endpoints. The
Mconnect backend should call Google Places API New and Geocoding API.

```text
Android / iOS / Web
        |
        | Bearer token
        v
Mconnect address API
        |
        | Restricted server credential
        v
Google Places API New / Geocoding API
```

Do not place a Google web-service key in Android, iOS, JavaScript, a Git
repository, logs, or API responses. Google recommends a secure authenticated
proxy for client-side web-service calls when a suitable native SDK is not used.

Current Android already contains models for the recommended application-facing
routes:

- `GET /api/address/autocomplete`
- `GET /api/address/place`

An older public no-auth integration also exists at
`https://api-map-service.aivida.in`. The authenticated Mconnect proxy should be
the long-term source so access control, quota protection, telemetry, and one
cross-platform response contract are server-owned.

## 2. Google Cloud setup

Create or select one Google Cloud project with billing enabled, then enable:

1. Places API New for search suggestions and selected-place resolution.
2. Geocoding API for dropped-pin reverse geocoding and optional address-only
   fallback.
3. Maps SDK for Android, Maps SDK for iOS, and Maps JavaScript API only on the
   platforms that render an interactive Google map.

Use separate restricted keys for server, Android, iOS, and web rendering.

Server web-service key restrictions:

- Application restriction: backend egress IP addresses where practical.
- API restrictions: Places API New and Geocoding API only.
- Store in the server secret manager or protected environment configuration.
- Never return the key to mobile or web clients.

Native map-rendering keys:

- Android: restrict by application ID and signing certificate.
- iOS: restrict by bundle identifier.
- Web: restrict by authorized HTTPS referrers.
- Restrict each key to only its required SDK/API.

Official security guidance:
https://developers.google.com/maps/api-security-best-practices

## 3. End-to-end search flow

```text
User focuses address search
        |
Create UUID v4 session token
        |
Type at least 3 characters
        |
Debounce 300 to 400 ms
        |
GET Mconnect /api/address/autocomplete
        |
Show predictions and Google attribution
        |
User selects one prediction
        |
GET Mconnect /api/address/place with same token
        |
Populate address fields, latitude, longitude and map link
        |
Discard token and create a new token for the next search
```

One autocomplete session may contain multiple typed-query requests followed by
one selected-place details request. Google recommends a unique UUID v4 token
per session. The same token and Google Cloud project must be used for the
autocomplete and terminating details call. Do not reuse a completed token.

Official session-token documentation:
https://developers.google.com/maps/documentation/places/web-service/place-session-tokens

## 4. Mconnect autocomplete endpoint

### Request

```http
GET /api/address/autocomplete?q=mogappair%20east&sessionToken=<uuid>
Authorization: Bearer <mconnect-token>
```

Query parameters:

| Name | Required | Validation | Description |
| --- | --- | --- | --- |
| `q` | Yes | Trimmed, 3 to 200 characters | User-entered address or place text |
| `sessionToken` | Yes | UUID v4 | One token for one search-and-selection session |
| `lat` | No | -90 to 90 | Optional current/map-centre latitude for bias |
| `lng` | No | -180 to 180 | Optional current/map-centre longitude for bias |
| `radiusMeters` | No | Server-capped | Optional location-bias radius |
| `languageCode` | No | Supported BCP 47 code | Default `en` |

The server should apply India as the region filter using
`includedRegionCodes: ["in"]`. A location bias may prioritize nearby results,
but must not silently replace the explicit India restriction.

### Success response

```json
{
  "success": true,
  "suggestions": [
    {
      "placeId": "ChIJ...",
      "description": "Mogappair East, Chennai, Tamil Nadu, India",
      "mainText": "Mogappair East",
      "secondaryText": "Chennai, Tamil Nadu, India"
    }
  ]
}
```

Empty results are successful:

```json
{
  "success": true,
  "suggestions": []
}
```

### Error response

```json
{
  "success": false,
  "suggestions": [],
  "error": "Address search is temporarily unavailable",
  "code": "INVALID_QUERY"
}
```

Recommended codes:

- `INVALID_QUERY`
- `UNAUTHORIZED`
- `RATE_LIMITED`
- `GOOGLE_REQUEST_DENIED`
- `GOOGLE_QUOTA_EXCEEDED`
- `UPSTREAM_UNAVAILABLE`

Do not expose Google's API key or raw credential-bearing upstream URL in an
error.

## 5. Google Autocomplete API New call

The Mconnect backend maps the preceding request to:

```http
POST https://places.googleapis.com/v1/places:autocomplete
Content-Type: application/json
X-Goog-Api-Key: <server-key>
X-Goog-FieldMask: suggestions.placePrediction.placeId,suggestions.placePrediction.text,suggestions.placePrediction.structuredFormat
```

Example body:

```json
{
  "input": "mogappair east",
  "sessionToken": "4d2ca7d9-aec8-498f-880f-5306efaa40d8",
  "includedRegionCodes": ["in"],
  "languageCode": "en",
  "regionCode": "IN",
  "locationBias": {
    "circle": {
      "center": {
        "latitude": 13.0827,
        "longitude": 80.2707
      },
      "radius": 50000
    }
  }
}
```

Google endpoint documentation:
https://developers.google.com/maps/documentation/places/web-service/place-autocomplete

Mapping:

| Google field | Mconnect field |
| --- | --- |
| `placePrediction.placeId` | `placeId` |
| `placePrediction.text.text` | `description` |
| `placePrediction.structuredFormat.mainText.text` | `mainText` |
| `placePrediction.structuredFormat.secondaryText.text` | `secondaryText` |

Return place predictions only. Ignore query predictions because the Mconnect
form needs a resolvable location.

## 6. Mconnect selected-place endpoint

### Place ID request

```http
GET /api/address/place?placeId=ChIJ...&sessionToken=<same-uuid>
Authorization: Bearer <mconnect-token>
```

Exactly one of these input groups is required:

- `placeId` with the autocomplete `sessionToken`; or
- `lat` and `lng` for reverse geocoding a dropped pin.

### Success response

```json
{
  "success": true,
  "placeId": "ChIJ...",
  "lat": 13.08312,
  "lng": 80.17541,
  "formattedAddress": "Mogappair East, Chennai, Tamil Nadu 600037, India",
  "googleMapsLink": "https://www.google.com/maps/search/?api=1&query=13.08312%2C80.17541&query_place_id=ChIJ...",
  "components": {
    "doorNo": "GF1",
    "street": "Venugopal Street",
    "addressLine1": "Mogappair East",
    "addressLine2": "Near Mogappair East Bus Stop",
    "city": "Chennai",
    "state": "Tamil Nadu",
    "pincode": "600037"
  }
}
```

Fields not supplied by Google must be `null`, not invented. In particular,
landmarks, apartment numbers, floors, and door numbers often require user
confirmation or manual entry.

### Failure response

```json
{
  "success": false,
  "error": "The selected place could not be resolved",
  "code": "PLACE_NOT_FOUND"
}
```

Additional codes:

- `INVALID_PLACE_ID`
- `INVALID_COORDINATES`
- `PLACE_NOT_FOUND`
- `RATE_LIMITED`
- `GOOGLE_REQUEST_DENIED`
- `UPSTREAM_UNAVAILABLE`

## 7. Google Place Details API New call

For a selected prediction:

```http
GET https://places.googleapis.com/v1/places/<placeId>?sessionToken=<same-uuid>&languageCode=en&regionCode=IN
X-Goog-Api-Key: <server-key>
X-Goog-FieldMask: id,formattedAddress,location,addressComponents
```

The field mask is mandatory. Do not use `*` in production. The listed fields
cover Mconnect's address and coordinate requirements and are in the Place
Details Essentials data group. Requesting fields such as `displayName` or
`googleMapsUri` can move the request to a higher SKU, so the Mconnect proxy can
reuse the autocomplete display text and construct a Maps URL instead.

Official Place Details documentation:
https://developers.google.com/maps/documentation/places/web-service/place-details

Suggested address-component mapping:

| Google component type | Mconnect field |
| --- | --- |
| `street_number`, `premise`, `subpremise` | `doorNo` where reliable |
| `route` | `street` |
| `sublocality_level_1`, `sublocality`, `neighborhood` | `addressLine1` |
| `locality` or suitable district fallback | `city` |
| `administrative_area_level_1` | `state` |
| `postal_code` | `pincode` |

Address components vary by place and country. Parse by component type, never by
array position.

## 8. Reverse geocoding for a dropped pin

The Mconnect backend may use Geocoding API v3 for a pin location:

```http
GET https://maps.googleapis.com/maps/api/geocode/json?latlng=13.08312%2C80.17541&language=en&region=in&key=<server-key>
```

Use the first suitable street-address or premise result, preserve Google's
formatted address, parse components by type, and return the original requested
coordinates when appropriate. A result may not contain a house number or
pincode; keep missing fields editable in the client.

Official Geocoding request documentation:
https://developers.google.com/maps/documentation/geocoding/guides-v3/requests-geocoding

## 9. Google Maps link

Opening a selected place in Google Maps does not require an API key. Construct
a universal Maps URL:

```text
https://www.google.com/maps/search/?api=1&query=<lat>,<lng>&query_place_id=<placeId>
```

If no place ID exists, use the encoded coordinates as `query`. Use the
platform URL builder rather than manual string concatenation.

Official Maps URLs documentation:
https://developers.google.com/maps/documentation/urls/get-started

## 10. Client behavior

Android, iOS, and web should follow the same behavior:

1. Start a new UUID v4 session token when the field gains focus or the previous
   selection session ends.
2. Do not search before three trimmed characters.
3. Debounce typing by 300 to 400 ms and cancel stale in-flight calls.
4. Render at most five or six suggestions.
5. Preserve manual address entry when search is unavailable or no suggestion is
   selected.
6. Resolve the selected `placeId` before considering the location complete.
7. Fill only reliable fields and keep them editable.
8. Require finite latitude and longitude in their legal ranges.
9. Show a pin confirmation step for operational visits.
10. End and discard the session token after selection or abandonment.
11. Display Google attribution according to the Places API policy when
    predictions appear outside a Google-branded map.

Official Places policy and attribution requirements:
https://developers.google.com/maps/documentation/places/web-service/policies

## 11. Caching and data handling

- Do not cache autocomplete predictions as a permanent search index.
- Store the selected canonical business data required by Mconnect: place ID,
  formatted address, coordinates, normalized components, and map link.
- Follow current Google Maps Platform Terms for attribution, storage, refresh,
  and display of Places data.
- Log Mconnect request IDs, response status, latency, quota category, and
  redacted upstream error codes. Never log API keys or full bearer tokens.
- Rate-limit by authenticated user and organization, and cap query length and
  bias radius on the server.

## 12. Billing controls

Google Maps Platform is usage-billed. Use field masks and request only required
fields. Session tokens group autocomplete typing and place selection for billing,
but abandoned or reused sessions can be billed differently. Review live pricing
before rollout rather than hard-coding prices into product documentation.

Official billing references:

- https://developers.google.com/maps/documentation/places/web-service/usage-and-billing
- https://developers.google.com/maps/documentation/places/web-service/session-pricing

## 13. Required HTTP behavior

| Situation | HTTP status | Response behavior |
| --- | --- | --- |
| Valid request | 200 | `success=true` with normalized data |
| Valid request with no predictions | 200 | Empty suggestions array |
| Invalid query or coordinates | 400 | Stable validation code |
| Missing/expired Mconnect session | 401 | Existing session handling |
| Caller lacks feature access | 403 | Stable forbidden code |
| Mconnect rate limit | 429 | Retry guidance; no busy loop |
| Google quota/rate limit | 503 or 429 by policy | Sanitized upstream code |
| Google timeout/outage | 503 | Retryable error; preserve manual input |

Set a bounded upstream timeout and retry only idempotent GET/Google read calls
with jitter. Do not retry invalid requests or credential errors.

## 14. Acceptance checks

1. Searching a Chennai address returns relevant Indian predictions after the
   minimum input and debounce.
2. Selecting a prediction returns place ID, formatted address, coordinates, and
   typed components without a second user search.
3. Android, iOS, and web receive the same Mconnect response fields.
4. One UUID token is used from the first query through the selected-place call,
   then discarded.
5. An invalid or reused token cannot break address entry; billing telemetry
   identifies the session problem.
6. Dropping a pin returns a readable address while preserving exact coordinates.
7. Missing door number, landmark, or pincode remains editable and is never
   fabricated.
8. Old autocomplete responses cannot overwrite a newer query.
9. Manual entry remains available during upstream failure.
10. Google keys are absent from APK/IPA/web bundles, logs, Git history, and
    Mconnect API responses.
11. Key restrictions reject an unauthorized app, referrer, IP, or API.
12. Google attribution is visible wherever policy requires it.
13. Quota alarms, latency monitoring, and sanitized error telemetry are active
    before production rollout.

## 15. Official references

- Autocomplete API New:
  https://developers.google.com/maps/documentation/places/web-service/place-autocomplete
- Place Details API New:
  https://developers.google.com/maps/documentation/places/web-service/place-details
- Session tokens:
  https://developers.google.com/maps/documentation/places/web-service/place-session-tokens
- Geocoding API:
  https://developers.google.com/maps/documentation/geocoding/guides-v3/requests-geocoding
- Maps URLs:
  https://developers.google.com/maps/documentation/urls/get-started
- Places policies:
  https://developers.google.com/maps/documentation/places/web-service/policies
- API security:
  https://developers.google.com/maps/api-security-best-practices
- Usage and billing:
  https://developers.google.com/maps/documentation/places/web-service/usage-and-billing
