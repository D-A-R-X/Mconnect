# Site-visit list shows "—" for client name and phone

**For:** web / Convex admin
**From:** mobile
**Date:** 2026-09-19
**Where the bug is:** backend (Convex), not the app.

## Symptom

On the Site Visits list, the card for BLUE SPOT 3.0 (19 Sep, 15:30, BDO EZHUMALAI.S, LMO RANJITHA.M) shows **"—"** for the client and **"—"** for the phone. The same visit's detail sheet shows **Vijay · 9962299926**.

## Cause

The two screens read two different backend responses.

| Screen | Endpoint | How the client is resolved |
|---|---|---|
| Detail sheet | SV detail (`enrich… detail`, `convex/marketing/siteVisits.ts` ~L660–740) | `lead` → **`directClient` (`visit.clientId`)** → client from the CP → client matched by phone |
| List card | `GET /api/sitevisits/my` → `enrichSiteVisitForMobile` (~L3634–3720) | `lead.contactName` → the source CP's client / place → client matched by phone |

The list mapper **never reads the SV's own `clientId`**, nor the lead's `manualProfile.clientName`. An SV created directly by a BDO, with no telecaller lead and no source CP, stores its client only in `clientId`. So the list row goes out with `leadName: null, leadPhone: null`, and the app correctly shows "—".

The app has nothing better to fall back on: the list row carries only `leadName` / `leadPhone`, with no client or attendee fields.

## Fix (Convex, `enrichSiteVisitForMobile`)

Add the SV's direct client (and the lead's manual profile name) to the chain, before the source-CP fallback:

```ts
let leadName: string | null =
  orNull(lead?.contactName) ?? orNull(lead?.manualProfile?.clientName);
let leadPhone: string | null = orNull(lead?.mobileNumber);

// The SV's own client (set when a BDO creates the visit directly). The detail
// endpoint already uses this as `directClient`; the list did not.
if ((!leadName || !leadPhone) && sv.clientId) {
  const directClient = await ctx.db.get(sv.clientId);
  leadName = leadName ?? orNull(directClient?.clientName);
  leadPhone = leadPhone ?? orNull(directClient?.mobileNumber);
}

if ((!leadName || !leadPhone) && sourceCp) {
  /* existing CP client / place fallback, unchanged */
}
/* existing phone → clients-master match, unchanged */
```

This is one extra indexed `db.get`, only for rows still missing a name or phone.

Optional last resort: if the SV has `attendees`, use the attendee marked as self (`relation === "self"`) for the name.

## Verify

Open Site Visits in the app (Android or iOS) after deploy. The BLUE SPOT 3.0 card on 19 Sep should read **Vijay** / **9962299926**, matching its detail sheet. No app change is needed.
