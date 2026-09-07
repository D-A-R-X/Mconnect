# Phone-entry device/account conflict API

## Purpose

Prevent mobile login from sending an OTP or opening the OTP screen when the
current app installation is already actively bound to a different staff
account.

This is a preflight usability check. `POST /api/auth/verify-otp` remains the
authoritative device-binding security boundary.

## Existing endpoint to update

`POST /api/auth/send-otp`

No new route is required.

### Mobile request

```json
{
  "phone": "9876543210",
  "deviceType": "mobile",
  "deviceId": "platform-scoped-device-id",
  "devicePlatform": "android",
  "deviceModel": "Manufacturer Model"
}
```

`devicePlatform` is `android` or `ios`. Web callers continue sending no mobile
identity fields and must retain their existing behavior.

### Conflict response

Return this before creating an OTP session or calling SMS/WhatsApp delivery:

```http
HTTP/1.1 409 Conflict
Content-Type: application/json
```

```json
{
  "success": false,
  "code": "DEVICE_BOUND_TO_ANOTHER_ACCOUNT",
  "error": "This device is linked to SARA.R.",
  "boundAccountName": "SARA.R"
}
```

Return only the display name needed by the requested warning. Do not return the
other staff member's phone, employee ID, designation, token, device ID or other
binding details.

## Backend decision

1. Normalize and validate the requested phone using the existing login rules.
2. Resolve the requested active staff account.
3. For `deviceType = mobile` with a valid device identity, find the active
   binding by the normalized `(devicePlatform, deviceId)` identity.
4. If that binding belongs to a different staff ID, return the conflict above.
5. If it belongs to the requested staff, or no active binding exists, continue
   the existing OTP flow unchanged.
6. Never create/store an OTP and never send SMS/WhatsApp for the conflict case.

## Security requirements

- Rate-limit by phone, device identity and IP before doing the lookup.
- Treat the client-supplied device ID as a routing hint, not hardware proof.
- Keep OTP verification and device recovery authoritative and unchanged.
- Log the conflict using hashed/redacted identifiers; do not log OTP values.
- Do not automatically move or delete a binding from this endpoint.
- Add Play Integrity and Apple App Attest verification before using this
  preflight as a stronger trusted-device signal.

## Mobile behavior

Android and iOS now send the optional device fields. On the exact stable code
`DEVICE_BOUND_TO_ANOTHER_ACCOUNT`, they remain on the phone-number screen and
show:

> Device linked to another account
>
> This phone is already linked to another staff account. Sign in with that
> account or contact admin.

All other responses preserve the current staff/agency OTP behavior. Older
servers may ignore the added request fields without breaking mobile login.

## Acceptance tests

1. Device unbound, valid staff phone: OTP is created and delivered once.
2. Device bound to the same staff: OTP is created and delivered once.
3. Device bound to different staff: HTTP 409 stable code; no OTP row or message.
4. Conflict response reveals only the bound account display name.
5. Invalid/unregistered phone retains the existing response and agency fallback.
6. Web login behavior is unchanged.
7. Missing device identity retains the current grace behavior.
8. OTP resend applies the same conflict decision.
9. `verify-otp` still enforces account-to-device binding independently.
10. CP, SV, Joint CP, attendance, tracking and existing sessions are untouched.
