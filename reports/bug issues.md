# Bug issues: CP status mismatch and old-record repair

Date: 2026-09-05
Scope: Android/iOS CP display, API consistency, and admin-owned historical repair.
Website repository has not been modified by this mobile task. Database repair
has not been executed by this task; external deployment/apply status is unverified.

## Admin handoff received: 2026-09-05

Source: `C:/Users/surya/Downloads/cp-completion-developer-handoff.md`.
The following implementation/test claims are reported by that handoff, not
independently verified against deployed backend code or production records.

- Public mobile endpoints and request bodies remain unchanged. No additional
  mobile endpoint integration is required by this handoff.
- `clientMet=false`: photo required, OTP not required. `clientMet=true`: both
  photo and OTP required. The handoff reports a passing Joint CP no-show test
  covering submission/completion without reviewer OTP.
- Internal repair mutation: `internal.adminCpVisitRepair.repairCompletedCpLinkedState`.
- Confirmation: `REPAIR_COMPLETED_CP_LINKED_STATE_2026_09_05`.
- Modes: `dryRun` and `apply`; requires super-admin actorStaffId. Apply requires
  reviewed approvedCandidates containing cpVisitId and expectedCpRevision,
  with at most 50 candidates per call. Supply jobId for the repair operation.
- Only a parent with exact status `completed` is repairable. Changed parent
  revisions conflict/skip; Joint CP requires latest review draft reviewedAt.
  Already completed/cancelled linked rows remain untouched.
- Repair covers linked fieldVisits, clientPlaceVisitParticipants, geoTrips,
  CP-source dailyTasks, and relevant site_visit trackingSessions through the
  existing tracking helper. Audit action: `completed-cp-linked-state-repaired`.
- Review dry-run cpVisitId, expectedCpRevision and staleCounts before apply;
  inspect repaired/repairedDetails, skipped, conflicts and failures afterward.
- Focused proof/repair tests and touched-file lint reportedly passed. Full
  TypeScript checking reportedly remains blocked by unrelated existing errors.

Operator follow-up: confirm deployment, obtain a scoped dry run, approve its
candidates, apply, then compare the exact CP IDs across mobile APIs and web.
No deployment, dry run or apply command was executed by this mobile task.

This repair addresses linked state, not incorrect client names/numbers, blank
historical outcome text, nonterminal parents or unreviewed Joint CPs. Those
must not be marked repaired merely because this migration exists.

## Additional iOS client-identity regression checks

Code audit on 2026-09-05 found stale client autofill when switching phone
numbers and stale manual search responses. iOS-only corrections are local;
the affected production records have not been inspected or repaired.

- Enter client A's number and allow autofill; switch to client B. A's unchanged
  autofilled name, address, map coordinates, project and LMO must not carry over.
- Manually edit a field before changing the number: intentional edits remain.
  Phone formatting changes for the same normalized number must not clear data.
- Delay A's lookup, enter B, then deliver A's response. It must not change B's
  number, selected lead or details. Repeat with the manual search button.
- Return multiple exact-phone lead matches: show choices, do not silently pick
  the first. Return a wrong-phone match: do not bind it to this request.
- Return a lead without a name: never use its phone number or record ID as the
  submitted clientName. Require entry or a named client-master lookup result.
- Submit while a lookup/address parse is pending: no late response may change
  the outgoing request. A selected leadId must match the submitted phone.
- Compare the captured POST create payload with GET detail and web for the same
  created ID. If a correct payload becomes different server/web data, investigate
  backend source precedence, persisted snapshots and display logic separately.

## Reported symptoms and findings

- Completed CP displayed as Enroute when its linked field visit remains active.
- Web shows Completed, while mobile shows Pending inside the Completed filter.

A CP record, its field visit/trip, and its task have separate lifecycle fields.
They can disagree after an interrupted multi-step completion. The existing mobile
status policy already prefers a terminal CP status over an older live trip status.
If the API still sends a nonterminal CP, that rule cannot discover the correct
state from the website alone: compare records and responses by their IDs.

The confirmed mobile bug is a second display rule: even when the CP says
completed, a blank outcome relabels it Pending. The filter independently reads
completed. Older records with incomplete outcome metadata trigger this conflict.

## Mobile correction implemented locally

Android and iOS now distinguish the authoritative CP lifecycle from missing
outcome metadata:

| CP status | Linked trip | Outcome | Display/action |
| --- | --- | --- | --- |
| completed/complete/done/closed | enroute/in-progress/completed/missing | Any, including blank | Completed; read-only completion detail |
| cancelled/canceled | Any | Any | Cancelled; no restart |
| postponed | Any | Any | Preserve postponed behavior |
| pending_gm_approval | completed | Any | Preserve approval workflow; not outcome recovery |
| Nonterminal | completed | Blank | Outcome still required; not in Completed filter |
| Nonterminal | active | Any | Preserve active workflow; do not infer completion |

The same outcome-pending policy controls Pending badge/action and Completed
filter eligibility. Neither a photo, OTP, outcome text nor a linked SV/booking ID
alone is sufficient to force a CP completed. This is especially important for
pre-fixed SVs, booking drafts and Joint CP review.

### Effect on old CP records

After installing the mobile fix and refreshing the list, old CP records whose
API status already says completed will display Completed, including those with
blank outcome text or stale trip status. Staff must not redo those completions.

This is a display correction, NOT a database migration. It does not repair stale
server trips/tasks, stop tracking sessions, restore missing proof, or rewrite
historic outcomes. The admin must apply the repair below for stored corruption.

## Required backend/admin repair

Use the shared backend; do not implement a phone-side bulk completion loop.
No new public mobile endpoint is required. The admin handoff above supplies an
internal mutation. The following remains the safety/acceptance checklist;
do not assume every item is implemented without reviewing the backend code.

1. Start with a read-only dry run. Page through candidate CPs and linked records.
   Record CP ID, trip IDs, task IDs, current statuses, revisions/updatedAt,
   authoritative completion evidence, proposed changes and skip reasons.
2. Resolve identity using exact relationships, never names or phone matching.
   Include both participant trips for Joint CP without merging their routes.
3. If a CP is authoritatively completed, verify its finalization event/transaction
   and all required approvals/reviews. Reconcile only its stale linked trip/task
   rows to the appropriate terminal state.
4. If CP is still nonterminal but another API/web response says completed,
   investigate the discrepancy first. Reconcile only with authoritative audit
   evidence that the CP actually finalized. Missing or conflicting evidence
   goes to manual review; do not guess from completedAt or an outcome alone.
5. Exclude active review/GM approval, reopened/reassigned visits, pre-fixed SVs,
   draft bookings, and genuine unfinished outcomes. For Joint CP, require the
   reviewer-completed state before finalizing either participant's workflow.
6. Apply each candidate in a transaction with revision checks. Re-read before
   applying; skip if it changed after the dry run. Never overwrite a newly
   reopened visit or affect another trip owned by the same staff member.
7. Preserve real timestamps, OTP/proof, remarks, assignments, CP type, booking/SV
   linkage and both travel histories. Do not fabricate completion timestamps,
   missing outcomes, proof photos or OTP verification. Missing evidence remains
   explicitly missing and must not cause the completed CP to reopen.
8. Audit every write with job ID, actor, reason and before/after values. Support
   resumable cursors and a reviewed rollback from captured values with revision
   checks. A repeated repair run must produce no duplicate side effects.
9. Invalidate affected list/detail caches and publish existing refresh events
   only after commit. Tracking cleanup must use established session rules and
   must never stop a separate active attendance/trip session.

Suggested job result fields: jobId, dryRun, scanned, candidates, repaired,
skipped, conflicts, failures, nextCursor. Require admin-only authorization,
explicit scope and approval of the dry-run report before applying.

## Existing API contracts to verify

Base MMS host: `https://api-mfpl.theairix.com`

| Endpoint | Required consistency |
| --- | --- |
| GET /api/marketing/clientPlaceVisits/my | Return authoritative CP status, linked trip status separately, stable IDs and current revision/update time. Apply counts, filters and pagination consistently. |
| GET /api/marketing/clientPlaceVisits/get?id=... | Same canonical CP state as list and web for the exact same ID. |
| POST /api/marketing/clientPlaceVisits/setOutcome | Do not report final completion before required workflow checks. Final transitions and linked closure must be atomic or backed by a durable server retry. |
| POST /api/geotrack/visit/complete | Idempotent trip completion with accurate success/error. A trip completion alone must not falsely complete a CP awaiting outcome/review. |
| POST /api/marketing/clientPlaceVisits/cancel | Atomic, idempotent closure of the correct linked CP/SV/trip/task with required remarks. |
| POST /api/bookings | Saving a draft must not finalize the source CP/SV. Preserve source and review rules for submitted bookings. |

The mobile fix cannot guarantee web/mobile agreement if list and detail APIs
return different revisions or if web derives status from different records.
Admin must verify deployed contracts before marking server repair complete.

## Acceptance checks

1. Reload an old completed CP with stale enroute trip: Completed badge, correct
   filter, no Start Trip/reopen-completion action, unchanged historical proof.
2. Reload an old completed CP with blank outcome: Completed in both clients;
   missing metadata remains missing, not invented or shown as Pending lifecycle.
3. A trip-only completion with no CP outcome remains recoverable and is excluded
   from Completed. Approval/review cases remain in their proper workflows.
4. Verify ordinary CP, SV-cum-CP, booking draft/submission, client-not-met and
   Joint CP review separately. Do not use a blanket update for all CP types.
5. Run repair dry-run, approve scoped application, then rerun: zero additional
   changes. Simulate concurrent reopen; revision check must skip it.
6. Compare mobile API list/detail and web by CP ID after cache refresh and verify
   both participant histories survive. Do not test against real active visits.

## Joint CP: Client Not Met Incorrectly Requires OTP

The explicit no-show mobile path does not call arrival-otp/request or
arrival-otp/verify. An OTP requirement in this path can therefore also be a
backend proof/workflow validation error; capture the actual failed response
before attributing a reported case conclusively.

Confirmed mobile defects corrected locally:

- iOS now includes the uploaded arrivalPhotoStorageId in the no-show setOutcome
  request, matching Android. Previously the proof was only supplied to the later
  trip-completion request, too late for outcome validation.
- Both platforms route successful Joint CP no-show outcome submission through
  joint-submit-review, not ordinary trip completion. Partner review remains
  mandatory and completion is not shown before the backend confirms it.
- Entering no-show clears conflicting prior camera/OTP-repair modes. iOS Joint
  CP review preserves the recorded clientMet=false instead of rewriting it true.

Required existing backend contract, to be verified/updated by the admin:

1. joint-arrival-preflight checks actor permissions and both fresh locations
   within the existing 50-metre rule; it must not itself request an OTP.
2. POST /api/marketing/clientPlaceVisits/markClientMet records clientMet=false
   and clientNoShowReason on the correct CP before outcome submission.
3. POST /api/marketing/clientPlaceVisits/setOutcome accepts required photo proof
   and the applicable no-show outcome when persisted clientMet=false, without
   requiring arrivalVerifiedAt or a client OTP. Preserve optional remarks.
4. POST /api/marketing/clientPlaceVisits/joint-submit-review accepts that no-show
   proof without OTP, while enforcing outcome-owner role, current locations,
   proximity, workflow revision and idempotency. Return the updated workflow.
5. POST /api/marketing/clientPlaceVisits/joint-complete-review preserves false
   clientMet, requires authorized reviewer approval, and atomically closes the
   linked workflow/trips/tasks. It must not require the absent client's OTP.
6. When clientMet=true, preserve the existing OTP requirement. Never implement
   a blanket OTP exemption for all Joint CPs or set a fake verified timestamp.

No new route is required if these existing routes satisfy this contract. These
backend changes have not been applied from mobile. Server-side OTP errors may
persist until deployed; do not claim end-to-end no-show completion is verified.

Historical no-show records: locate candidates with persisted clientMet=false and
real photo storage IDs. Resume the proper review stage if review is incomplete;
only repair final state when authorized reviewer completion is documented. Never
mark missing OTP as missing proof for a no-show, but never waive a missing photo
or fabricate reviewer approval. Use the dry-run/revision/audit rules above.

Regression acceptance: ordinary and Joint CP, met vs not-met, cancelled camera,
retry after upload/outcome failure, photo absent/present, staff farther than 50m,
owner vs reviewer, interrupted review, and an old no-show record. Record HTTP
requests and assert zero OTP request/verify calls on every no-show path.

## Validation Scope

Android debug assembly passed; all 154 unit tests passed with zero failures,
errors or skips. Both mobile diffs pass whitespace checks. These checks do not
replace an authenticated no-show/review test on a device.
iOS native testing requires a Mac/device. Authenticated production record
comparison, backend repair execution and actual web synchronization have not
been performed. These remain release/repair acceptance requirements.
