# Device notes

Baseline state of the test phones before any activation (Phase 0.1, #2). Needed to restore them afterwards.

## GrapheneOS phone

Recorded 2026-10-02, read-only over adb.

| Item | Value |
|---|---|
| Model | Pixel 10 Pro Fold (`rango`) |
| OS | GrapheneOS, Android 17 (SDK 37), build `CP3A.260905.009`, incremental `2026092501` |
| Security patch | 2026-09-01 |
| MediaProvider | package `com.android.providers.media.module` (AOSP name), APEX `com.android.mediaprovider` 370399999 |
| Photo picker | `com.android.photopicker` present |
| Google Photos | installed (sandboxed), versionCode 52458705 |

Cloud picker state:

| Flag | `mediaprovider` | `storage_native_boot` |
|---|---|---|
| `allowed_cloud_providers` | unset (null) | unset (null) |
| `cloud_media_feature_enabled` | unset (null) | unset (null) |

No local overrides. Sync mode `none`.

MediaProvider's own view (`dumpsys activity provider com.android.providers.media.module/com.android.providers.media.MediaProvider`):

```text
isCloudMediaInPhotoPickerEnabled=false
defaultCloudProviderPackage=
allowedCloudProviderPackages=[]
shouldEnforceCloudProviderAllowlist=true
mCloudProvider=null
allAvailableCloudProviders=[]
```

Cloud media is off and the allow-list is empty: Google Photos is not a cloud source on this phone by default.

**Restore:** every flag was unset, so `device_config clear_override <namespace> <flag>` for both flags in both namespaces returns the phone to this state.

**Tooling:** the `media_provider` shell tool referenced in PLAN.md is not usable here (not a `cmd` service, and the `/system/bin` entry doesn't resolve). Use the `dumpsys` command above to read cloud picker state.

**Picker:** this build uses the older picker inside MediaProvider (`com.android.providers.media.photopicker.PhotoPickerActivity`). The newer `com.android.photopicker` app is installed but its activities are disabled.

### No-root activation test: passed (2026-10-02)

Google Photos stood in for our provider. On this phone it is an ordinary user-installed app (`pm list packages -3`), so the test covers the same case as our sideloaded APK.

1. Plain `adb shell`, no root:

   ```sh
   for ns in mediaprovider storage_native_boot; do
     adb shell device_config override $ns cloud_media_feature_enabled true
     adb shell device_config override $ns allowed_cloud_providers com.google.android.apps.photos
   done
   ```

   All four writes were accepted. MediaProvider picked them up immediately: `isCloudMediaInPhotoPickerEnabled=true`, and Google Photos appeared in `allAvailableCloudProviders`.
2. `PhotoPickerSettingsActivity` stayed disabled, so the picker's cloud settings screen (`am start -a android.provider.action.PICK_IMAGES_SETTINGS`) would not open until a reboot. MediaProvider enables it at boot when cloud media is on.
3. After a reboot, the overrides were still listed by `device_config list_local_overrides` and the settings screen opened. It offered Google Photos and None, with Google Photos already selected (it was the only allowed provider). `dumpsys` showed it as the sync controller's active provider.

Not yet checked: that a manual selection survives a second reboot.

Current state: the four overrides above are still set. Restore with `clear_override` on both flags in both namespaces, then reboot.

## Stock Pixel

Recorded 2026-10-02, read-only over adb. This is a family member's phone with Google Photos in active use as the picker's cloud source, so leave it exactly as found.

| Item | Value |
|---|---|
| Model | Pixel 11 Pro (`grizzly`) |
| OS | Stock Android 17 (SDK 37), build `CD1A.260905.001.B1`, incremental `16238327` |
| Security patch | 2026-09-01 |
| MediaProvider | package `com.google.android.providers.media.module` (Google-signed), APEX `com.google.android.mediaprovider` 370546200, plus overlay `com.android.providers.media.overlay.pixel` |
| Photo picker | the newer picker, `com.google.android.photopicker`, with its activities enabled |
| Google Photos | versionCode 52458705 |

Cloud picker state:

| Flag | `mediaprovider` | `storage_native_boot` |
|---|---|---|
| `allowed_cloud_providers` | `com.google.android.apps.photos.cloudpicker` (server-set) | unset (null) |
| `cloud_media_feature_enabled` | unset (null) | unset (null) |

No local overrides. Sync mode `none`.

MediaProvider's own view (`dumpsys activity provider com.google.android.providers.media.module/com.android.providers.media.MediaProvider`):

```text
isCloudMediaInPhotoPickerEnabled=true
defaultCloudProviderPackage=com.google.android.apps.photos
allowedCloudProviderPackages=[com.google.android.apps.photos]
shouldEnforceCloudProviderAllowlist=true
mCloudProvider=com.google.android.apps.photos.cloudpicker
cachedCloudMediaCollectionInfo=Bundle[{last_media_sync_generation=17230, media_collection_id=61f7a4b0-c99b-412a-a919-2e178fd97aa8-1}]
```

Things to note:

- The effective allow-list (`[com.google.android.apps.photos]`) does not match the `mediaprovider` flag, which holds the provider's *authority*, not its package. It most likely comes from the Pixel overlay's built-in default, with `storage_native_boot` unset. Either way, "read the current flag and append" would produce a wrong list on this phone. Activation should build the list from MediaProvider's effective value and write explicit package names.
- Cloud media is on by default here, from the overlay, with no flag set.
- The `media_provider` shell tool is missing here too, so `dumpsys` is the way to read state on both phones.

**Restore:** the only flag with a value is the server-set `mediaprovider/allowed_cloud_providers`. `clear_override` on anything we override hands control back to that and the overlay defaults.

### No-root allow-list test: passed (2026-10-02)

No APK installed. Our package name was added to the list next to Google Photos, using plain `adb shell` with no root.

1. `device_config override storage_native_boot allowed_cloud_providers com.google.android.apps.photos,com.keithvassallo.ncmediaprovider`: accepted, but MediaProvider's effective list **did not change** while running. This namespace is presumably read only at boot. A reboot was not tried on this phone.
2. Same value in the `mediaprovider` namespace: accepted, and applied **immediately**: `allowedCloudProviderPackages=[com.google.android.apps.photos, com.keithvassallo.ncmediaprovider]`. Google Photos stayed the active provider, with the same sync generation (17230) and collection ID.
3. `clear_override` on both: no local overrides left, all flags and MediaProvider's view identical to the baseline above.

Unexplained: the server-set `mediaprovider` value (`...cloudpicker`) is ignored, yet a local override of the same flag is honoured live.

Not covered here: a third-party provider actually appearing in this phone's picker. That is the Phase 1 exit test (#10).

Current state: restored to baseline, no overrides set.

## Phase 1.5 on-device test (GrapheneOS, 2026-10-02)

Debug build of Phases 1.1 to 1.4 on the GrapheneOS phone, listing the Memories timeline folder of Keith's server.

### Activation

`device_config override` of `allowed_cloud_providers` to `com.google.android.apps.photos,com.keithvassallo.ncmediaprovider.debug` in both namespaces applied immediately: MediaProvider listed the provider in `allAvailableCloudProviders` with no reboot, and it could be selected next to Google Photos.

### Local network protection blocks LAN servers

The server's name resolves to a LAN address (192.168.96.0/24). Every connection from the app failed while the shell connected fine. Tested as the app's UID with `adb shell run-as <package> toybox nc -w 4 <host> <port>`:

| Target | Before the permission | After |
|---|---|---|
| 1.1.1.1:443 (public) | reachable | reachable |
| LAN router, port 80 | blocked | not retested |
| LAN server, port 443 | blocked | reachable |

App-ops showed `ACCESS_LOCAL_NETWORK: ignore` with a rejection at each attempt. Apps targeting API 37 need the runtime permission `android.permission.ACCESS_LOCAL_NETWORK` to reach private addresses. Declaring it and requesting it from the setup screen fixed it. `pm list permissions` does not show this permission; `dumpsys package permissions` does.

### Background network restriction

`dumpsys netpolicy` showed `blocked=APP_BACKGROUND` for the app's UID whenever its process was not in the foreground:

- screen off, setup screen nominally on top: process state `TPSL` (top, sleeping), blocked;
- when MediaProvider called the provider, the UID's firewall rule switched to allow within a millisecond, then back to blocked about 5 to 10 seconds after the call returned.

A listing started on the app's own thread after `onGetMediaCollectionInfo` returned therefore timed out. Network work has to happen while MediaProvider is calling (the first `onQueryMedia` now waits for the listing, which works) or in a scheduled job.

### App updates deselect the provider

Reinstalling the debug APK (`adb install -r`) made MediaProvider log `Cloud provider changed successfully. Old: com.keithvassallo.ncmediaprovider.debug.cloudmedia. New: null`. Replacing the package briefly removes it, and the selection is not restored. Keith had to select the provider again from the picker's menu.

### Listing and sync

- 14,073 images listed in about 7 seconds once the network was allowed; MediaProvider then pulled all rows in 500-row pages in under 5 seconds.
- Because the first sync started while generation 0 was advertised, MediaProvider immediately asked for changes since 0 and received the whole library again. Per-row generations (Phase 2) avoid this.
- `Listing stopped early: a full page shared one modification time`: more than 1,000 files on this server share one modification second, so date-window paging alone can't list them all. The listing is incomplete until PLAN 2.2 handles this.

### Thumbnails and originals

- A cancelled thumbnail request (the picker cancels every tile scrolled off screen) surfaced as `IOException: Canceled`, which the back-off gate inherited from the base counted as "server unreachable". One cancellation blanked every thumbnail for 5 seconds, 302 failed tiles in 46 seconds of scrolling. Fixed: cancellations and missing previews no longer arm the back-off. Fast scrolling then kept loading.
- The server itself answered 60 thumbnails, four at a time, in a median 0.09 s (slowest 5.1 s, generated on demand).
- Picking a photo returned the original: `onOpenMedia` handed over 927,816 bytes in 271 ms, byte for byte the server's `image/heic` file.

### Apps with their own photo grid

Messenger's attach screen never called the provider. It holds full photo and video access and reads the phone's media database directly; cloud-provider photos are never in that database, only in the picker. Apps like that can't show a cloud library. WhatsApp offers the system picker behind its folder button, and it showed the Nextcloud library there.

## Phase 2 on-device checks (GrapheneOS, 2026-10-02)

### WorkManager jobs keep network access

The sync runs as a WorkManager job with a network constraint. With the screen off and the app not in the foreground, it listed the server normally: background network restriction (Phase 1.5) does not apply to a running job. First import: 16,896 images in 7 batch commits (generation 8) in 121 s. A second full listing with no changes kept generation 8.

### Listing speed on the phone

Per-request timing (debug build, 1,000-file pages of about 690 KiB):

| | Network | Parse |
|---|---|---|
| Foreground, after the date fix | 170 to 310 ms | 305 to 476 ms |
| Background job, after the date fix | 170 to 430 ms | 2.4 to 6.2 s |
| Background job, before the date fix | 230 to 300 ms | 3.5 to 6.7 s |

- Parsing `getlastmodified` through `ZonedDateTime` cost several ms per file on Android (the JVM caches what Android looks up each time); parsing Nextcloud's fixed format by hand fixed that.
- A background job then still parses about 10 times slower than the foreground, with no growth in work: Android runs background jobs on restricted CPU. A full background listing of this library takes about 1.5 minutes in a debug build. Fine for a first import or a weekly check, too slow for checking changes each time the picker opens: that is what the folder-etag walk (PLAN 2.4) is for.
- The commit that compares 16,896 unchanged rows took 11 to 26 s in the background, also CPU-bound.

### Exit test: changes arrive incrementally

Keith changed files in a subfolder (`Photos/2026/09`) from his computer, with the picker open in between:

| Change on the server | In the picker | MediaProvider's sync |
|---|---|---|
| Added a photo | appeared | `MEDIA_INCREMENTAL`, generation 8 to 9 |
| Replaced it with a file of the same name | updated | `MEDIA_INCREMENTAL`, 9 to 10 |
| Deleted it, toggled a favourite | gone; favourite shown | `MEDIA_INCREMENTAL`, 10 to 12 |

The only reset in the log came first: `MEDIA_FULL_WITH_RESET` when Keith reselected the provider after reinstalling, which is expected. A move was not tried on the phone; the move logic is covered by unit tests and the integration test.

## Phase 3 matching (GrapheneOS, 2026-10-02)

### What is on the phone

MediaStore lists 158 images and 18 videos (161 of them visible to the app). Most are screenshots, ChatGPT and messaging-app images. Of the 26 camera photos (`DCIM/Camera`), 24 are in the library: the Nextcloud app uploaded them to `Photos/2026/09` with their name and size unchanged.

### Dates are an hour apart

For all 24, Nextcloud's date taken was 3,599 or 3,600 s later than MediaStore's. Both come from the same EXIF date, read in different time zones (Nextcloud also drops the milliseconds). A "date within 2 seconds" rule would never have fired, so date rules allow whole time-zone offsets.

Screenshots and most messaging-app images have no date taken in MediaStore, so only name and size can match them.

### Timing

| Matching run (phone dozing, debug build) | Time |
|---|---|
| All 16,895 rows read and checked in Kotlin | 8.4 s (1.3 s reading, 6.9 s matching) |
| SQLite picks out rows with a phone item's size or name (24) | 0.6 s |

The first run matched the 24 photos and moved each to a new generation; later runs changed nothing.

### Exit test

After Keith reselected the provider, MediaProvider rebuilt its copy of the library (`MEDIA_FULL_WITH_RESET`, generation 13). For each of the 24 matched rows it logged, at verbose level, a failed insert (`UNIQUE constraint failed: media.local_id, media.is_visible`), a failed retry as an update, and nothing more: it then stored the row hidden, behind the visible phone copy. The same 24 lines in Phase 1.5 were this path too. The photos showed once, and picking them never reached the provider's `onOpenMedia`, so nothing was downloaded.

## Phase 4 checks (GrapheneOS, 2026-10-02)

### The shell can select the provider

`adb shell content call --uri content://media --method get_cloud_provider` reports the selected provider. After an install cleared it (`null`), `--method set_cloud_provider --extra cloud_provider:s:<authority>` returned `true`, and `get_cloud_provider` and `dumpsys` then named this app. So Shizuku, which runs as the shell, can select the app after activation and again after every update.

### Reselecting after an update

A `MY_PACKAGE_REPLACED` receiver declared with `exported="false"` still receives the broadcast. The first test failed: a sync that ran 2 s after the install saw the provider deselected and recorded the user as having deselected it, before the reselect job ran. A deselection seen after an update no longer counts as the user's choice. On the second run the job found the provider deselected and, without Shizuku, posted the notification.

### Exit test: a fresh install (GrapheneOS, 2026-10-02)

The debug app was uninstalled and the new build installed with nothing hard-coded. Uninstalling switched the picker back to Google Photos; the allow-list overrides survived it, so only the selection was needed.

| Step | Result |
|---|---|
| Sign in | About 15 s from Continue to the folder picker: `ACCESS_LOCAL_NETWORK` asked for first, then the server's login page in the browser (Vanadium), then the app picked up the grant. |
| Folders and first import | 16,896 images from 2 folders in 24 s in the foreground (68 s as a background job before); the 24 photos also on the phone matched straight after. |
| Picker | MediaProvider rebuilt from the new collection with no errors. |
| Photo keyboard | Messenger accepts `image/png`, `image/gif`, `image/jpeg` and `image/webp`; a JPEG went in within 574 ms and Messenger took it. |
| Send from Nextcloud | Two bugs, both fixed, then Element X twice and Reddit once, each receiving the original (3.9 and 4.0 MB). |

Send from Nextcloud's two bugs:

- **The share failed silently.** The picker grants read access to the activity that receives its result, and Android revokes it when that activity finishes. The share sheet starts the chosen app on the sender's behalf, after the sender had already finished: `SecurityException: UID … does not have permission to content://media/picker/…`. The activity now stays, invisibly, until the share sheet returns.
- **The next send reopened Element.** The chosen app's share screen ran in the sender's task, which outlived the share, and the shortcut brought that task back. The chosen app now gets its own task, and the shortcut and tile start a cleared one.

The picker downloads the originals of the selected items before returning (`SelectedMediaPreloader`, 475 ms for 4 MB), so the receiving app reads them from the cache in 4 to 9 ms.

### Sign-out and a revoked app password (GrapheneOS, 2026-10-02)

- **Sign-out** revoked the app password and cleared the phone; the picker showed only the phone's photos. Signed out, the provider first still reported an account name ("Not set up"), so the picker announced "photos from Not set up"; it now reports no account, which is the picker's state for "set up an account".
- **Revoking the app in Nextcloud's "Devices & sessions"**: the next sync's first request was refused (one 401, after about 5 s), the app stopped all requests and cancelled its syncs, the remote-wipe check ran in its own job and found no wipe, and the "Sign in to Nextcloud again" notification appeared. Signing in again as the same user resumed with a change check, without reimporting.
- **Vanadium wedged twice**, once after each sign-in: neither the sign-in tab nor the full browser would load the server (blank page) while a laptop could, and only a force stop of Vanadium fixed it. The app made no failed requests that could have triggered Nextcloud's throttling. Both times the user left the sign-in tab by going home rather than closing it. Worth checking with other browsers (PLAN 8).

## Phase 5 checks (GrapheneOS, 2026-10-02)

### Streaming needs a foreground service

The first send of a 1 GB video to Element X failed. The picker's preload opened the stream and returned in 3 s, and Element's transcoder read about 70 MB, then every read failed with `Unable to resolve host`. The firewall log shows why: while MediaProvider or the visible picker is calling the app it is bound-foreground or bound-top and its network is allowed; 5 s after the last call the rule flips to blocked (`10221-background-default`). A dataSync foreground service, started from inside the open call, keeps it open: the second send read the whole 1,083 MiB in 125 s, 542 requests, no failed reads. Android allowed the start because the app was bound-top (the picker on screen, bound to it); the picker opens files during its preparing step, while it is still on screen.

### WorkManager can run a job without the job's network exemption

Straight after an install, WorkManager ran a sync job inside the app's own process rather than through JobScheduler. Android blocked the app's network 3 s in and WorkManager stopped the job, but the duration reads kept going until they timed out. Long sync steps now stop when their job is stopped; the rescheduled run went through JobScheduler and had network.

### Video durations

Core Nextcloud keeps none, so the picker showed 00:00. Reading the `moov`/`mvhd` box: 4 small requests a video took 0.7 s each (about 180 ms per request on Keith's server); 64 KiB windows, four videos at a time, read 500 in 39 s, none unreadable. `PXL_20260315_154241316.mp4`: 199 s.

### Preview playback is silent

Previews play quickly, the large video included, from the server or the phone's copy. Sound doesn't: Android logs `AudioHardening background playback muted` for the app although the picker has it bound and on top, and refuses its audio focus requests (`Audio focus request blocked by hardening`), after which ExoPlayer paused the video. A media-playback foreground service started from the picker's call didn't change that; Android noted that a foreground service started from the background gets no camera, microphone or location access, and audio control seems to be withheld the same way. The picker requests audio focus itself when the user unmutes, so the player no longer does.

### Google Photos previews are silent too

With Google Photos as the picker's cloud source, its cloud videos also play muted, and unmuting doesn't bring sound (nor pause them); videos on the phone play with sound, because the picker plays those itself. So the silence is Android's rule for every cloud provider, not something this app does wrong.

### Thumbnail sizes

The picker's grid asks for 264 to 291 px tiles on the Pixel Fold (both screens), and the full-screen preview for 2076 by 1913. Tiles above 256 px went to the 1024 px bucket, ten times the data per tile; tiles up to 300 px now get 256 px previews, which looked sharp enough on the inner screen. The pre-cache fetched about 28 thumbnails a second at first, 15 KB each.

### Thumbnails made on the phone

For servers without HEIC or video previews, `PhoneThumbnailsDeviceTest` checks on the phone that a video frame read through Range requests and a HEIC decoded from its download both give 256 px squares (passed). It runs against the default test server through an adb tunnel, with `am instrument` so the app and its data stay installed (Gradle's connected tests uninstall the app afterwards):

```
tools/testserver/up.sh 35 default
adb reverse tcp:8035 tcp:8035
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.keithvassallo.ncmediaprovider.debug.test/androidx.test.runner.AndroidJUnitRunner
adb reverse --remove tcp:8035
```

## Phase 6 checks (GrapheneOS, 2026-10-03)

### The update and the first Memories read

The schema 7 migration kept all 18,348 rows' values; the first syncs then read every video's header (1,453 in three batches of 500, 1,444 with a recording time) and Memories' whole timeline. Afterwards 18,070 rows take Memories' dates, 16,399 dates differ from the listing's, and 276 live-photo videos are reported to the picker as deleted, leaving 18,072. Where both exist, a video's header time agrees with Memories within 2 s for 1,108 of 1,167.

Later syncs find no day whose count changed, so they cost two small requests (the version and the 60 KB day list).

### Background requests were slow this morning

The phone took 173 s for the 20 Memories requests that took 2.8 s from the laptop on the same Wi-Fi, and 118 s for 500 video headers that took 32 to 39 s the evening before. Change checks rose from about 0.9 s to 3 to 4 s. Not confirmed, but the likely cause is that the screen was off with the app in the background (`procState=TRNB`), where Wi-Fi power saving and Android's limits on background apps slow each request. Reading Memories now stops after 2 minutes and stores the days it has read, so the next sync carries on from there instead of starting over.

## Photo keyboard prototype (GrapheneOS, 2026-10-02)

A keyboard showing the 30 newest Nextcloud photos, inserting the tapped one through the keyboard content API (branch `proto/photo-keyboard`, debug builds only). Tested in Messenger, whose own photo grid never sees a cloud provider:

- Messenger declares it accepts `image/png`, `image/gif`, `image/jpeg` and `image/webp` from keyboards. No HEIC, no video.
- Tapping a 3 MB JPEG original fetched it and inserted it in 400 ms; Messenger accepted it.
- All thumbnails loaded while the keyboard was open: an active keyboard has network access.
- Enabling the keyboard with `adb shell ime enable` skips the trust warning a user sees when enabling it in Settings.
