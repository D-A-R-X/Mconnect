# CP Completion Repeats OTP / Complete Action

Date: 2026-09-09

## Observed state

The supplied screenshots show one CP with:

- arrival photo captured;
- arrival OTP verified;
- field visit still `arrived`;
- parent CP still `scheduled` on web;
- mobile asking the staff to complete or enter OTP again.

This is a partially committed workflow, not a genuinely completed CP row. Mobile now recovers from it, but the backend must return one authoritative committed state after every write.

## Existing endpoints used by mobile

All calls require:

```http
Authorization: Bearer <AIRIX_SESSION_TOKEN>
Content-Type: application/json
```

### 1. Read one CP and reconcile the screen

```http
GET /api/marketing/clientPlaceVisits/get?id=<clientPlaceVisitId>
```

Required response fields:

```json
{
  "success": true,
  "visit": {
    "_id": "<clientPlaceVisitId>",
    "status": "completed",
    "effectiveStatus": "completed",
    "clientMet": true,
    "outcome": "old_client_visited",
    "completedAt": 1757410000000,
    "fieldVisitId": "<fieldVisitId>",
    "fieldVisit": {
      "_id": "<fieldVisitId>",
      "status": "completed",
      "completedAt": 1757410000000
    },
    "arrivalProof": {
      "photoStorageId": "<storageId>",
      "otpVerifiedAt": 1757409900000,
      "gpsLat": 13.04392,
      "gpsLng": 80.21204,
      "distanceFromPlaceMeters": 3662
    }
  }
}
```

For an unfinished but OTP-verified visit, `status` may still be live, but `arrivalProof.otpVerifiedAt` and `fieldVisit.status: "arrived"` must be returned so mobile never asks for OTP again.

### 2. Save an out-of-geofence reason

```http
POST /api/marketing/cp-visits/geofence-remark
```

```json
{
  "id": "<clientPlaceVisitId>",
  "remark": "Client requested the meeting at this location"
}
```

Required response:

```json
{
  "success": true
}
```

The write must be committed before returning. Mobile now waits for this response before opening photo/OTP.

### 3. Verify arrival OTP and attach the photo

```http
POST /api/geotrack/visit/arrival-otp/verify
```

```json
{
  "visitId": "<fieldVisitId>",
  "otp": "<four-digit-otp>",
  "lat": 13.04392,
  "lng": 80.21204,
  "arrivalPhotoStorageId": "<storageId>"
}
```

Required response:

```json
{
  "success": true,
  "arrivalDistanceFromPlaceMeters": 3662
}
```

Before this response is sent, the field visit must contain `arrivalVerifiedAt`, `arrivalLat`, `arrivalLng`, `arrivalPhotoStorageId`, and status `arrived`.

### 4. Record CP outcome

```http
POST /api/marketing/clientPlaceVisits/markClientMet
POST /api/marketing/clientPlaceVisits/setOutcome
```

Old Client request:

```json
{
  "id": "<clientPlaceVisitId>",
  "outcome": "old_client_visited",
  "notes": "<staff remarks>"
}
```

Required `setOutcome` response:

```json
{
  "success": true,
  "status": "completed",
  "outcome": "old_client_visited",
  "visit": {
    "_id": "<clientPlaceVisitId>",
    "status": "completed",
    "effectiveStatus": "completed",
    "outcome": "old_client_visited",
    "completedAt": 1757410000000
  }
}
```

For a completion outside the configured client geofence, the terminal workflow state is `pending_gm_approval`, not `scheduled` or `arrived`.

### 5. Close the field visit

```http
POST /api/geotrack/visit/complete
```

```json
{
  "visitId": "<fieldVisitId>",
  "lat": 13.04392,
  "lng": 80.21204,
  "remarks": "Arrival verified",
  "arrivalPhotoStorageId": "<storageId>",
  "clientMet": true,
  "outcome": "old_client_visited",
  "cpOutcomeNotes": "<staff remarks>"
}
```

Required response:

```json
{
  "success": true,
  "fieldVisitId": "<fieldVisitId>",
  "clientPlaceVisitId": "<clientPlaceVisitId>",
  "status": "completed",
  "outcome": "old_client_visited",
  "completedAt": 1757410000000,
  "alreadyCompleted": false
}
```

For an out-of-geofence completion, return `status: "pending_gm_approval"`. A replay with the same already-completed visit must return HTTP 200, `success: true`, `alreadyCompleted: true`, and the same terminal state.

## Backend correction required

No additional endpoint is needed. Update the existing completion transaction so it atomically or idempotently guarantees:

1. OTP/photo proof is linked to the field visit.
2. `clientMet` and outcome are persisted on the CP.
3. CP status becomes `completed`, `postponed`, `cancelled`, or `pending_gm_approval`.
4. Field visit status becomes `completed` when appropriate.
5. The response returns the committed parent and field-visit state.
6. `GET /clientPlaceVisits/get` immediately reads the same committed values.

The current backend source for `/api/geotrack/visit/complete` forwards only `visitId`, location, remarks, and photo to `fieldVisits.completeVisit`, then returns no parent CP status. It does not forward the mobile request's `clientMet`, `outcome`, `cpOutcomeNotes`, postpone reasons, or follow-up slot. That response gap is why clients need compatibility recovery and why a partial failure is difficult to distinguish from a completed operation.
