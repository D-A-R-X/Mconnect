# Google Maps: backend key fix (web / Convex / geo service)

**For:** web and backend admin
**From:** mobile. No web or Convex file has been edited by mobile.
**Date:** 2026-09-17
**Scope:** server-side Google calls only. Android and iOS keys are handled separately.

---

## 1. Root cause

The browser key (`AIzaSyD8…`) is restricted to websites (`https://*.theairix.com/*`). Google only accepts it from a browser page on those sites. Server calls send no `Referer`, so Google rejects them:

```
REQUEST_DENIED — API keys with referer restrictions cannot be used with this API.
```

## 2. Do this first: the variable names in the request are not the ones the code reads

The request says to set `GOOGLE_MAPS_API_KEY`, `GOOGLE_ROUTES_API_KEY`, `GOOGLE_PLACES_API_KEY` and `GOOGLE_GEOCODING_API_KEY`. Checked against `origin/development-testing` (`f35f6f69`):

| Variable | Read by the code? |
|---|---|
| **`GOOGLE_MAPS_SERVER_KEY`** | **Yes. This is the main one, read first everywhere.** |
| `GOOGLE_MAPS_API_KEY` | Only as a fallback when `GOOGLE_MAPS_SERVER_KEY` is empty |
| `NEXT_PUBLIC_GOOGLE_MAPS_API_KEY` | Fallback, and the **browser** key: remove this (§4) |
| `GOOGLE_ROUTES_API_KEY` | **Not read anywhere** |
| `GOOGLE_PLACES_API_KEY` | **Not read anywhere** |
| `GOOGLE_GEOCODING_API_KEY` | **Not read anywhere** |

**Setting only the three new names changes nothing.** If `GOOGLE_MAPS_SERVER_KEY` still holds the browser key, every server call keeps failing.

> Also: this project is **Next.js + Convex**, not Vite. The browser variable is `NEXT_PUBLIC_GOOGLE_MAPS_WEB_KEY`, not `VITE_GOOGLE_MAPS_API_KEY`.

## 3. Environment values to set

The backend key is `AIzaSyD_hfq…`; the admin already has the full value. **Do not commit it.**

### 3.1 Convex deployment (serves `api-mfpl.theairix.com`)

Convex functions read their environment from the **Convex deployment**, not from Docker or `.env`. Set it there:

```bash
npx convex env set GOOGLE_MAPS_SERVER_KEY "<backend key>"
npx convex env set GOOGLE_MAPS_API_KEY "<backend key>"   # optional, same value, only so the fallback is also right
```

Existing deploy scripts already do this when `GOOGLE_MAPS_SERVER_KEY` is present in the deploy environment:
- `deploy.sh:112`
- `deploy.dev.sh:621`
- `deploy.new.sh:679`, `:1525`, `:1672`

Update the value in the deploy secret file / CI secret so a redeploy does not put the old value back.

Convex env changes take effect on the next function call. **No code deploy is needed for the key alone.**

### 3.2 Next.js server (the web container)

`app/api/map/reverse-geocode/route.ts:62` reads `GOOGLE_MAPS_SERVER_KEY || GOOGLE_MAPS_API_KEY` at runtime.
- Set `GOOGLE_MAPS_SERVER_KEY` in the container environment.
- `docker-compose.dev.yml:33` passes it through, but **`docker-compose.prod.yml` does not**. Add it there, then restart the container.

### 3.3 Geo tracking service (`api-geo.theairix.com`)

`internal/config/config.go:66-68` reads `GOOGLE_MAPS_SERVER_KEY`, then `GOOGLE_MAPS_API_KEY`, then `NEXT_PUBLIC_GOOGLE_MAPS_API_KEY`. `compose.prod.yaml:54` passes `GOOGLE_MAPS_SERVER_KEY`.
- Its route, geocode and place-search calls **work today**, so it already has a working server key.
- Point it at the same backend key for consistency.
- Restart the service after changing it.

### 3.4 Browser (unchanged)

```
NEXT_PUBLIC_GOOGLE_MAPS_WEB_KEY=<browser key AIzaSyD8…>
```

Never put the backend key in any `NEXT_PUBLIC_*` variable. Those are inlined into the browser bundle at build time (`Dockerfile:22,33`).

## 4. Code changes: remove the browser-key fallback

These server files fall back to `NEXT_PUBLIC_GOOGLE_MAPS_API_KEY`, the browser key name. On a misconfigured deploy they silently pick up a key that can never work server-side.

| File | Lines | Change |
|---|---|---|
| `convex/geotrack/geocoding.ts` | 7–9 | Drop `NEXT_PUBLIC_GOOGLE_MAPS_API_KEY` |
| `convex/geotrack/roads.ts` | 7–9 | same |
| `convex/hr/staffHomeGeocoding.ts` | 55–57 | same |
| `convex/trackingMaps.ts` | 29–31 | same |
| geo service `internal/config/config.go` | 68 | same; also update `config_test.go` and the comment in `.env.example:10` |

Keep `GOOGLE_MAPS_SERVER_KEY || GOOGLE_MAPS_API_KEY`. Where the key is missing, these already return a clear `"GOOGLE_MAPS_SERVER_KEY not configured"` error, so no new startup check is needed.

`convex/http.ts:13707` and `:13764` (place autocomplete and details for mobile) already read only `GOOGLE_MAPS_SERVER_KEY`. They need no code change, just the right value.

Do not add a `Referer` header to any backend request.

## 5. Backend key verified live (no `Referer`, 2026-09-17)

| Google API | Used by | Result |
|---|---|---|
| Geocoding, forward | `http.ts:13806`, `trackingMaps.ts:294` | `OK` |
| Geocoding, reverse | `geocoding.ts`, `staffHomeGeocoding.ts`, `reverse-geocode/route.ts` | `OK` |
| Places Autocomplete (legacy) | `http.ts:13722` | `OK` |
| Place Details (legacy) | `http.ts:13786` | `OK` |
| Places Text Search (New) | `trackingMaps.ts:238` | HTTP 200 |
| Routes `computeRoutes` | `trackingMaps.ts:56, 169` | HTTP 200 |
| Roads `snapToRoads` | `roads.ts:75, 309` | HTTP 200 |
| Distance Matrix | none today | `OK` |

**The key is good. Only where it is configured needs fixing.**

## 6. `/`, `/health`, `/healthz` returning 404

`api-mfpl.theairix.com` is the **Convex HTTP router**. `No matching routes found` is Convex's own reply for any path without a route, which proves Cloudflare reaches Convex correctly.
- **Nothing is broken.**
- A `/health` route is optional. The apps don't call one; the mobile app's reachability check is `GET /api/mobile/app-version`.
- If you want one for monitoring, add it to `convex/http.ts`. It needs a Convex deploy, which follows the normal release process.

## 7. Bundle check after the web rebuild

```bash
grep -rl "AIzaSyD_hfq" .next/static/ && echo "LEAK" || echo "backend key not in browser bundle"
```

This should print `backend key not in browser bundle`. Only the browser key (`AIzaSyD8…`) may appear there.

## 8. Checklist

- [ ] `GOOGLE_MAPS_SERVER_KEY` = backend key on the **Convex deployment** (`npx convex env set`).
- [ ] Same value in the deploy secret file / CI, so redeploys don't revert it.
- [ ] `GOOGLE_MAPS_SERVER_KEY` added to `docker-compose.prod.yml` and set in the web container; container restarted.
- [ ] Geo service `GOOGLE_MAPS_SERVER_KEY` = backend key; service restarted.
- [ ] `NEXT_PUBLIC_GOOGLE_MAPS_API_KEY` fallback removed from the 4 Convex files and the geo `config.go`.
- [ ] Web: map renders, address autocomplete works, pin reverse-geocoding works.
- [ ] Address search returns results: `GET /api/address/autocomplete` (`http.ts:13707`) and `GET /api/address/place` (`http.ts:13764`).
- [ ] Bundle check (§7) prints no leak.
- [ ] After everything works: **regenerate both keys**. Both full values were pasted in chat.
