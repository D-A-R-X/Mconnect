# Site visit: outcome without a QR scan (server-side loopholes)

**For:** web/Convex admin
**From:** mobile. No web or Convex file has been edited by mobile.
**Date:** 2026-09-17
**Code checked:** MMS `development-testing` @ `f35f6f69`

---

## What was reported

A DSV reached **On Counselling** without the client's QR being scanned. The web showed it active with the outcome enabled.

## Cause and what mobile fixed

The Android app did it, in two steps:

1. The SV overview unlocked the outcome buttons at **On Site** (and when a cab reached the site), before any QR scan.
2. Saving an outcome from there first called `POST /api/marketing/siteVisits/markOnCounselling` itself, to get past `setOutcome`'s transition check. If the save then failed or was abandoned, the visit stayed **On Counselling** with no outcome, which is what the web showed.

**Fixed in the app:**
- The outcome is available only from `on_counselling` / `picked_from_site` / `dropped` / `completed`.
- The outcome form never calls `markOnCounselling`. The only caller is now the QR scanner after "Start counselling".
- If the server refuses, staff see "Scan the client's QR code to start counselling before recording the outcome."

**iOS already behaved correctly** (outcome from `on_counselling` onward; only the QR scanner advances the status).

**The normal QR flow is unchanged:** scan → confirm → `markOnCounselling` → outcome.

Old app builds still have the bypass until staff update. **The server should enforce the rule itself**, so no client can skip the scan.

---

## 1. The server cannot tell whether a QR was scanned

`POST /api/marketing/siteVisits/markOnCounselling` already runs `markOnCounsellingFromQr` with `requesterStaffId: auth.user._id`, which checks `canStartQrCounselling` (assigned BDO / Site Incharge / admin / authorised staff). It accepts `scheduled`, `client_started`, `picked_up` and `on_site`.

But `POST /api/marketing/siteVisits/scanQr` is a **read-only lookup** (`getByQrPayload`). It records nothing. So "counselling started from a QR" is only a naming convention: any authorised caller can start counselling without scanning. That includes every installed app build with the old outcome form, from `scheduled` onward.

**Recommended:** make the scan a fact the start depends on.

1. In `scanQr`, after resolving the visit for an authorised requester, stamp:
   ```ts
   qrScannedAt: now,
   qrScannedByStaffId: auth.user._id,
   ```
   `scanQr` is a query route today, so do this in a small mutation.
2. In `markOnCounsellingFromQr`, require a recent scan:
   ```ts
   if (!visit.qrScannedAt || now - visit.qrScannedAt > 15 * 60_000) {
     throw new Error("Scan the client's QR code to start counselling.");
   }
   ```

The mobile QR flow already calls `scanQr` immediately before `markOnCounselling`, on both Android and iOS, so it keeps working with no app change.

## 2. The outcome can be reached without counselling via the return leg

`setOutcome` accepts `on_counselling`, `picked_from_site`, `dropped` (and `completed`). But:

```ts
markPickedFromSite  assertTransition(visit.status, ["on_site", "on_counselling"], …)                     // :5953
markDropped         assertTransition(visit.status, ["on_site", "on_counselling", "picked_from_site"], …) // :5972
```

A driver marking **Picked From Site** or **Dropped** straight from `on_site` moves the visit into a status where `setOutcome` is allowed, **with no QR scan**.

**Decide:**
- If counselling is mandatory for every SV, remove `on_site` from both lists.
- If a cab can legitimately leave without counselling (client did not go in), keep the transition but don't let `setOutcome` accept `picked_from_site` / `dropped` unless `consultingAt` is set. `consultingAt` is stamped only by the two counselling mutations.

```ts
// in setOutcome, after assertTransition
if (!visit.consultingAt && ["picked_from_site", "dropped"].includes(visit.status) && !visit.completedOffline) {
  throw new Error("Scan the client's QR code to start counselling before recording the outcome.");
}
```

Keep the `completed` / `completedOffline` path as is. The offline fleet completion has no scan by design.

## 3. `convertToBooking` has no counselling check

`convertToBooking` only rejects `cancelled` / `no_show`. A booking can be created from `scheduled` or `on_site` without a QR scan.

**Recommended:** apply the same gate as `setOutcome`: require `on_counselling` / `picked_from_site` / `dropped` / `completed`, and `consultingAt` unless `completedOffline`.

---

## The visit that is already stuck

Rows that reached `on_counselling` through the old app path have `consultingAt` set but no scan behind them. Either record the outcome now, or reset the row to `on_site` (clear `consultingAt`) and rescan.

To list candidates:

```ts
// on_counselling, no outcome, consultingAt set, and no scan record
// (only possible to tell apart once §1 records scans)
```

---

## Checklist

- [ ] §1: `scanQr` records the scan; `markOnCounsellingFromQr` requires a recent one.
- [ ] §2: `setOutcome` refuses `picked_from_site` / `dropped` without `consultingAt` (except `completedOffline`), or those transitions no longer accept `on_site`.
- [ ] §3: `convertToBooking` has the same counselling gate.
- [ ] Verify: QR scan → Start counselling → outcome still works end to end.
- [ ] Fix the stuck DSV row.
