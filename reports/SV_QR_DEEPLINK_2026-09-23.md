# Site-visit QR: make a Google Lens scan open the app

**For:** whoever can publish a file on `mg.theairix.com` (web/hosting)
**From:** mobile
**Date:** 2026-09-23
**App side:** done, not yet released

---

## What staff are hitting

Field staff scan the site-visit QR with Google Lens or the phone camera. It
opens the **website**, so they have to come back into M-Connect and scan the
same code a second time.

## What the QR actually contains

Not a problem, as it turns out. It already encodes a normal URL:

```
https://mg.theairix.com/site-visit/consulting/<visitId>
```

(`features/marketing/pages/site-visit-detail-page.tsx:857`.) Any scanner can
read it. The QR does **not** need changing, and nothing needs reprinting.

The gap was that the Android app declared no intent filter for that address,
so the link had nowhere to go but the browser.

## Done in the app

- `SplashActivity` now claims `https://mg.theairix.com/site-visit/consulting/*`
  with `android:autoVerify="true"`.
- The id is parsed by `deeplink/SiteVisitDeepLink`, held on disk if the staff
  member is signed out (login can restart the process), and opened by
  `MainActivity` once the shell is up.
- It opens the **existing** scanner screen with the id already resolved
  (`QrScannerFragment.forSiteVisit`), so the counselling flow, its permission
  rules and its error handling are exactly those of an in-app scan - only the
  camera step is skipped, since Lens already did it.
- The old `SV:<id>` payload is still accepted, so nothing regresses.

Verified on a device: the app claims the URL, an external VIEW intent opens it,
it lands on the site-visit flow, no crash.

## What is still needed - one file on the domain

Android will not route a web link to an app unless the domain vouches for it.
Right now:

```
$ adb shell pm get-app-links com.manjugroups.mconnect
    mg.theairix.com: 1024          # 1024 = no response
$ curl -o /dev/null -w "%{http_code}" https://mg.theairix.com/.well-known/assetlinks.json
404
```

**On Android 12 and newer an unverified link does not even offer a chooser -
it goes straight to the browser.** So without this file the app-side work has
no effect for staff.

Publish at **`https://mg.theairix.com/.well-known/assetlinks.json`**, served as
`application/json`, reachable over HTTPS with no redirect:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.manjugroups.mconnect",
    "sha256_cert_fingerprints": [
      "<PLAY APP SIGNING SHA-256>",
      "<UPLOAD KEY SHA-256>",
      "1A:CE:31:5C:C8:01:32:B8:CF:55:12:D9:D5:AF:6B:75:BE:AE:A5:2F:95:1E:7F:CF:E7:52:DE:3E:89:78:01:51"
    ]
  }
}]
```

- **Play App Signing SHA-256** - the one that matters for staff, since Google
  re-signs every release. Play Console > Test and release > Setup > App
  signing > App signing key certificate > SHA-256.
- **Upload key SHA-256** -
  `10:EA:F2:C2:6E:C0:92:95:05:FB:C8:4E:6D:BD:50:21:0B:A7:D7:B2:54:F2:15:7F:38:CE:FF:7B:AC:C8:FC:EA`
  (keystore `mconnect-play-upload-key.jks`, alias `mconnect_upload`).
- The third is the shared Android debug key, so developers can test. Drop it
  if you would rather not list it.

Also see `maps-key-and-play-signing` - the Play SHA-1 for that key is
`F4:78:18:31:7E:38:1D:CC:E9:58:4B:70:E0:11:A8:81:AD:96:79:3D`, from the same
certificate.

### Confirming it took

```bash
adb shell pm verify-app-links --re-verify com.manjugroups.mconnect
adb shell pm get-app-links com.manjugroups.mconnect      # want: verified
```

Then scan a real site-visit QR with Google Lens - it should open M-Connect on
the visit, with no browser in between.

## iOS

Not done. The same trick on iOS needs **two** things, and one of them is an
Xcode project change that cannot be made or verified from this machine:

1. `https://mg.theairix.com/.well-known/apple-app-site-association` (served as
   `application/json`, no redirect), listing the app's `<TeamID>.<BundleID>`
   and the path `/site-visit/consulting/*`;
2. the **Associated Domains** entitlement (`applinks:mg.theairix.com`) added to
   the FoundationChat target, plus `onOpenURL` handling.

Worth doing in the same pass as the Android file, since it is the same domain
and the same hosting change.
