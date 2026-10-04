# Google Play notes

What publishing on Google Play takes for this app (PLAN 9.2). Researched 2026-10-04 from Google's own policy and developer pages, listed at the end. "Inference" marks a reading of the policy rather than something it states.

## Biggest risks

1. **The photo and video permissions.** A bundle that asks for `READ_MEDIA_IMAGES` or `READ_MEDIA_VIDEO` without an approved declaration can't be published on any track, closed testing included, and review can take weeks [S3]. Only apps whose core purpose is managing all of a user's photos qualify [S1][S2]; recognising photos already on the phone is a secondary feature, so expect a refusal.
2. **Reviewers reaching the app's core.** It needs a Nextcloud server and adb. Google expects every resource needed for review [S24] and treats an app that loads but does nothing as broken [S29].
3. **The adb step read as circumvention.** Google describes cloud media providers as a pilot for OEM-nominated apps behind a server-side allow-list [S4], and Play bans instructions to "circumvent security protections" [S8]. Inference: an allow-list of photo sources isn't a security protection, but the listing and the app must not make it sound like one.
4. **The foreground service declaration**, which needs a video [S7].

## Photo and video permissions

- Qualifying apps manage "all of users' photos/videos" (galleries), or the picker is "not technically sufficient" [S1][S2]. Nothing covers cloud providers or backup apps [S14].
- The declaration needs a core use case, reviewer instructions, a video and access details [S3].
- Best argument (inference): the app *is* the picker's back end, and the platform's `MEDIA_STORE_URI` column expects a provider to know each item's local MediaStore URI [S5], which the picker can't give it.
- Partial access (`READ_MEDIA_VISUAL_USER_SELECTED`) is declared alongside the same permissions [S6], so it doesn't avoid the declaration, and it would defeat recognising the phone's copies.

The declaration is required: since 2025-05-28 every app asking for these permissions needs an approved one, and apps without are "subject to removal" [S2] (checked on Google's page, 2026-10-04).

**Decision (Keith, 2026-10-04):** one build for every channel, with the permissions; submit the declaration with the argument above, and revisit only if it's refused. The permissions are already optional, which the policy requires anyway [S2].

## Foreground service

- `dataSync` fits: its documented uses include "Data upload or download" [S9], matching Play's "Network transfer" use case [S7]. `shortService` stops after about 3 minutes, too short for a long video, and `mediaProcessing` is for transcoding [S9].
- Declaration: a description, what the user loses if it's stopped, a video and the use case [S7]. The service must be user-initiated or user-visible, stoppable, and stop when done [S8].
- Android 15 and later give `dataSync` 6 hours in 24, reset when the user opens the app; the service must call `stopSelf()` in `onTimeout` or the app crashes [S10].
- Done (2026-10-04): `onTimeout` stops the service, and its notification has a Stop action. Still to do: keep the word "proxy" out of the declaration, since Play restricts proxy services [S8] (inference), and record the video ([screenshots.md](screenshots.md)).

## adb and Shizuku

- No policy forbids an app that needs adb or Shizuku [S14]; the malware policy is about abusing privileges and rooting without consent [S11]. Shizuku, aShell, LADB and SystemUI Tuner (whose listing gives `adb shell pm grant` commands) are on Play [S13].
- Listing: lead with "One-time setup with adb or Shizuku. No root." and describe the step as turning on Android's built-in cloud media setting; never "bypass", "hack" or "unlock".
- App: a clear disclosure and consent before the commands run, and a way to turn it off that clears the overrides, since such changes must be "easily reversible" [S12]. **To do:** an in-app "Turn off" through Shizuku; today the app only shows the commands to copy.

## The keyboard

No keyboard-specific policy or declaration [S14]. Inserting a photo the user chose is a user-initiated transfer, not "sharing" in Data safety [S15].

## Developer account

- New personal accounts need a closed test with at least 12 testers opted in for 14 days in a row before production [S16]. Keith's account predates that rule, so it doesn't apply.
- Identity: a government ID for a personal account; an organisation needs a D-U-N-S number and documents [S17], free but up to 28 days [S19]. Either way, a non-rooted phone on Android 10 or later for device verification [S18].
- Android developer verification for sideloaded apps: from 2026-09-30 it covers installs from seven stores in Brazil, Indonesia, Singapore and Thailand only; GitHub and IzzyOnDroid installs are unaffected until the global rollout in 2027, and adb installs always are [S19][S20]. Play registers the package; another signing key's SHA-256 can be added [S19][S21].

## Listing and declarations

- **Privacy policy:** required, linked in the app too; a public web page naming the developer, with contact details and retention [S22] (PLAN 9.8).
- **Data safety:** "collect" means sending data off the device [S15]. The official Nextcloud app declares no data collected [S35]; inference: credentials sent to the user's own server can be treated the same way. Plain HTTP is allowed only to servers on the local network (NextcloudClient refuses it otherwise), so say so rather than claim everything is encrypted in transit [S22]. Filled in before closed testing [S15].
- **Content rating:** the IARC questionnaire [S27]. Target audience 18 and over keeps the app outside the Families policy [S28].
- **App access for reviewers:** reusable credentials in English [S23]: a demo Nextcloud server and app password, the adb steps, and a video. Inference: the keyboard works without adb, so point reviewers to it first. Decision (Keith): set this up only if review asks for it.
- **Graphics:** icon 512×512 32-bit PNG; feature graphic 1024×500 without transparency; 2 to 8 screenshots, 320 to 3840 px a side [S25]; title up to 30 characters [S34].
- **Build:** an Android App Bundle [S33]; target SDK 37 meets the minimum of 36 [S32].
- **Signing:** new apps default to a key Google generates. Choose "Provide a copy of your app signing key" before any open or production release, so GitHub APKs can be signed with the same key [S26]. The release key was made on 2026-10-04 (RSA 4096, `CN=Keith Vassallo`, SHA-256 `08:C0:A9:28:BD:71:22:D3:A2:B9:94:D2:49:7A:D7:7E:1A:DE:E4:6E:85:4E:CC:6E:2D:E1:93:20:5F:6E:AE:CD`); it is kept outside the repository.
- **Trademark:** no "Nextcloud" in the name, a statement that the app is unofficial [S31], no implied affiliation [S30]: "Not affiliated with Nextcloud GmbH", and not their logo.

## Sources

- S1 https://support.google.com/googleplay/android-developer/answer/9888170
- S2 https://support.google.com/googleplay/android-developer/answer/14115180
- S3 https://support.google.com/googleplay/android-developer/answer/9214102
- S4 https://developer.android.com/guide/topics/providers/cloud-media-provider
- S5 https://developer.android.com/reference/android/provider/CloudMediaProviderContract.MediaColumns
- S6 https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- S7 https://support.google.com/googleplay/android-developer/answer/13392821
- S8 https://support.google.com/googleplay/android-developer/answer/9888379
- S9 https://developer.android.com/develop/background-work/services/fgs/service-types
- S10 https://developer.android.com/develop/background-work/services/fgs/timeout
- S11 https://support.google.com/googleplay/android-developer/answer/9888380
- S12 https://support.google.com/googleplay/android-developer/answer/9888077
- S13 https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api, https://play.google.com/store/apps/details?id=in.sunilpaulmathew.ashell, https://play.google.com/store/apps/details?id=com.draco.ladb, https://play.google.com/store/apps/details?id=com.zacharee1.systemuituner
- S14 https://support.google.com/googleplay/android-developer/answer/17190352
- S15 https://support.google.com/googleplay/android-developer/answer/10787469
- S16 https://support.google.com/googleplay/android-developer/answer/14151465
- S17 https://support.google.com/googleplay/android-developer/answer/10841920
- S18 https://support.google.com/googleplay/android-developer/answer/14316361
- S19 https://developer.android.com/developer-verification/guides/faq
- S20 https://android-developers.googleblog.com/2026/06/android-developer-verification.html
- S21 https://developer.android.com/developer-verification/guides/open-source-app-registration
- S22 https://support.google.com/googleplay/android-developer/answer/10144311
- S23 https://support.google.com/googleplay/android-developer/answer/15748846
- S24 https://support.google.com/googleplay/android-developer/answer/10788890
- S25 https://support.google.com/googleplay/android-developer/answer/9866151
- S26 https://support.google.com/googleplay/android-developer/answer/9842756
- S27 https://support.google.com/googleplay/android-developer/answer/9898843
- S28 https://support.google.com/googleplay/android-developer/answer/9867159
- S29 https://support.google.com/googleplay/android-developer/answer/9898783
- S30 https://support.google.com/googleplay/android-developer/answer/9888374
- S31 https://nextcloud.com/trademarks/
- S32 https://support.google.com/googleplay/android-developer/answer/11926878
- S33 https://developer.android.com/guide/app-bundle
- S34 https://support.google.com/googleplay/android-developer/answer/9859152
- S35 https://play.google.com/store/apps/datasafety?id=com.nextcloud.client
