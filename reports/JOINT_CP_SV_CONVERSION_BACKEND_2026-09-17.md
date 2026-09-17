# Joint CP: converting to a Site Visit skips the review (backend)

**For:** web/Convex admin
**From:** mobile. No web or Convex file has been edited by mobile.
**Date:** 2026-09-17
**Code checked:** MMS `development-testing` @ `f35f6f69`

## Problem

`convertToSiteVisit` (`convex/marketing/clientPlaceVisits.ts` ~11833) closes the CP with `status: "completed"` directly. It has no Joint CP handling. For a Joint CP this means:

- **No review step.** The reviewer never adds remarks. `submitJointReview` / `completeJointReview` never run.
- **Trips stay open.** `completeJointParticipantTrips` never runs, so neither participant's field visit is completed. Their GeoTrack trips and legs stay open.
- **Credit is inconsistent.** The CP reads Completed, but the review record that credits both staff does not exist.
- **The owner's submit then fails.** submit-review returns 409 "Cannot submit a terminal Joint CP for review".

This is reachable from the Site Visit option in the outcome form, for the owner and for the reviewer editing the outcome, on both apps.

## What mobile changed

Both apps no longer report a direct completion as "Outcome sent for review". A `completed` workflow counts as a confirmed submit only if it has `submittedAt`, `reviewedAt` or `reviewedByName`. The owner now sees the real error instead of a false success.

Mobile did not remove the Site Visit option, because that is a business decision.

## Recommended fix

In `convertToSiteVisit`, when `isJointCpVisit(visit)`:

1. **Do not** patch the CP to `completed`.
2. Create the SV as today. Store the conversion on the **outcome draft** via `saveJointCpOutcomeDraft`, with `outcome: "converted_to_site_visit"`, `clientMet: true`, and `outcomeDraft.convertedSiteVisitId`.
3. Return as a normal draft save. The owner then sends for review (`submitJointReview` completes the owner's leg). The reviewer adds remarks and completes (`completeJointReview` closes the CP, both legs, and credits both).

If the SV must exist before review, it can still be created in step 2. Only the CP close has to wait for the review.

Alternatively, if Joint CPs should never convert to a SV directly, reject it with `INVALID_STATE` and a clear message ("Send the Joint CP for review first"), and mobile will hide the option.

## Tests to add (`convex/jointCpVisits.test.ts`)

1. Owner converts to SV → CP not completed, draft has the conversion, both legs still open.
2. Submit review → reviewer completes → CP completed, both legs completed, both credited, SV linked.
3. Non-joint CP `convertToSiteVisit` unchanged.
