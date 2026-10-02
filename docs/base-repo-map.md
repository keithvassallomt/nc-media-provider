# Base repo map: 9dc/immich-media-picker v0.4.2

Phase 0.3 (#4). Written 2026-10-02 from a read of the whole base repo at the pinned tag. Only the MIT repo was read. The GPL projects (Dreaming-Codes/immich-cloud-media, 9dc/immich-cloud-media) were not opened.

Kotlin paths below are relative to `app/src/main/java/io/github/immichmediapicker/` unless they start with `app/`. Line numbers are for tag v0.4.2.

## 1. Pinned version

| Item | Value |
|---|---|
| Tag | `v0.4.2`, commit `faf00c4`, 2026-09-27 |
| Newer releases | None. v0.4.2 is the latest tag, the latest GitHub release and the tip of `main`. |
| History | 8 commits, all by one author (`9dc`), 2026-08-05 to 2026-09-27. The first is "Initial sanitized Immich media picker v0.1.1". |
| Repo state | MIT, not archived, 5 stars, 0 open issues |
| Size | About 3,100 lines of Kotlin in one `:app` module, plus 260 lines of tests |

v0.4.2 matters more than its number suggests. Its single commit ("Stop incremental syncs from wiping the Immich catalog") is what made the base follow two of the rules PLAN.md credits it with:

- `onQueryMedia` now lists `EXTRA_SYNC_GENERATION` among its honoured args. Before this, any server change made MediaProvider reject the next incremental sync and rebuild the whole cloud catalog.
- Changes are announced with `MediaStore.notifyCloudMediaChangedEvent`. Before this, the base called `notifyChange` on its own URI, which nobody observes.
- The collection ID is derived from the stored account, not the decrypted key, so a Keystore hiccup no longer flips it.

Anything older than v0.4.2 gets these wrong, so the pin is correct and must not be relaxed.

## 2. Architecture

One Gradle module, `:app`, package `io.github.immichmediapicker`. Views with ViewBinding, no Compose, no DI, no Room, no WorkManager.

| Package | Classes | Role |
|---|---|---|
| `provider` | `ImmichCloudMediaProvider` | The `CloudMediaProvider`. Caller check, collection info, cursors, previews, originals, and the API 36 search/category methods. |
| `data` | `ImmichRepository` | Process-wide singleton facade. Owns everything below and holds the sync logic. |
| | `ImmichApi`, `ImmichJson`, `ImmichUrl`, `Models` | OkHttp client for the Immich REST API, `org.json` parsing, URL building, data classes |
| | `CredentialStore` | Server URL, account and API key, with the key encrypted by an Android Keystore AES-GCM key |
| | `SyncStateStore` | Generation counter, collection epoch, deletion map and per-generation time baselines, all in SharedPreferences |
| | `MediaDiskCache` | LRU file cache: previews (256 MiB) and originals (2 GiB) |
| | `RemoteQueryGate` | Back-off gate: after a reachability failure, metadata calls fail fast for a few seconds |
| `local` | `LocalMediaIndex` | In-memory snapshot of the phone's MediaStore, used to fill `MEDIA_STORE_URI` and to hand over local files |
| `activation` | `CloudProviderActivation`, `DeviceConfigUserService`, `IActivationService.aidl` | The device_config values, and a Shizuku user service that writes them as the shell user |
| `ui` | `SetupActivity`, `ActivationCommands` | The single screen: connect, media permission, activation status, Shizuku, adb commands, sync status, cache usage |

How a picker session flows through it:

```text
MediaProvider / picker
 │
 ├─ onGetMediaCollectionInfo ── reads prefs only ── returns collection ID, generation, account name
 │      └─ scheduleSyncCheck ─► SYNC_EXECUTOR thread (at most once per 30 s)
 │             └─ ImmichRepository.pollChanges
 │                   ├─ ImmichApi.sync (Immich sync stream), or
 │                   │  ImmichApi.assetsChangedSince + assetIdsTrashedSince (fallback)
 │                   ├─ SyncStateStore.record ─► generation + 1, deletions, time baseline
 │                   └─ MediaStore.notifyCloudMediaChangedEvent
 │
 ├─ onQueryMedia(page token, sync generation, album)
 │      └─ ImmichRepository.queryAssets ─► RemoteQueryGate ─► ImmichApi.assets
 │             (live POST /api/search/metadata on the binder thread, 4 s timeout)
 │      └─ LocalMediaIndex.find per row ─► MEDIA_STORE_URI
 │
 ├─ onQueryDeletedMedia ─► SyncStateStore.deletedSince (prefs only)
 ├─ onQueryAlbums ─► ImmichApi.albums (live, cached 5 min)
 ├─ onOpenPreview ─► MediaDiskCache ─► preview slot (4) ─► ImmichApi.downloadPreview
 └─ onOpenMedia ─► MediaDiskCache ─► local MediaStore file ─► original slot (2) ─► ImmichApi.downloadOriginal
```

The key fact for us: **the base has no local library database.** Every `onQueryMedia` page is a live Immich request. Its "sync engine" is a counter, a wall-clock baseline per generation and a small deletion map. Everything Phase 2 builds (Room snapshot, per-row generations, deletion journal, etag walk) is new.

## 3. File-by-file classification

Starting point was PLAN.md 0.3. Corrections are explained in section 3.2.

### 3.1 Table

| File | Class | Reason |
|---|---|---|
| **Build and project** | | |
| `build.gradle.kts` | Adapt | Bump AGP to 9.3.1 and remove the `kotlin.android` plugin (AGP 9 has Kotlin built in). See section 9. |
| `settings.gradle.kts` | Adapt | Rename `rootProject.name`. Repositories are fine. |
| `gradle.properties` | Keep | AndroidX, non-transitive R, 2 GB heap. Nothing Immich-specific. |
| `gradle/wrapper/gradle-wrapper.properties` | Adapt | Gradle 8.13 to 9.6.1. |
| `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` | Keep | Standard Gradle wrapper (Apache-2.0). Regenerate with the wrapper task when upgrading. |
| `app/build.gradle.kts` | Adapt | New namespace and application ID, SDK levels, new deps (Room, WorkManager, Media3 later), turn off the dependency-info block for F-Droid. Keep the env-var release signing and the `.debug` suffix. |
| `app/proguard-rules.pro` | Adapt | Only change is the package in the `-keep` rule for the Shizuku user service (line 7). |
| `.gitignore` | Keep | Merge with ours. Already ignores `local.properties` and keystores. |
| `.github/workflows/android.yml` | Adapt | Good base for PLAN 9.7: test, lint, assemble. Change JDK and artifact names. |
| `.github/workflows/release.yml` | Adapt | Signed release from a tag, checks versionName against the tag, attaches APK and sha256. Rename only. |
| `LICENSE` | Keep | Its copyright notice must be carried into ours (section 10). |
| `README.md` | Replace | We have our own. Keep the attribution. |
| **Manifest and resources** | | |
| `app/src/main/AndroidManifest.xml` | Adapt | Keep the provider declaration (exported, `MANAGE_CLOUD_MEDIA_PROVIDERS`, `CLOUD_MEDIA_PROVIDER` filter, `${applicationId}.cloudmedia`), the Shizuku provider and the backup exclusion. Rename classes, add a `<queries>` entry for Google Photos, decide on cleartext. |
| `res/layout/activity_setup.xml` | Adapt | Card layout is reusable. The Immich connection card becomes Login Flow v2 plus folder picker; activation card gains restore and reboot items. |
| `res/values/strings.xml` | Adapt | All Immich wording changes. Several strings promise a reboot and "four DeviceConfig values", which will no longer be true. |
| `res/values/themes.xml`, `res/values-night/themes.xml` | Adapt | Rename `Theme.ImmichMediaPicker`. |
| `res/values/colors.xml` | Adapt | Our own brand colours. |
| `res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml` | Replace | New icon. |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | Keep | Adaptive icon wrapper, points at the new drawables. |
| `res/mipmap-anydpi/ic_launcher.xml` | Drop | Dead with minSdk 34: the `-v26` version always wins. |
| `res/xml/data_extraction_rules.xml` | Keep | Excludes everything from cloud backup and device transfer, as PLAN 4.2 wants. |
| `res/xml/network_security_config.xml` | Keep | Trusts system and user CAs (PLAN 4.2). It also allows cleartext; decide whether we want that. |
| **AIDL** | | |
| `app/src/main/aidl/.../IActivationService.aidl` | Adapt | Needs more than `activate()`: read state, write an explicit list, restore. |
| **Kotlin** | | |
| `ImmichPickerApplication.kt` | Adapt | Empty `Application`. Rename; it becomes the place to set up WorkManager and Room. |
| `activation/CloudProviderActivation.kt` | Adapt | Writes the wrong values for us (replaces the allow-list, always enables cloud media, always reboots). Section 5. |
| `activation/DeviceConfigUserService.kt` | Adapt | The Shizuku mechanism is right; the payload and the `put` fallback are not. Section 5. |
| `data/CredentialStore.kt` | Adapt | Keystore code stays as is. Fields change to base URL, login name, user ID and app password. |
| `data/ImmichApi.kt` | Replace | Immich REST. Worth copying two patterns: the same-origin auth interceptor (lines 22-31, exactly what PLAN 1.2 needs for Basic auth) and the cancellable download-to-file (311-348). |
| `data/ImmichJson.kt` | Replace | Immich JSON. Our replacement parses WebDAV multistatus XML. |
| `data/ImmichRepository.kt` | Replace | Its sync logic is Immich-only. Salvage the media-delivery half: `openOriginal`, `openPreview`, `withSlot` and the semaphores (lines 36-62, 269-366), `notifyPickerOfChanges` (118-129) and `collectionId` (98-106). |
| `data/ImmichUrl.kt` | Replace | Immich `/api` paths. The normalisation ideas (strip trailing segments, reject credentials in the URL, subpath support) carry over to a Nextcloud base URL. |
| `data/MediaDiskCache.kt` | Keep | Generic. Callers choose the key, so we key by fileId, etag and size. Rename the `immich_media` directory and one error message. |
| `data/Models.kt` | Replace | Immich data classes. Keep the generic `Page<T>`. |
| `data/RemoteQueryGate.kt` | Keep | Generic back-off gate. Only the exception message mentions Immich. |
| `data/SyncStateStore.kt` | Replace | Time baselines and an 8-generation deletion map in prefs. Replaced by Room tables (PLAN 2.1). |
| `local/LocalMediaIndex.kt` | Adapt | Matching logic is close to PLAN 3.2, but it is typed to `ImmichAsset` and leans on Immich's `deviceAssetId`. |
| `provider/ImmichCloudMediaProvider.kt` | Adapt | Keep the shell: caller check, collection info, honoured args, extras, cursor building, preview and open wrappers, sync scheduling. Rewire queries to Room. Drop lines 143-277 and 465-490 (API 36 methods and projections). |
| `ui/ActivationCommands.kt` | Adapt | Becomes enable, restore and (maybe) legacy command sets. |
| `ui/SetupActivity.kt` | Adapt | Keep the Shizuku flow (lines 44-101, 292-425), activation status (267-282), picker settings and test buttons, cache card. Replace the connect flow. |
| **Tests** | | |
| `app/src/test/.../data/ImmichJsonTest.kt` | Replace | Becomes WebDAV parser tests over saved fixtures. |
| `app/src/test/.../data/ImmichUrlTest.kt` | Replace | Becomes base-URL and DAV path tests. |
| `app/src/test/.../data/RemoteQueryGateTest.kt` | Keep | Tests a kept class. |
| `app/src/test/.../ui/ActivationCommandsTest.kt` | Adapt | Must assert the new explicit list and restore commands. |

**Counts (48 tracked files):** Keep 12, Adapt 23, Replace 12, Drop 1.

Kotlin and AIDL only (17 main files): Keep 2, Adapt 9, Replace 6, Drop 0. Tests (4): Keep 1, Adapt 1, Replace 2.

### 3.2 Where the code corrects PLAN.md 0.3

- **"Keep the provider shell" is really Adapt.** The class imports `ImmichAsset`, `ImmichRepository` and `Page`, serves live network queries, and holds all the API 36 methods. The structure is what we keep.
- **The network gate lives in two places.** `RemoteQueryGate` is a separate file (Keep), but the download semaphores and `withSlot` are inside `ImmichRepository` (Replace). They must be carried over by hand.
- **The credential store, local index, setup screen and Shizuku activation are Adapt, not Keep.** Each is tied to Immich types or writes values that are wrong for us.
- **`ImmichRepository` and `SyncStateStore` are also Replace.** PLAN listed only API, JSON, model and URL classes. The repository's sync half and the whole state store are Immich-shaped.
- **"Drop" has no files of its own.** People, smart search and the API 36 additions are methods inside the provider plus code inside files we replace anyway. Method-level drops:

| Where | What |
|---|---|
| `provider/ImmichCloudMediaProvider.kt:143-277` | `onGetCapabilities`, `onQueryMediaCategories`, `onQueryMediaSets`, `onQueryMediaInMediaSet`, `onQuerySearchSuggestions`, both `onSearchMedia`, `searchCursor` |
| `provider/ImmichCloudMediaProvider.kt:351-360, 391, 426-433, 465-490` | `pagedCollectionExtras`, `personMediaId`, search and people constants, the API 36 projections |
| `data/ImmichRepository.kt:216-236, 244-261, 299-301, 316-321` | Person assets, smart search, people lists, person thumbnails |

Without an `onGetCapabilities` override, all capabilities default to off, which is what we want.

## 4. Where the base does each picker behaviour PLAN relies on

| Behaviour | Where | Notes |
|---|---|---|
| Per-account collection ID | `data/ImmichRepository.kt:98-106`; `data/CredentialStore.kt:51-58`; `data/SyncStateStore.kt:13-14, 97-108` | SHA-256 of `serverUrl\|accountId\|provider-v3\|epoch`, first 12 bytes in hex, prefixed `immich-`. Read from plain prefs (`account()`), never the Keystore. Unconfigured: `immich-unconfigured-v1`. Our hash input becomes server, user, folder set and epoch (PLAN 1.4). |
| Collection info | `provider/ImmichCloudMediaProvider.kt:51-78` | Collection ID, `LAST_MEDIA_SYNC_GENERATION`, `ACCOUNT_NAME`, and `ACCOUNT_CONFIGURATION_INTENT` set to the app's launcher intent. |
| Collection ID on every cursor | `provider/ImmichCloudMediaProvider.kt:344-349, 362-364` | `EXTRA_MEDIA_COLLECTION_ID` plus the next `EXTRA_PAGE_TOKEN`. Cursors reuse the collection captured by the last `onGetMediaCollectionInfo` (`advertisedCollection`, line 31) so one sync pass sees one collection. |
| Honoured args | `provider/ImmichCloudMediaProvider.kt:98-110` (media), `119-124` (deleted media) | `ContentResolver.EXTRA_HONORED_ARGS` as a string ArrayList. Media: `EXTRA_PAGE_SIZE`, `EXTRA_PAGE_TOKEN`, plus `EXTRA_ALBUM_ID` and `EXTRA_SYNC_GENERATION` when present. Deleted media: `EXTRA_SYNC_GENERATION` only; the page token is ignored, which happens to be robust to Android 17 passing the media token in. Albums: no honoured args. |
| Notifying the picker | `data/ImmichRepository.kt:118-129` | `MediaStore.notifyCloudMediaChangedEvent(resolver, "<pkg>.cloudmedia", collectionId())`. A `SecurityException` when we're not the active provider is caught and logged. Called from the provider after a detected change (`ImmichCloudMediaProvider.kt:375-381`) and from the setup screen on connect, disconnect and refresh (`ui/SetupActivity.kt:203, 225, 481-490`). |
| Thumbnail vs full file in `onOpenPreview` | `provider/ImmichCloudMediaProvider.kt:295-314`; `data/ImmichRepository.kt:292-314` | Reads `EXTRA_PREVIEW_THUMBNAIL`. Thumbnail requested: server thumbnail, small if the requested size is at most 512 px, large otherwise. Full preview of a **video**: the whole original is downloaded and returned. Full preview of an image: the large server preview. |
| Network off the binder thread / the 100 ms path | `provider/ImmichCloudMediaProvider.kt:51-78, 366-383, 435-440`; `data/ImmichRepository.kt:131-136` | `onGetMediaCollectionInfo` never touches the network: change polling runs on a single daemon thread, guarded by `SYNC_IN_FLIGHT` and a 30 s throttle. **Only that call is network-free.** See the caveats below. |
| Bounding the calls that do block | `data/RemoteQueryGate.kt:9-44`; `data/ImmichRepository.kt:36-62, 340-353`; `data/ImmichApi.kt:362-384` | Metadata calls: 4 s timeout, then 30 s of fast failure. Previews: 4 slots, give up after 2 s waiting, 15 s timeout, 5 s back-off. Originals: 2 slots, wait up to 60 s, 120 s timeout. |
| Keystore credential storage | `data/CredentialStore.kt:25-42, 63-81, 89-105` | AES-256-GCM key generated in `AndroidKeyStore` (non-exportable), ciphertext and IV in prefs, decrypted value cached in memory. Backups off: `AndroidManifest.xml:17-18` plus `res/xml/data_extraction_rules.xml`. |
| Local MediaStore index for dedupe | `local/LocalMediaIndex.kt:33-54, 75-99, 125-194` | Snapshot of images and videos (`_ID`, name, size, date taken, MIME), rebuilt on a low-priority thread, invalidated by a ContentObserver, 30 min TTL. Match order: Immich `deviceAssetId` (corroborated), then exact name and size, then the same name and type within 2 s. Used for `MEDIA_STORE_URI` (`ImmichCloudMediaProvider.kt:319, 333`) and for local delivery (`ImmichRepository.kt:276-283`). Warmed in `onCreate` (`ImmichCloudMediaProvider.kt:36-49`). |
| Shizuku activation | `ui/SetupActivity.kt:44-101, 292-425`; `activation/DeviceConfigUserService.kt`; `IActivationService.aidl`; `AndroidManifest.xml:11-13, 45-51`; `app/proguard-rules.pro:7` | A Shizuku user service in an `:activation` process runs `/system/bin/device_config` with `ProcessBuilder`, as the shell user when Shizuku was started over wireless debugging. |
| Caller check | `provider/ImmichCloudMediaProvider.kt:398-406` | Allows its own UID, otherwise requires the caller to hold `MANAGE_CLOUD_MEDIA_PROVIDERS`. Called at the top of every entry point. |
| Allowed and active status | `ui/SetupActivity.kt:267-282` | `MediaStore.isSupportedCloudMediaProviderAuthority` and `isCurrentCloudMediaProviderAuthority`. Public API, no Shizuku or dumpsys needed. Keep this for PLAN 4.6. |

Caveats on the 100 ms path:

- `ImmichCloudMediaProvider.kt:76` calls `repository.isConfigured`, which is `credentials.load()`. On a cold process that is a Keystore decrypt (two binder calls to keystore2 plus an AES-GCM init, per the base's own comment at `CredentialStore.kt:18-21`). It is not network, but it is on the 100 ms path. Our version should check the plain-prefs `account()` there.
- The first touch of the repository singleton (OkHttp client, two ContentObserver registrations) can also land inside `onGetMediaCollectionInfo`. `onCreate` warms it on the executor, but that is a race, not a guarantee.
- `onQueryMedia`, `onQueryAlbums`, `onOpenPreview` and `onOpenMedia` all do network on binder threads. PLAN's research summary ("it keeps slow network calls from blocking the picker") is true only in the sense that these calls are bounded. Our Room-backed queries fix listing properly; previews and originals stay on the binder thread by nature, so the slot and timeout scheme is what to carry over.

## 5. Activation

### 5.1 What the base writes

`activation/CloudProviderActivation.kt:10-15` defines four values, always the same:

| Namespace | Flag | Value |
|---|---|---|
| `mediaprovider` | `allowed_cloud_providers` | our package only |
| `mediaprovider` | `cloud_media_feature_enabled` | `true` |
| `storage_native_boot` | `allowed_cloud_providers` | our package only |
| `storage_native_boot` | `cloud_media_feature_enabled` | `true` |

**adb text** (shown in the app and copied by "Copy commands"; `ui/ActivationCommands.kt:6`, `activation/CloudProviderActivation.kt:17-25`):

```sh
adb shell device_config override mediaprovider allowed_cloud_providers <pkg>
adb shell device_config override mediaprovider cloud_media_feature_enabled true
adb shell device_config override storage_native_boot allowed_cloud_providers <pkg>
adb shell device_config override storage_native_boot cloud_media_feature_enabled true
adb reboot
```

`<pkg>` is `BuildConfig.APPLICATION_ID`, so debug builds write `io.github.immichmediapicker.debug`. A `put` variant (`ActivationCommands.legacyEnable`) exists but only the tests use it; the README tells ColorOS users to swap `override` for `put` by hand.

**Shizuku path** (`activation/DeviceConfigUserService.kt:15-24, 26-39, 61-77`): runs `/system/bin/device_config override` for all four, then verifies each with `device_config get` and a string compare. If anything fails, it repeats the whole set with `put`. It returns which verb worked, and the success dialog tells the user to restart once.

Answers to the specific questions:

- **Replace or append?** Replace. The list is a single package, so Google Photos is removed.
- **Sets `cloud_media_feature_enabled`?** Yes, always, in both namespaces.
- **Uses the `media_provider` shell tool?** No, nowhere. It also never runs `dumpsys`.
- **Restore path?** None. There is no `clear_override` or `delete` anywhere in the code.

### 5.2 Compared with docs/device-notes.md

| Device finding | Base behaviour | Gap |
|---|---|---|
| An explicit package list is needed; the stock Pixel's flag holds an authority MediaProvider ignores | Writes only our package | Google Photos disappears as a cloud source. Breaks PLAN's "Google Photos stays selectable". |
| `mediaprovider` overrides apply live | Always ends with `adb reboot`; dialog always says restart | Reboot is unnecessary on the stock Pixel and on a second activation. |
| `storage_native_boot` is read at boot | Writes both namespaces | Matches. |
| GrapheneOS needs `cloud_media_feature_enabled=true` plus a reboot before the cloud settings screen opens | Always writes the flag and always reboots | Works on GrapheneOS by accident. On the stock Pixel it adds a needless override (cloud media is already on through the overlay). |
| `media_provider` tool unavailable | Not used | No change needed. |
| State is read with `dumpsys activity provider <MP pkg>/com.android.providers.media.MediaProvider` | Not used; verification is `device_config get` | `get` proves the flag was stored, not that MediaProvider accepted it. |
| Restore is `clear_override` per flag | No restore | Must be added. |

### 5.3 What must change (Phase 4.6, and the Phase 1.5 commands)

1. Build the allow-list explicitly: Google Photos' package (`com.google.android.apps.photos`) when installed and the user wants to keep it, plus our package, comma-separated. Detecting Google Photos needs a `<queries>` entry for it; the manifest only declares Shizuku today.
2. Shizuku path: read MediaProvider's effective state from `dumpsys` (`allowedCloudProviderPackages=[...]`, `isCloudMediaInPhotoPickerEnabled=`), detect the MediaProvider package (`com.google.android.providers.media.module` or `com.android.providers.media.module`), merge, then write.
3. Write `cloud_media_feature_enabled=true` only where cloud media is off.
4. Make the reboot conditional: prompt only when the picker's cloud settings screen is not yet enabled (GrapheneOS first run), not as part of every command block.
5. Verify through MediaProvider, not `device_config get`: `isSupportedCloudMediaProviderAuthority` turns true immediately because the `mediaprovider` override applies live. Add the dumpsys lines to diagnostics.
6. Add restore: `device_config clear_override <ns> <flag>` for every flag written, as adb text and as a Shizuku action. Extend the AIDL accordingly.
7. Drop the automatic `put` fallback, or keep it as a documented manual option with matching `device_config delete` restore commands. `put` writes the server-synced value: on the stock Pixel that flag is server-set and may be overwritten, and `clear_override` does not undo a `put`.
8. Use the real package in commands, including the `.debug` suffix during development (`com.keithvassallo.ncmediaprovider.debug`), as the base does.

## 6. Dependence on Immich's server-side "changed since"

Every piece of the base's change tracking leans on Immich answering "what changed since X":

| Base piece | Where | What it relies on |
|---|---|---|
| Sync stream | `data/ImmichApi.kt:198-231`; `data/ImmichJson.kt:122-142` | `POST /api/sync/stream` with server-side checkpoints, delete events, and acks. The base notes Immich 3.x refuses this for API keys, so in practice the fallback below runs. |
| Change poll | `data/ImmichApi.kt:87-127`; `data/ImmichRepository.kt:415-464` | `/api/search/metadata` with `updatedAfter` (changed assets) and `trashedAfter` + `withDeleted` (trashed asset IDs). |
| Incremental `onQueryMedia` | `data/ImmichRepository.kt:199-208`; `data/ImmichApi.kt:55-81` | Maps the picker's `EXTRA_SYNC_GENERATION` to a wall-clock baseline (`SyncStateStore.kt:56-80`), subtracts 10 min for clock skew, and asks Immich for assets updated after it. Unknown baseline: lists the whole library. |
| Hard deletes | `data/ImmichRepository.kt:466-478`, constant at 495 | Assets deleted without the trash are invisible to the poll, so the base bumps the collection epoch weekly. That empties the picker's cloud tab while it rebuilds. |
| Deletions for the picker | `provider/ImmichCloudMediaProvider.kt:113-126`; `data/SyncStateStore.kt:82-84` | A JSON map of asset ID to generation in prefs. |

What our sync engine (PLAN Phase 2) replaces:

| Base | Ours |
|---|---|
| Sync stream / `updatedAfter` / `trashedAfter` | Folder etag walk (2.4) and listing diff (2.3) against the Room snapshot |
| Wall-clock baseline per generation, server query on the binder thread | Per-row `generation` column; `onQueryMedia` reads `WHERE generation > since` from Room, no network |
| Live Immich page numbers as page tokens | Tokens with `m:` / `d:` prefixes, pinned top generation and an offset (2.3) |
| Deletion map in prefs, last 8 generations | Room deletion journal (2.1) |
| Weekly epoch bump to catch hard deletes | Weekly full listing diff that writes journal rows without changing the collection ID (2.2) |
| Every row reports the current generation | Every row reports the generation it last changed in |

Keep from the base: the 30 s throttled background check triggered from `onGetMediaCollectionInfo` (`ImmichCloudMediaProvider.kt:366-383`, `ImmichRepository.kt:131-136`), which is exactly PLAN 2.6, and the `advertisedCollection` trick that keeps one collection per sync pass.

One latent base bug to design around: `SyncStateStore.record` keeps deletions for only 8 generations (`SyncStateStore.kt:61-62, 146`). A picker that last synced more than 8 generations ago silently misses older deletions, and nothing forces a full sync. Our journal needs a retention floor, and if the picker asks for deletions below it, we must change the collection ID rather than return an incomplete list.

## 7. Video and streaming

- **No `CloudMediaSurfaceController`.** `onCreateCloudMediaSurfaceController` is not overridden, so the picker gets the default (null). PLAN 5.4 is all new work.
- **No `openProxyFileDescriptor`**, no Range requests, no pipes. `onOpenMedia` downloads the whole original into the 2 GiB originals cache (or hands over a matching local MediaStore file) and returns a plain file descriptor (`ImmichRepository.kt:269-290`). That satisfies "complete, seekable file", but a large video must finish downloading within the 120 s call timeout (`ImmichApi.kt:383`), after waiting up to 60 s for one of 2 slots.
- **Video preview** (`onOpenPreview` without the thumbnail flag) also downloads the whole original (`ImmichRepository.kt:303-309`), so opening a large video's preview in the picker can take minutes and fill the cache.
- Duration is reported as null when unknown (`ImmichCloudMediaProvider.kt:327`); PLAN 2.8 plans to report 0.

## 8. Build setup

### 8.1 Versions

| Item | Base v0.4.2 |
|---|---|
| Gradle | 8.13 |
| AGP | 8.11.2 |
| Kotlin | 2.2.20 (`org.jetbrains.kotlin.android` plugin) |
| Java / JVM target | 17 / 17 |
| minSdk / compileSdk / targetSdk | 34 / 36 / 36 |
| Build features | AIDL, BuildConfig, ViewBinding |
| Release | R8 minify and resource shrinking; signing from `ANDROID_KEYSTORE_*` env vars, fails fast if only some are set |
| Debug | `applicationIdSuffix = ".debug"` |

### 8.2 Dependencies and licences

Direct dependencies (`app/build.gradle.kts:87-100`). Licences marked POM were read from the artifacts' POM files; the rest are the projects' published licences.

| Dependency | Version | Licence | Notes |
|---|---|---|---|
| androidx.activity:activity-ktx | 1.10.1 | Apache-2.0 | |
| androidx.appcompat:appcompat | 1.7.1 | Apache-2.0 | |
| androidx.core:core-ktx | 1.16.0 | Apache-2.0 | |
| androidx.lifecycle:lifecycle-runtime-ktx | 2.9.1 | Apache-2.0 | |
| com.google.android.material:material | 1.12.0 | Apache-2.0 (POM) | No Play Services dependency |
| com.squareup.okhttp3:okhttp | 4.12.0 | Apache-2.0 | Pulls okio 3.6.0 (Apache-2.0, POM) |
| dev.rikka.shizuku:api | 13.1.5 | MIT (POM) | Pulls `aidl`, `shared` 13.1.5, both MIT (POM) |
| dev.rikka.shizuku:provider | 13.1.5 | MIT (POM) | |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.10.2 | Apache-2.0 | |
| junit:junit (test) | 4.13.2 | EPL-1.0 | Test only, not shipped |
| org.json:json (test) | 20250517 | Public Domain (POM) | Test only. Post-2022 release, so no "Good, not Evil" clause. |

The resolved release runtime tree contains only AndroidX, Kotlin, JetBrains annotations, Guava `listenablefuture`, Error Prone annotations, JSpecify, okio, Material and Shizuku artifacts. All are Apache-2.0 or MIT. Nothing is copyleft, proprietary or Play Services, so nothing blocks F-Droid on licence grounds.

Two F-Droid build points that are not licence issues:

- AGP embeds an encrypted dependency-info block in the APK signing block. F-Droid's scanner rejects it, so set `dependenciesInfo { includeInApk = false; includeInBundle = false }`. The base does not.
- `androidx.profileinstaller` comes in transitively. Baseline profiles have caused non-reproducible builds before; check this when we set up reproducible builds (PLAN 9.2).

### 8.3 Tests and CI

14 JVM unit tests in 4 classes, all passing here: `ImmichJsonTest` (5), `ImmichUrlTest` (4), `RemoteQueryGateTest` (3), `ActivationCommandsTest` (2). No instrumented tests, no Robolectric. `unitTests.isReturnDefaultValues = true`, and `org.json` is added for tests because Android's copy is a stub on the JVM.

Tip for our parser tests: Android's `XmlPullParser` is also a stub in JVM tests. `javax.xml.parsers` (DOM or SAX) exists both on Android and in the JDK, so a parser built on it can be tested with plain JUnit against the Phase 0.2 fixtures.

CI (`.github/workflows/android.yml`) runs `testDebugUnitTest lintDebug assembleDebug` on Temurin 17. `release.yml` builds a signed release from a `v*` tag.

## 9. Toolchain

### 9.1 What was tried here

All builds ran in the scratchpad clone. Machine: system JDK 27; `~/.gradle/gradle.properties` sets `org.gradle.java.home=/opt/android-studio/jbr` (JBR 25.0.3); a Temurin 21 sits in `~/.gradle/jdks`; SDK platforms 36, 36.1 and 37.0.

| # | Setup | Result |
|---|---|---|
| 1 | Base as-is, `./gradlew assembleDebug` | **Failed** in 12 s. Gradle 8.13 started its daemon on JBR 25 (from `~/.gradle/gradle.properties`) and died with the one-line error `25.0.3`. Gradle 8.13 cannot run on Java 25. |
| 2 | Base as-is, daemon on Temurin 21 (`-Dorg.gradle.java.home=<jdk21>`) | **Built** in 1 min 11 s, 14/14 tests pass. AGP auto-installed Build-Tools 35.0.0 into `~/Android/Sdk`. |
| 3 | As 2, compileSdk and targetSdk 37 | Built, with the warning "We recommend using a newer Android Gradle plugin to use compile SDK version 37.0". |
| 4 | AGP 9.3.1, Gradle 9.6.1, compileSdk and targetSdk 37, default JDK setup (JBR 25) | **Built** in 50 s, tests pass, no warnings. Three edits: plugin version, remove the `kotlin.android` plugin and the `kotlin { compilerOptions }` block (AGP 9 has built-in Kotlin), wrapper version. Used the existing Build-Tools 36.0.0. |

AGP 9.3.1 with Gradle 9.6.1 and Kotlin 2.4.10 is the combination Keith's Ari project already uses with compileSdk 37 on this machine.

### 9.2 API differences between the installed platforms

A `javap` diff of the `CloudMediaProvider`, `CloudMediaProviderContract` and `MediaStore` cloud APIs:

- 36 to 36.1 adds `MEDIA_CATEGORY_TYPE_USER_ALBUMS` and `Capabilities.Builder.setAlbumsAsCategoryEnabled`. Relevant to PLAN Phase 7: the newer picker may want albums as a category.
- 36.1 to 37 changes nothing in the cloud media API.
- API 37 adds `android.permission.ACCESS_LOCAL_NETWORK`.

### 9.3 Recommendation for PLAN 0.3

- **compileSdk 37, targetSdk 37, minSdk 34.** Android 17 is released, both test phones run SDK 37, and the platform is installed. There is no cloud-media API reason to prefer 36 or 37, so target the platform our users actually run.
- **AGP 9.3.1, Gradle 9.6.1, Kotlin 2.4.10** through AGP's built-in Kotlin. Proven on this machine; AGP 8.11 only warns about SDK 37 today and Gradle 8.13 cannot run on the JBR that this machine's Gradle config selects.
- **JDK:** run Gradle on JBR 25 (the existing user config) or JDK 21; keep the bytecode target at 17. JDK 27 is fine as the launcher, since Gradle forks its daemon on the configured JVM; running the daemon on 27 was not tried. Pin the daemon JVM in the repo (Gradle's daemon JVM criteria file) so the command line, the IDE and CI agree. CI moves from Temurin 17 to 21.
- **Check before relying on targetSdk 37:** the new `ACCESS_LOCAL_NETWORK` permission. If Android 17 enforces local network protection for apps targeting 37, a Nextcloud server on a LAN address will need that permission granted during setup, because the provider makes its requests in the background on the picker's behalf. Test against a LAN-only server in Phase 1.

## 10. MIT notice to keep

From the base's `LICENSE`, verbatim:

```text
MIT License

Copyright (c) 2026 Immich Media Picker contributors
```

followed by the standard MIT permission and warranty text, identical to ours. The holder is "Immich Media Picker contributors", year 2026. No individual is named; the only committer is GitHub user `9dc`.

Our `LICENSE` currently carries only "Copyright (c) 2026 Keith Vassallo". Once any base code is copied in (Phase 1.1), add the base's copyright line above or below ours in the same file. The permission text is identical, so one notice covers both. Keep the README credit.

## 11. Surprises, risks and contradictions with PLAN.md

1. **No local database.** PLAN's architecture diagram says the provider is "kept" and only the backend changes. In the base, the provider pages straight through to Immich, so swapping the backend means rewiring every query to Room. This is the main reason the provider is Adapt rather than Keep.
2. **Network is bounded, not avoided.** Only `onGetMediaCollectionInfo` is network-free. Media, albums, previews and originals all block binder threads on HTTP (section 4).
3. **Keystore on the 100 ms path.** `isConfigured` decrypts the key on a cold process inside `onGetMediaCollectionInfo` (section 4). Easy to fix, easy to copy by mistake.
4. **The rules PLAN credits are five days old.** The honoured `EXTRA_SYNC_GENERATION` and `notifyCloudMediaChangedEvent` arrived in v0.4.2 (2026-09-27). The base is young (8 commits, one author, two months), built and tested for an OPPO Find X9 Pro on ColorOS 16, with no mention of Pixels or GrapheneOS.
5. **The GPL caveat is confirmed by the history.** The very first commit is titled "Initial sanitized Immich media picker". The risk assessment in PLAN stands; nothing in the code needs to change because of it.
6. **Activation removes Google Photos** and always reboots (section 5). PLAN already knew about the replace; the `put` fallback, the reboot, the `get`-based verification, the missing restore and the missing `<queries>` entry are new findings.
7. **Deletions older than 8 generations are lost** (section 6). Our journal must handle a picker that falls far behind.
8. **Originals cache is keyed by asset ID only** (`ImmichRepository.kt:272`) and holds up to 2 GiB. An edited file on the server would be served stale. Our keys must include the etag. PLAN mentions a 256 MiB thumbnail cache but no originals cache size; 2 GiB of LRU originals sits uneasily with "never keep a full local mirror" on small phones and probably wants a setting (PLAN 8.2).
9. **Video goes through full downloads only** (section 7). Large videos in `onOpenMedia` and in video previews depend on finishing within 120 s. Phase 5.2 and 5.4 are all new code.
10. **Orientation.** The base sets the `ORIENTATION` column from EXIF while serving server-rendered thumbnails. This is the double-rotation question PLAN 5.1 already lists as unverified; the base gives no evidence either way.
11. **Date taken can be 0.** The base forces size to at least 1 byte but sends a date of 0 when Immich has none, which PLAN's research says makes the picker drop the row. PLAN 2.9's safety net covers this.
12. **Column order.** The base's projection (`ImmichCloudMediaProvider.kt:442-455`) has all 12 media columns but in its own order. PLAN 1.4 wants AOSP's order; whether order matters is still on PLAN's unverified list.
13. **Local index timing.** Rows go out without `MEDIA_STORE_URI` if the local snapshot is not ready, and MediaProvider caches them that way (the base's own comment, `ImmichCloudMediaProvider.kt:38-41`). With Room, a local match found later must bump that row's generation so the picker picks it up.
14. **`uses-permission MANAGE_CLOUD_MEDIA_PROVIDERS`** in the manifest (line 9) is never granted to a user app. Harmless; the provider's `android:permission` is what matters.
15. **Cleartext is allowed app-wide** (`usesCleartextTraffic="true"` and the network security config) for LAN Immich servers. PLAN doesn't decide this for Nextcloud.
16. **Build side effects on this machine** from the trial builds: Build-Tools 35.0.0 was installed into `~/Android/Sdk/build-tools/35.0.0`, and the Gradle 8.13 distribution was downloaded into `~/.gradle/wrapper/dists/`. Both can be deleted.

Suggested PLAN.md edits, for whoever updates it:

- Decisions table, Toolchain row: compileSdk and targetSdk 37, AGP 9.3.1, Gradle 9.6.1, Kotlin 2.4.10, JVM target 17, daemon on JDK 21 or 25.
- 0.3 Keep list: move the provider shell, credential store, local index, setup screen and Shizuku activation to "Adapt", and add `ImmichRepository` and `SyncStateStore` to Replace.
- Research findings, base repo: "keeps slow network calls from blocking the picker" should read "bounds slow network calls and keeps them off `onGetMediaCollectionInfo`".
- Phase 2: add the deletion-journal retention floor and the "local match found later bumps the row" rule.
- Phase 4.6: add the `<queries>` entry, drop the automatic `put` fallback, verify with MediaProvider instead of `device_config get`.
- Risks: add `ACCESS_LOCAL_NETWORK` for LAN servers when targeting 37.
