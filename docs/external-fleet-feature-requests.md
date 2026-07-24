# External Fleet — Feature Requests & Requirements

> **Source:** Raw requirements gathered from the External Fleet Admin.
> **Scope:** Unless stated otherwise, **every item applies to BOTH the mobile app and the web (Travel Desk) portal.**
> **Status:** Draft for review — priorities, difficulty, and open questions below are proposals to confirm before build.

---

## How to read this document

**Priority**

| Tier | Meaning |
|------|---------|
| **P0** | High value, low effort — quick wins. Do first. |
| **P1** | Important, medium effort or has a dependency. Do next. |
| **P2** | Large / integration-heavy. Plan and scope separately. |

**Difficulty (effort)**

| Badge | Meaning |
|-------|---------|
| **S** | Small — UI/logic tweak, no schema change. |
| **M** | Medium — new fields, cross app+web wiring, some backend. |
| **L** | Large — new schema/roles, auth, or third-party integration. |

**Surface** — where the change lands: **App**, **Web**, **Backend** (Convex), **IAM**.

---

## Master summary

| ID | Module | Item | Priority | Difficulty | Surface |
|----|--------|------|:--------:|:----------:|---------|
| V1 | Vehicle | Remove **Model** and **Model Year** from the add form | P0 | S | App · Web |
| V2 | Vehicle | Vehicle Type limited to **SUV / Sedan / Hatchback** | P0 | S | App · Web |
| V3 | Vehicle | **Vehicle number optional** on create | P0 | S | App · Web · Backend |
| V4 | Vehicle | **Auto-fetch seating capacity** for a vehicle | P1 | M | App · Web · Backend |
| V5 | Vehicle | **Create a vehicle inline from Trips** (like inline add-driver) | P1 | M | App · Web |
| D1 | Driver | Add **OLD / NEW** category when adding a driver | P1 | S–M | App · Web · Backend |
| T1 | Trips | Show the **date & time** (from CP outcome / direct CP) while assigning | P0 | S–M | App · Web |
| T2 | Trips | **Highlight reassigned / edited** trip cards distinctly | P0 | S–M | App · Web · Backend |
| T3 | Trips | **Expiry extends to end of day**, not the moment time crosses | P0 | S–M | App · Web · Backend |
| T4 | Trips | **Extra-km field** in completion, auto-priced by the trip's per-km rate | P1 | M | App · Web · Backend |
| S1 | Settings | **Restore external-fleet Settings** + rate/charge fields | P1 | L | App · Web · Backend · IAM |
| S2 | Settings | **Standing charge**: default allowance = duration + amount, set by admin | P1 | M | App · Web · Backend |
| A1 | Access | **Staff tab** — agency staff with scoped access (no Settings) | P2 | L | App · Web · Backend |
| A2 | Access | Add the **internal-fleet capabilities to IAM** | P1 | M–L | Backend · IAM |
| N1 | Access | **OTP redirect to agency admin** (drivers without the app) | P2 | M–L | App · Web · Backend |
| N2 | Notify | **WhatsApp trip dispatch + driver reply pipeline** (no app needed) | P2 | L | Backend · Integration |

---

## 1. Vehicle module

### V1 — Remove Model & Model Year from the add form  · P0 · S
**What:** Drop the **Model** and **Model Year** fields from the vehicle create/edit form.
**Why:** The external agency doesn't track these; they add noise to a form that should be fast to fill.
**Acceptance:**
- Create and Edit vehicle forms no longer show Model / Model Year (app + web).
- Existing stored values are ignored/untouched; no validation depends on them.

> Note: Model Year was recently surfaced on the vehicle list card. If Model/Year are removed from the form, the card's third slot should fall back to something meaningful (e.g. capacity or blank) rather than showing a stale year.

### V2 — Vehicle Type limited to SUV / Sedan / Hatchback  · P0 · S
**What:** The Vehicle Type dropdown should offer **only** `SUV`, `Sedan`, `Hatchback`.
**Why:** These are the only categories this agency operates; a shorter list speeds entry and keeps capacity auto-fill (V4) deterministic.
**Acceptance:**
- Type picker shows exactly these three options (app + web).
- No free-text "Other" for external fleet.

### V3 — Vehicle number optional on create  · P0 · S
**What:** **Vehicle Number is no longer mandatory** when creating a vehicle.
**Why:** Agencies often onboard a cab before the plate is confirmed, or use pooled/temporary vehicles.
**Acceptance:**
- Create succeeds with a blank vehicle number (app + web).
- Backend create mutation accepts a missing/empty vehicle number.
- Downstream displays handle a blank plate gracefully (show "—" / vehicle name instead).
**Reasoning / dependency:** Backend validator currently expects a plate — needs relaxing. *(Backend change → deploy-gated.)*

### V4 — Auto-fetch seating capacity  · P1 · M
**What:** When a vehicle is added, its **seating capacity is filled automatically**.
**Why:** Removes a manual step and keeps capacity consistent per vehicle class.
**Open question — source of truth:** How should capacity be derived?
- **(a)** A fixed default per Vehicle Type (e.g. Hatchback 5, Sedan 5, SUV 7) — simplest, no integration; or
- **(b)** Looked up from the vehicle number via an external RTO/registry service — heavier, needs an API + handles missing plates (conflicts with V3).
**Recommendation:** Start with **(a) type-based defaults**, editable if needed. Confirm the exact seat counts per type.
**Acceptance:** Selecting a type pre-fills capacity; the value remains editable.

### V5 — Create a vehicle inline from Trips  · P1 · M
**What:** In the Trips / allocation flow, allow **creating a new vehicle on the spot**, exactly like the existing inline "add new driver" action.
**Why:** A dispatcher assigning a trip shouldn't have to leave, go to Vehicles, add, and come back.
**Acceptance:**
- The allocate/assign screen exposes an "Add vehicle" action that opens the vehicle create form and, on success, selects the new vehicle for the trip (app + web).
- Mirrors the current inline add-driver UX.

---

## 2. Driver module

> The driver module is otherwise **approved as-is**. One addition:

### D1 — OLD / NEW category when adding a driver  · P1 · S–M
**What:** Adding a driver asks for a **category: `OLD` or `NEW`**.
**Why:** The agency distinguishes established (OLD) drivers from newly onboarded (NEW) ones — likely for trust/assignment decisions and reporting.
**Acceptance:**
- Add-driver form has an OLD/NEW selector (app + web).
- Value is stored on the driver record and visible on the driver list/detail.
**Open question:** Any behaviour tied to the category (e.g. filtering, restrictions), or is it purely a label for now?

---

## 3. Trips module

### T1 — Show trip date & time while assigning  · P0 · S–M
**What:** During assignment, the **trip's scheduled date & time must be shown** — the value set via the **CP outcome** or a **direct CP**.
**Why:** The dispatcher needs to see when the trip is for before picking a vehicle/driver and pickup time.
**Acceptance:**
- The allocate view shows the scheduled date + time carried from the originating CP (app + web).
- Matches the value stored when the CP outcome / direct CP set it.

### T2 — Highlight reassigned / edited trips  · P0 · S–M
**What:** A trip card that has been **reassigned or edited** should be **visually distinct** (badge / colour / "Updated" tag) from untouched cards.
**Why:** Lets everyone see at a glance that an assignment changed after it was first made.
**Acceptance:**
- Reassigned/edited cards render with a clear "Edited / Updated" treatment (app + web).
**Reasoning / dependency:** Needs a signal to detect "edited" — e.g. an `updatedAt` newer than assignment, or an explicit "reassigned" flag set by the reassign/edit action. *(Small backend flag likely needed.)*

### T3 — Expiry extends to end of day  · P0 · S–M
**What:** A trip must **stay live until the end of its scheduled day**, and **not expire the instant the scheduled time passes**.
**Why:** Field reality — a 12:00 pickup that starts at 12:20 shouldn't already be "Expired". Current behaviour flips to expired the second the time crosses, which is too aggressive.
**Acceptance:**
- Expiry is computed against **end-of-day** of the scheduled date, not `scheduledTime`.
- Applies everywhere expiry is derived (app trip lists, web tabs, backend if it filters). Keep the definition in one shared place so app and web agree.
**Reasoning:** This is effectively a bug fix to the expiry rule; verify all surfaces (app `VisitExpiry`, web tabs, any backend reclassification) use the same end-of-day rule.

### T4 — Extra-km field in trip completion, auto-priced  · P1 · M
**What:** In the **trip completion detail**, add a field to enter **extra km travelled** (for any reason). The extra amount is **auto-calculated using the per-km rate set for that trip**.
**Why:** Detours/extended trips need to be billed correctly without manual arithmetic.
**Acceptance:**
- Completion form has an "Extra km" input; entering it shows the computed extra charge (extra km × trip per-km rate).
- Stored on the trip so the final bill reflects it (app + web).
**Dependency:** Requires a **per-km rate on the trip** — comes from **S1 (Settings)**. Build after, or alongside, Settings.

---

## 4. Settings module (external fleet)

### S1 — Restore external-fleet Settings + rate/charge fields  · P1 · L
**What:** **Bring back the Settings page for external fleet**, expanded with rate/charge fields. These settings are **monitored by the Internal Fleet Admin and `Transport.assistantmanager`**.
**Fields requested:**
- Per km
- Package amount
- Betta *(batta — driver daily allowance)*
- Permit charges
- Permit taxes
- Standing charge (with AC) — see **S2**
- Waiting allowance
- Cancel allowance
- Toll charges
**Why:** These rates drive trip pricing (incl. T4 extra-km) and payouts; they were previously removed and are needed again, now richer.
**Acceptance:**
- External-fleet Settings page exists (app + web) with all fields above.
- Values persist per agency and feed trip pricing.
- **Visibility/monitoring:** Internal Fleet Admin and `Transport.assistantmanager` can view (and per A2, as permitted) these settings.
**Reasoning / dependency:** New backend fields per agency + read access for internal roles → ties into **A2 (IAM)**. *(Backend + deploy.)*
**Open questions:** Confirm units/currency, whether "with AC" implies a separate non-AC variant for other charges, and which of these are per-trip overridable vs agency defaults.

### S2 — Standing charge: default allowance (duration + amount)  · P1 · M
**What:** For **standing charge**, the **external fleet admin sets a default allowance as a time duration paired with an amount** (e.g. "up to N hours = ₹X, then …").
**Why:** Standing/waiting time is billed against a configured baseline, set by the admin.
**Acceptance:**
- Settings lets the admin define the standing-charge default: duration + amount (and, if applicable, the AC variant).
- Trip pricing uses this default.
**Open question:** Is it a single duration→amount pair, or tiered slabs (e.g. first 2h free, then ₹X/h)? Confirm the exact structure.

---

## 5. Access & identity

### A1 — Staff tab for external agencies  · P2 · L
**What:** A new **Staffs tab** where the agency admin can **add staff** (with their details). These staff get **the same access as the admin to manage drivers, vehicles, and assign trips** for **their own agency** — **except they cannot view or edit the Settings page.**
**Key constraints:**
- **Not associated with MMS.** External agency staff are **not** MMS/Manju staff records — they're a separate, agency-scoped identity that can log into the app and manage assignment + drivers.
- **Scoped to their agency** only.
- **Settings is excluded** from their access.
**Why:** Agencies need to delegate day-to-day dispatch without handing over rate/settings control.
**Open question — "basic details we get for creating a staff":** define the create fields. Proposed minimum: **Name, Mobile number** (login identity), optional **WhatsApp**, **role/label**. Confirm the exact set and how they authenticate (OTP to their own number, presumably).
**Acceptance:**
- Staffs tab (app + web) to add/list/deactivate agency staff.
- A staff member can do everything the admin can **except** Settings.
- Auth is agency-scoped and separate from MMS staff/IAM.
**Reasoning:** This is a **new auth/role surface** (external, non-MMS). Highest-effort of the access items; scope carefully.

### A2 — Add internal-fleet capabilities to IAM  · P1 · M–L
**What:** The **capable internal-fleet options are added to IAM**, so internal roles (Internal Fleet Admin, `Transport.assistantmanager`) get the right, explicit permissions — e.g. to monitor external-fleet Settings (S1) and manage fleet.
**Why:** Ties the internal oversight of external-fleet features to proper, grantable permissions instead of ad-hoc checks. (Consistent with the recent `marketing.fleet.assign` learnings — explicit grants resolve reliably.)
**Acceptance:**
- New/adjusted IAM permission keys for the internal fleet capabilities, listed in the IAM catalog and grantable per staff/designation.
- Internal Fleet Admin + `Transport.assistantmanager` can be granted the monitoring/management rights S1 and the fleet flows require.
**Reasoning / dependency:** Backend IAM catalog + deploy. Prerequisite for the internal-monitoring parts of **S1**.

### N1 — OTP redirect to agency admin  · P2 · M–L
**What:** An **OTP feature that redirects to the agency admin**, because **not all drivers install the app.**
**Why:** A driver who can't/won't install the app still needs to be authenticated/actioned; routing the OTP to the agency admin lets the admin complete the step on the driver's behalf.
**Open question — flow:** Clarify the exact intent:
- Is the OTP for a **driver login** that is instead delivered to / approved by the **agency admin**? or
- Is it an **assignment confirmation** the admin authorises? or
- Does it pair with **N2 (WhatsApp)** so drivers never need the app at all?
**Acceptance:** *(To be defined once the flow above is confirmed.)*
**Reasoning:** Overlaps heavily with N2 — decide them together; N2 may make N1 unnecessary for most cases.

### N2 — WhatsApp trip dispatch + driver reply pipeline  · P2 · L
**What:** Use the **default Manju Group WhatsApp automated messaging** to:
1. **Send trip details to the assigned driver** automatically **once a trip is assigned** to them.
2. Let the driver **reply on WhatsApp with the dashboard image + start km**, which is **parsed and written to the DB** — so **temporary/external drivers never need to install, update, or run the app.**
**Why:** Removes the biggest friction for casual/temporary external drivers; the agency already trusts WhatsApp.
**Acceptance (high level):**
- On assignment, an automated WhatsApp with trip details goes to the driver's number.
- An inbound WhatsApp handler ingests the driver's dashboard photo + start-km reply, validates it against the trip, and updates the trip record (start photo, start km, status).
**Reasoning / risk — highest complexity in this doc:**
- Requires the **WhatsApp Business API / automation platform** already used for Manju Group messaging (confirm which; there's existing DPR WhatsApp automation to build on).
- **Inbound parsing** (matching a reply to the right trip, extracting km from free text, handling media) is the hard part and needs a robust matching key (e.g. a per-trip token in the outbound message).
- Media storage + DB update pipeline + failure/retry + audit.
- **Recommend a phased build:** Phase 1 = outbound trip-details message on assignment (low risk). Phase 2 = inbound photo+km ingestion (the complex half).

---

## Suggested delivery sequence

**Phase 1 — Quick wins (P0, mostly app+web UI, minimal backend)**
`V1` remove model/year · `V2` restrict types · `V3` vehicle# optional · `T1` show CP date/time · `T2` highlight edited trips · `T3` expiry to end-of-day.

**Phase 2 — Medium features & their dependencies (P1)**
`V4` auto-capacity (confirm source) · `V5` inline create vehicle in Trips · `D1` OLD/NEW driver category · `A2` IAM internal-fleet options → then `S1`/`S2` Settings → then `T4` extra-km pricing (needs per-km from Settings).

**Phase 3 — Large / integration (P2)**
`A1` external agency Staff tab · `N1` OTP-to-admin (decide with N2) · `N2` WhatsApp dispatch + driver reply pipeline (build outbound first, then inbound).

> Rationale: Phase 1 ships visible value fast with little risk. Phase 2 is gated by Settings/IAM because pricing (T4) and internal monitoring depend on them. Phase 3 items are separate projects (new roles, auth, and a third-party messaging pipeline) and should each get their own scoping.

---

## Consolidated open questions (please confirm)

1. **V4** — Seating capacity source: type-based defaults (recommended) vs plate lookup? If defaults, the exact seats per SUV / Sedan / Hatchback.
2. **V1** — With Model/Year gone, what should the vehicle-card third slot show?
3. **D1** — Does OLD/NEW drive any behaviour, or is it a label for now?
4. **T2** — What counts as "edited" (any field change, or specifically reassignment)?
5. **S1** — Currency/units; which charges are agency defaults vs per-trip overrides; does "with AC" imply a non-AC counterpart?
6. **S2** — Standing charge: single duration→amount, or tiered slabs?
7. **A1** — Exact create-staff fields, and how agency staff authenticate (OTP to their own mobile?).
8. **N1** — Precise OTP-to-admin flow, and whether it's superseded by N2.
9. **N2** — Which WhatsApp platform/number powers "Manju Group automated messaging", and confirmation that inbound (driver → DB) ingestion is in scope for the first release.

---

## Notes on rollout

- Everything here spans **app + web + backend**; backend/Convex changes are **deploy-gated** and must go through the normal prod deploy (this workstation can't reach prod Convex).
- Several items (S1, A2) interlock with the **IAM permission model** — land the IAM keys first so internal monitoring and grants resolve reliably.
