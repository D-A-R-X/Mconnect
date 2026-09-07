# MConnect Mobile - Remaining Dialer Provider Requirements

Date: 2026-09-03

## Provider Host Required

Provide one resolvable provider API host under `*.theairix.com` and set it in
MMS as `telecaller.modernDialerApiUrl`.

Current evidence:

- `dialer-api.theairix.com` does not resolve in DNS.
- `https://dialer.theairix.com` serves the embedded web Dialer, but the required
  `/api/mobile/*` routes return HTTP 404.

The routes below are server-to-server. MMS sends
`X-Service-Secret: <SERVER_ONLY_SECRET>`. The secret must never be embedded in
Android/iOS or returned in an API response.

## Missing Provider Endpoints

### 1. Device-token ingestion

```http
POST /api/mobile/device-tokens
X-Service-Secret: <SERVER_ONLY_SECRET>
Content-Type: application/json
```

Android example:

```json
{
  "token": "<FCM_TOKEN>",
  "platform": "android",
  "provider": "fcm",
  "bundleId": "com.manjugroups.mconnect",
  "deviceId": "<ANDROID_ID>",
  "extension": "1030",
  "externalId": "<STAFF_ID>",
  "active": true
}
```

iOS example:

```json
{
  "token": "<PUSHKIT_TOKEN>",
  "platform": "ios_voip",
  "provider": "apns_voip",
  "bundleId": "com.manjugroups.mconnect",
  "deviceId": "<IOS_DEVICE_ID>",
  "extension": "1030",
  "externalId": "<STAFF_ID>",
  "active": true
}
```

Upsert by staff, platform, provider, bundle ID, and device ID. Deactivate tokens
on logout and after FCM/APNs invalid-token feedback.

### 2. Current authoritative call

```http
GET /api/mobile/calls/current
  ?extension=<MAPPED_EXTENSION>
  &externalId=<STAFF_ID>
  &callId=<OPTIONAL_CALL_ID>
X-Service-Secret: <SERVER_ONLY_SECRET>
```

```json
{
  "success": true,
  "call": {
    "callId": "call-123",
    "direction": "incoming",
    "stage": "incoming",
    "fromNumber": "919876543210",
    "toNumber": "1030",
    "displayName": "Actual client name",
    "extension": "1030",
    "requiresPickup": true,
    "muted": false,
    "held": false,
    "startedAt": "2026-09-03T10:00:00.000Z",
    "expiresAt": "2026-09-03T10:01:00.000Z"
  }
}
```

Return `call: null` when idle. Enforce extension/external-ID ownership. The
display name must be the remote client/contact, never the receiving staff name.

### 3. Idempotent call action

```http
POST /api/mobile/calls/{callId}/action
X-Service-Secret: <SERVER_ONLY_SECRET>
Idempotency-Key: <UUID>
Content-Type: application/json
```

```json
{
  "action": "pickup|reject|hangup",
  "extension": "1030",
  "externalId": "<STAFF_ID>",
  "staffId": "<STAFF_ID>",
  "deviceId": "<DEVICE_ID>",
  "platform": "android|ios",
  "eventId": "<PUSH_EVENT_ID>",
  "source": "mms"
}
```

```json
{
  "success": true,
  "callId": "call-123",
  "stage": "connecting",
  "alreadyApplied": false
}
```

Support `pickup`, `reject`, and `hangup` without requiring the embedded web
Dialer or mobile process to be running. Repeating the same idempotency key must
return the same final result without duplicate PBX actions.

### 4. Media restart

```http
POST /api/mobile/calls/{callId}/media/restart
X-Service-Secret: <SERVER_ONLY_SECRET>
Idempotency-Key: <UUID>
Content-Type: application/json
```

```json
{
  "reason": "ice_failed",
  "extension": "1030",
  "externalId": "<STAFF_ID>",
  "staffId": "<STAFF_ID>",
  "deviceId": "<DEVICE_ID>",
  "platform": "android|ios",
  "source": "mms"
}
```

```json
{
  "success": true,
  "callId": "call-123",
  "stage": "connecting",
  "iceRestarted": true
}
```

The provider must perform a real ICE restart/re-offer, not only reset a database
status.

### 5. Sanitized media diagnostics

```http
GET /api/mobile/calls/{callId}/media
  ?extension=<MAPPED_EXTENSION>
  &externalId=<STAFF_ID>
X-Service-Secret: <SERVER_ONLY_SECRET>
```

```json
{
  "success": true,
  "callId": "call-123",
  "media": {
    "iceConnectionState": "connected",
    "iceGatheringState": "complete",
    "connectionState": "connected",
    "signalingState": "stable",
    "selectedCandidatePair": {
      "localType": "relay",
      "remoteType": "relay",
      "transport": "udp"
    },
    "inboundAudioPackets": 120,
    "inboundAudioBytes": 42100,
    "outboundAudioPackets": 118,
    "outboundAudioBytes": 39900,
    "lastRtpAt": "2026-09-03T10:00:30.000Z"
  }
}
```

Never return TURN credentials, ICE passwords, service secrets, bearer tokens,
API keys, or provider credentials.

## Required Call Push Delivery

After token registration, send these provider events through MMS to every
active device mapped to the call.

Android incoming calls require a high-priority data-only FCM message with a
30-60 second TTL and a `callId` collapse key. Do not include an FCM
`notification` block.

```json
{
  "type": "dialer-call-incoming",
  "eventId": "event-123",
  "callId": "call-123",
  "callUuid": "optional-uuid",
  "fromNumber": "919876543210",
  "clientName": "Actual client name",
  "extension": "1030",
  "requiresPickup": "true",
  "expiresAt": "2026-09-03T10:01:00.000Z"
}
```

iOS must use APNs VoIP/PushKit with:

```text
apns-push-type: voip
apns-topic: com.manjugroups.mconnect.voip
apns-expiration: 0
apns-priority: 10
```

When the call ends, is rejected, expires, or is answered elsewhere, send:

```json
{
  "type": "dialer-call-ended",
  "eventId": "event-124",
  "callId": "call-123",
  "reason": "remote_hangup|answered_elsewhere|rejected|expired"
}
```

The terminal event must immediately stop Android ringing/full-screen UI and end
the iOS CallKit call on every device.

## Non-Endpoint Asterisk/Media Work

No additional Android/iOS endpoint alone can repair the current silent audio
and approximately eight-second disconnection.

The provider/Asterisk deployment must:

- Advertise a public reachable ICE/RTP candidate.
- Supply valid TURN credentials with UDP, TCP, and TLS 443 fallback.
- Forward trickled ICE candidates in both directions.
- Support provider-side ICE restart and re-offer.
- Validate NAT/firewall forwarding for the configured RTP port range.
- Validate DTLS fingerprints and two-way RTP packet flow.
- Avoid declaring answered media usable until a candidate pair is selected and
  inbound/outbound RTP counters are non-zero.
- Use a defensible media timeout of approximately 30 seconds instead of ending
  at the current approximately eight-second ICE failure boundary.

## Acceptance Checks

1. Incoming Android FCM rings in foreground, background, lock screen, Doze, and
   process-terminated states.
2. Incoming iOS VoIP push opens CallKit while foregrounded, locked, asleep, and
   terminated.
3. Accept, reject, and hangup are idempotent from every process state.
4. Terminal events clear all devices immediately.
5. Incoming and outgoing calls reach ICE `connected` or `completed`.
6. Both inbound and outbound RTP counters become non-zero on Wi-Fi and mobile
   data.
7. Both parties can hear and speak continuously for at least five minutes.
