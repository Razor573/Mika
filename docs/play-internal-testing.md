# Mika 2 on Google Play, internal testing

Goal: get a genuine Play install record (installer and initiating
`com.android.vending`, packageSource from Play) so banking apps that flag
sideloaded accessibility apps stop flagging Mika 2. Internal testing is
private, up to 100 testers, and does not need a public listing.

## The signing rule that must not be broken

Mika 2 on the phone today (v46, 0.1.41) is signed with the fork's own
keystore, the same key stored as GitHub secrets
`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` /
`ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`.

Play App Signing must be enrolled with **that existing key**, not a
Google-generated key:

- In Play Console, App integrity > App signing, choose
  "Export and upload a key from Java keystore" (existing app).
- Download the PEPK tool from that page, and on a machine with the
  offline keystore backup run, with the encryption key Play shows you:
  `java -jar pepk.jar --keystore=<your.keystore> --alias=<alias> --output=<file>.zip --encryption-key=<key from Play Console>`
- Upload the resulting `.zip` in Play Console. That transfers a copy of
  the existing app signing key to Google.
- Then generate a **new, separate upload key** (Android Studio or
  keytool, RSA 2048-bit or more) and register its certificate in Play
  Console. The AAB is signed with the upload key; Google re-signs the
  APKs it delivers with the transferred app signing key.
- Do NOT let Play generate a new app signing key, and do NOT choose
  "Upgrade your app signing key". Either of those makes the Play APKs
  carry a different signature, so they will NOT install over the
  sideloaded v46. Android will refuse the update, the only fix is
  uninstall, and all Mika 2 data (providers, keys, pairing, skills)
  is lost. If the wrong key is chosen before any internal/open/production
  release is rolled out, it can still be changed in App signing; once a
  release is rolled out on open testing or production, it cannot.

The AAB in this repo is built by the same workflow and the same
keystore secrets as the APK (`:app:bundleRelease`, artifact
`mika2-release-aab`), so its upload signature matches the existing
install. Keep the keystore backup offline; never commit it.

## Build

- `app/build.gradle.kts`: versionCode 49, versionName 0.1.44
  (versionName was also bumped because the in-app updater compares
  versionName/tag, and 0.1.42 is already the versionName of v47).
- `.github/workflows/build-apk.yml`: after the release APK step, runs
  `:app:bundleRelease` with the same keystore and uploads
  `mika2-release-aab` (14-day retention, same as the APK).
- ABI splits are assemble-only now, so `bundleRelease` is not
  affected by the arm64 split logic.

## Play Console steps (user, in the console)

1. Create a Play Console developer account, one-time $25. A new
   personal account needs identity verification.
2. Create the app: package `com.wangzan101.mika2`, name "Mika 2".
   Do not create a second app with a different package.
3. Enrol in Play App Signing with the existing key, as above,
   BEFORE uploading the AAB. Do not upload first and accept the
   default Google-generated key.
4. Upload the v47 AAB to Testing > Internal testing, add testers
   (up to 100 Google accounts, by email list), and publish the
   internal release. Internal releases are usually available within
   minutes, with no full public review.
5. On the phone, open the Play Store opt-in link for the internal
   test, install Mika 2 from Play. It should update over v46
   without uninstalling. Verify in Diagnostics / dumpsys that the
   installer and initiating package are now `com.android.vending`,
   then test HSBC UK.

## Accessibility declaration, the hard gate

Mika 2 declares `BIND_ACCESSIBILITY_SERVICE`
(`.a11y.MikaAccessibilityService`, `typeAllMask`, can retrieve window
content, perform gestures, take screenshots). It does NOT set
`isAccessibilityTool`, so it is a non-tool app in Play's terms.

That means, before the internal release can be published:

- Complete the Accessibility declaration in Play Console
  (App content > Accessibility): state what the service is used for
  (on-device agent operating the phone for the user), why no other
  API can do it, and provide a link to a short video showing the
  app opening, the prominent disclosure shown to the user, and the
  accessibility service being enabled and used. Google says review
  of this declaration can take weeks, and the release is held until
  it is approved.
- The app must show a prominent in-app disclosure before the
  service is enabled, describing the data accessed through the
  AccessibilityService API. The existing teach disclosure covers
  skill generation, not the accessibility service itself; check
  this before submitting, and add one if the reviewer cannot find it.
- Do NOT set `isAccessibilityTool=true` to dodge this: that flag is
  only for apps designed to help people with disabilities, and
  misusing it is a policy violation.

Other Play requirements for internal testing only:

- Data Safety form: NOT required for apps that only ever run on the
  internal testing track. It becomes required if the app moves to
  closed, open or production.
- Closed-test 12 testers / 14 days: NOT required for internal
  testing. It only gates production access for personal accounts
  created after 13 Nov 2023, and internal testing does not count
  towards it.
- Privacy policy, content rating, target audience, app access:
  needed for a full listing / closed track, not to start an
  internal test. Fill them in if Play Console blocks the internal
  release for missing app content.

## What this does not guarantee

A Play install gives Mika 2 a genuine Play record, which is the
last remaining explanation for HSBC flagging it. It is not a
guarantee: HSBC could also be flagging the accessibility service
itself, or Play Integrity. Do not claim HSBC passes until it is
tested live on the phone after a Play install.

<!-- trigger check: 2026-11-14T12:30:59Z -->
