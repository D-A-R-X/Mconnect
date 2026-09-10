# MConnect One-Time Release Notice

## Behavior

- The active campaign appears after a successful sign-in and only after the
  authenticated app shell is ready.
- A campaign is acknowledged only when the user taps **Continue**.
- Seen campaign IDs are stored on the device and survive normal app updates and
  account changes.
- The same campaign ID never appears again on that installation.
- Clearing app data or uninstalling the app also clears local seen history.
- The notice has no API dependency and cannot delay login or operational calls.

## Future release rule

Do not change the campaign for an ordinary build. Only when a release banner is
explicitly requested:

1. Set a new unique campaign ID and content in Android
   `ReleaseNoticeCampaign.kt` and iOS `ReleaseNoticeCampaign.swift`.
2. Keep both platforms on the same campaign ID and copy.
3. Set `activeCampaign` / `active` to `null` / `nil` for a release without a
   banner.
4. Build an update over an existing install, confirm the notice appears once,
   tap **Continue**, relaunch twice, and confirm it stays dismissed.
5. Never clear app data during the one-time behavior test.

## Current campaign

Campaign ID: `2026-09-10-service-apology-v1`

English: **Sorry for the inconvenience**

Tamil: **தடங்கல்களுக்கு வருந்துகிறோம்**

Transliteration: **Thadangalukku varundhugindrom**

Attribution: **MMS IT Team**
