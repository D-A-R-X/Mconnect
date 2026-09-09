# GeoTrack Map Helper Authentication Fix

Date: 09 Sep 2026

## Problem

The mobile trip screen needs road geometry from the direct GeoTrack service.
The Android app previously drew a direct origin-to-destination line immediately
and left it on screen when the route request failed. That fallback looked like a
real route even though it did not follow roads.

The client-side fallback has been removed. Mobile now draws a line only after a
valid encoded road polyline is returned and decoded.

## Production Result

Using the bearer from the user-approved emulator login:

```text
GET  https://api-mfpl.theairix.com/api/auth/geotrack-access -> 200 success=true
GET  https://api-geo.theairix.com/api/tracking/sessions/current -> 200 success=true
POST https://api-geo.theairix.com/api/geotrack/route -> 401
POST https://api-geo.theairix.com/api/geotrack/geocode-address -> 401
GET  https://api-geo.theairix.com/api/tracking/places/search -> 401
```

All three failed map-helper responses returned:

```json
{
  "success": false,
  "error": "invalid credentials"
}
```

The bearer was never written to this report or printed. The test did not create
or modify a visit, route, tracking session, or business record.

## Existing Endpoints To Fix

No new endpoint is required. Fix bearer validation on these direct GeoTrack
routes so they use the same existing-mobile-session validation as the other
GeoTrack routes:

```text
POST /api/geotrack/route
POST /api/geotrack/geocode-address
GET  /api/tracking/places/search?q={query}
```

Required header:

```http
Authorization: Bearer <existing mobile session token>
```

Do not require a Google key, service secret, or `X-API-Key` from the mobile app.
GeoTrack must keep provider credentials server-side.

## Route Contract

Request:

```json
{
  "originLat": 13.067439,
  "originLng": 80.237617,
  "destLat": 13.1049,
  "destLng": 80.2367
}
```

Successful response:

```json
{
  "success": true,
  "encodedPolyline": "<Google encoded road polyline>",
  "distanceMeters": 5200,
  "durationSeconds": 900
}
```

Requirements:

1. `encodedPolyline` must contain the complete road geometry, not only the two
   request coordinates.
2. `distanceMeters` and `durationSeconds` must be positive for distinct points.
3. Invalid or expired mobile sessions return structured HTTP 401.
4. A valid MMS mobile session must be accepted through the documented
   `/api/auth/geotrack-access` validation path.
5. Provider failures return a distinct upstream code such as
   `ROUTE_PROVIDER_UNAVAILABLE`, not `invalid credentials` for a valid user.

## Verification

After deployment, run:

```text
MCONNECT_TOKEN=<valid-test-session> node scripts/check-mobile-api.mjs geotrack-map-contracts
```

The check is read-only/non-mutating. It validates authentication, route
geometry, distance, duration, geocoding, and place search.
