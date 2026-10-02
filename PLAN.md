# nc-media-provider: project plan

**Status (2026-10-02):** Phase 0 is complete: activation needs no root on either test phone (see [docs/device-notes.md](docs/device-notes.md)), the server matrix is in [docs/server-notes.md](docs/server-notes.md), and the base repo map is in [docs/base-repo-map.md](docs/base-repo-map.md). Phase 1 is done except the 1.5 run on the stock Pixel. Phase 2 is built and its exit test passed on the GrapheneOS phone (see the device notes). Phase 3 passed its exit test. Phases 4 and 5 passed their exit tests on the GrapheneOS phone; Phase 5's previews are silent, an Android limit shared by Google Photos. Phase 6 (the Memories layer) is next.

Progress is tracked in [GitHub issues](https://github.com/keithvassallomt/nc-media-provider/issues): one issue per phase, with each numbered sub-task below as a sub-issue.

## Goal

nc-media-provider is an Android app that implements Android's `CloudMediaProvider` API, so a user's Nextcloud photo library appears as the cloud source in the system photo picker. Any app that opens the system picker can then attach photos and videos straight from Nextcloud. Apps with their own photo grid, such as Messenger, read the phone's media database directly and never see a cloud provider (confirmed in Phase 1.5); for those, a "Send from Nextcloud" shortcut (4.7) picks through the system picker and shares into them.

```text
An app that opens the system picker (WhatsApp's folder button, a browser upload, …)
   ↓
Attach photo
   ↓
Android system photo picker
   ↓
Nextcloud library appears as the cloud source
   ↓
User browses remote photos
   ↓
Selected item is fetched on demand
   ↓
The app receives the original file
```

The user should never need to download a photo first, browse Nextcloud through the generic file picker, keep a local copy of the whole library, or upload it to Google Photos.

Core requirements:

1. Authenticate against a user-provided Nextcloud server.
2. Find the images and videos in the user's photo library.
3. Implement `CloudMediaProvider` so that remote media appears in the picker.
4. Serve thumbnails and metadata without downloading originals.
5. Download or stream the original only when an item is selected.
6. Support photos and videos.
7. Cache metadata and thumbnails locally so the picker stays fast.
8. Never keep a full local mirror of the library.

It must work whichever gallery the user prefers (Nextcloud Photos, Memories or another frontend). All of them sit on top of ordinary Nextcloud files, so the app integrates with Nextcloud itself rather than with any one frontend.

## Constraints

- **No root, ever.** Activation uses only shell-level privileges: `adb shell`, or Shizuku over wireless debugging. If either test phone turns out to need root, the project stops. Never propose root, Magisk or similar workarounds.
- **Any Nextcloud server.** The app is for public release. Features are detected at login and the app degrades gracefully when a server lacks them. Keith's server is one test target, not the design target.
- **MIT, with clean GPL hygiene.** The provider shell comes from 9dc/immich-media-picker (MIT). Dreaming-Codes/immich-cloud-media and 9dc/immich-cloud-media are GPL: study their designs, never copy their code.
- **Google Photos stays selectable.** Where Google Photos is a cloud provider, activation writes an allow-list that still includes it.
- **No "Nextcloud" in the name.** Nextcloud's [trademark guidelines](https://nextcloud.com/trademarks/) forbid the mark in an app's name, and for store distribution say plainly that "Nextcloud" can't be used in the app's name. "X for Nextcloud" isn't allowed either. A free, non-commercial project may say it is compatible with Nextcloud, so the store description can say it works with Nextcloud.

## Decisions

| Topic | Decision |
|---|---|
| App name | nc-media-provider |
| Application ID | `com.keithvassallo.ncmediaprovider`. Hyphens are invalid in application IDs. `com.keithvassallo.nc_media_provider` is the alternative if separators are wanted. It must be final before the first public release, because users type it into the adb command. |
| Provider authority | `${applicationId}.cloudmedia`, as in the base repo |
| License | MIT, keeping the base repo's notice |
| Base repo | [9dc/immich-media-picker](https://github.com/9dc/immich-media-picker), pinned at v0.4.2 |
| Toolchain | minSdk 34, compileSdk and targetSdk 37, AGP 9.3.1, Gradle 9.6.1, Kotlin 2.4.10 (AGP's built-in Kotlin), JVM bytecode target 17, Gradle daemon on JDK 21 or 25 (pinned in the repo), CI on JDK 21. Settled in Phase 0.3. |
| Library source of truth | Core Nextcloud WebDAV SEARCH. Memories only enriches. |
| Library scope | Folders the user picks, pre-filled from Memories' `timeline_path` when detected, plus common folders such as `/Photos` and `/InstantUpload` |
| Test devices | Pixel 11 Pro on stock Android 17, and Pixel 10 Pro Fold on GrapheneOS (Android 17). The stock phone is a family member's daily phone: read-only by default, writes only with consent, always restored afterwards. |
| Nextcloud versions | Official support for the releases still maintained upstream (33 to 35 as of 2026-10; 32 reached end of life in September 2026). Older servers run with reduced features. |

Keith's own server runs both Photos and Memories and holds about 20,000 items. Keith is its admin, so features can be switched on there for full-feature testing. His Pixel auto-uploads to it, so most recent photos exist both on the phone and on the server.

## Architecture

```text
Android system photo picker
        │
        ▼
CloudMediaProvider             adapted from 9dc/immich-media-picker
        │
        ▼
Repository + sync engine       new: Room snapshot, generations, deletion journal
        │
        ├── Nextcloud core     WebDAV SEARCH and PROPFIND, /core/preview, WebDAV GET with Range
        ├── Nextcloud Photos   albums (optional)
        └── Memories           dates, dimensions, video duration, live photos (optional)
```

The picker-facing contract follows the base repo. The provider's internals are adapted: the base pages straight through to Immich on every query, while ours answers from the local database. See [docs/base-repo-map.md](docs/base-repo-map.md).

## Research findings

### Base repo

9dc/immich-media-picker is MIT licensed, active, and at v0.4.2. Of the reference projects, it's the only one that follows the picker's rules correctly:

- it gives each account its own collection ID;
- it reports which query arguments it handled, in the exact form the picker expects;
- it tells the picker when the library changes, using the right system call;
- it sends thumbnails or full files depending on what the picker asked for;
- it keeps the network off `onGetMediaCollectionInfo` and bounds other network calls with timeouts (it has no local database, so media queries still go to the server);
- it stores credentials in the Android Keystore.

Dreaming-Codes' original is GPL, has had no maintainer since April, and gets several of these rules wrong.

One caveat: 9dc wrote the MIT repo as a "sanitized" rewrite of their own GPL fork, and some patterns carry over. The risk looks low, but it isn't a clean-room rewrite.

### Nextcloud has no change feed

The base repo depends on Immich's server-side "what changed since" queries. Nextcloud has nothing equivalent: the WebDAV sync REPORT doesn't work on files, and there are no deletion records.

We therefore keep a local snapshot database that gives each row a sync generation and keeps a deletion journal, then compare each server listing against it. 9dc's GPL fork has this design. We rebuild it from the design without copying code.

### Picker traps (confirmed in the AOSP source)

- Rows with no date taken, a size of 0 or no MIME type are silently dropped.
- A malformed `media_store_uri` silently aborts the rest of a sync, yet the sync position is still saved. Items then vanish until the next full sync.
- `onGetMediaCollectionInfo` gets 100 ms in one code path, so it must never touch the network. On Android 17, repeated failures there can get the provider deselected.
- `onOpenMedia` must return a complete file that can be read at any position, because apps read it through the system file layer with a 3-minute timeout. Pipes are ruled out.
- Video preview on Android 17 needs a `CloudMediaSurfaceController`. Without one, the new picker only uses its own player if a particular system flag is on.

### Nextcloud SEARCH pitfalls

- The offset element must be in the SearchDAV namespace. In any other namespace it is silently ignored, so a naive pager loops forever.
- If `nresults` is left out, results are capped at 100.
- Paging by offset is unreliable, because results can't be sorted by fileid.

### Server defaults

- HEIC and video previews are off until enabled. They need imagick with HEIC support, and ffmpeg.
- Core Nextcloud only reads EXIF dates from JPEG and WebP. HEIC and video dates come from a date in the file name (`PXL_`, `VID_`, `IMG_yyyymmdd_hhmmss`, read in the server's timezone), otherwise the modification time. A video's own creation time is ignored. Memories fixes this. Confirmed on Nextcloud 33 and 35 in Phase 0.2.
- Real servers often lack metadata entirely: 390 of the 500 newest JPEGs on Keith's server had none, so fallbacks are the common path, not an edge case.

### Activation

- Two flags matter: `allowed_cloud_providers` and `cloud_media_feature_enabled`. Set them in both namespaces, `mediaprovider` and `storage_native_boot`, then select the provider in the picker's cloud settings.
- No root is needed, confirmed on both test phones (Phase 0.1). The plain `adb shell` user can write both flags with `device_config override`. Shizuku uses the same adb-level permission, started through wireless debugging.
- On both phones (MediaProvider module 37), a `mediaprovider` override applies immediately. A `storage_native_boot` override does not apply while the phone is running, and is presumably read at boot. Writing both covers either case.
- The flag's current value is not a reliable base to append to. On the stock Pixel, `mediaprovider/allowed_cloud_providers` holds a server-set *authority* (`com.google.android.apps.photos.cloudpicker`) that MediaProvider ignores, while the effective list (`[com.google.android.apps.photos]`) comes from the Pixel overlay's default. Activation must write an explicit list of package names.
- Defaults differ by OS. Stock Pixel: cloud media is on and Google Photos is allowed and active. GrapheneOS: cloud media is off and the allow-list is empty, so Google Photos isn't a cloud source at all. There, `cloud_media_feature_enabled=true` is also needed, and the picker's cloud settings screen (`PhotoPickerSettingsActivity`) only becomes enabled after a reboot.
- Only one cloud provider can be active at a time. The base repo's commands replace the allow-list, which removes Google Photos.
- Updating the app deselects it: replacing the package briefly removes it, and MediaProvider resets the cloud provider to none without restoring it (seen in Phase 1.5). The app can't select itself.
- Restoring the original state is `device_config clear_override` for each overridden flag, which hands control back to the server and overlay defaults.
- The `media_provider` shell tool is not available on either phone. Read cloud picker state with `dumpsys activity provider <MediaProvider package>/com.android.providers.media.MediaProvider`: the package is `com.google.android.providers.media.module` on stock Pixels and `com.android.providers.media.module` on AOSP builds such as GrapheneOS.
- The picker UI also differs. The stock Pixel uses the newer picker (`com.google.android.photopicker`). GrapheneOS uses the older picker built into MediaProvider.

## Supporting any Nextcloud server

Every feature is detected at login, with a fallback when the server lacks it.

| Server condition | Detected by | Fallback |
|---|---|---|
| HEIC previews off (the default) | 404 for a HEIC preview | Read the HEIC's built-in thumbnail on the phone through small Range requests, then cache it |
| Video previews off (the default) | 404 for a video preview | Grab a frame on the phone from the streamed file, then cache it |
| Photo metadata never generated | `nc:metadata-photos-*` missing | Use the modification time as the date taken; leave dimensions empty |
| Nextcloud older than 30 | `status.php` | One SEARCH per folder instead of one combined SEARCH |
| Upload-time search unsupported (before 32.0.7) | capabilities | Rely on folder etags only |
| Photos app disabled | 404 on its WebDAV root | Hide albums |
| Memories absent | `/api/describe` returns 404 | Use core Nextcloud values |
| End-to-end encrypted folders | folder flag | Exclude them from the folder picker |
| Proxy timeouts (e.g. Cloudflare's 100 s) | request timeout | Smaller pages, then retry |
| Nextcloud in a subfolder, or no pretty URLs | Login Flow returns the base URL | Build every path from that base URL |

Paging uses date windows only. X-NC-Paginate was considered and dropped, because date windows work on every server and it removes an optional code path.

## Phases

| # | Phase |
|---|---|
| 0 | Groundwork: no-root check, server matrix, base repo review |
| 1 | Proof of concept |
| 2 | Sync engine |
| 3 | Duplicate handling |
| 4 | Accounts, settings and activation |
| 5 | Media delivery and video |
| 6 | Memories layer |
| 7 | Albums |
| 8 | Robustness and offline |
| 9 | Release |

Each phase ends with an exit test. On-device exit tests are run by Keith, with the exact commands and log lines to watch supplied when the phase is handed over.

### Phase 0: Groundwork (no app code)

**0.1 No-root activation check (Keith, on device).** This is a strict go/no-go. It proves that a sideloaded provider can be activated on both phones without root, so any later failure is clearly our code and not the platform.

- First record the current flag values in both namespaces and MediaProvider's cloud picker state from `dumpsys`. These are needed to restore the phone later.
- Quickest test: [trajano/cloud-media-provider-proxy](https://github.com/trajano/cloud-media-provider-proxy) passes the Nextcloud Files app's documents through as a cloud provider. Use it only as a test tool, never as code. It has a bug that forces full resyncs, which doesn't matter here. If it has no prebuilt APK, skip it and fold this check into the Phase 1 exit test.
- Pass, on both phones and without root: the provider appears in the picker's cloud settings, can be selected, and is still there after a reboot. Google Photos is still listed and selectable.

**0.2 Server probe and test matrix (Claude).** Done: results are in [docs/server-notes.md](docs/server-notes.md). The probe is `tools/probe_server.py`, the test servers come from `tools/testserver/up.sh <33|35> <default|full>`, and their fixtures are in `testdata/nextcloud/`.

On Keith's server, a short curl checklist:

- The server version and capabilities: the `dav.search_supports_*` flags, X-NC-Paginate support, and whether a memcache is configured.
- Request a preview by fileId for one JPEG, one HEIC and one MP4. A 404 on HEIC or MP4 means enabling the HEIC or Movie preview provider (plus ffmpeg).
- Check whether `nc:metadata-photos-*` values are present. If not, run `occ files:scan --generate-metadata`.
- Call Memories' `/api/describe` and `/api/config` to get its version and `timeline_path`.
- Check whether Memories albums are simply Photos albums.
- Check whether the video half of each live photo is marked `nc:hidden`.
- Create an app password for the proof of concept and store it in the git-ignored `local.properties`.
- Captures from Keith's server stay in the git-ignored `local/` folder, since they hold private file names, paths and metadata. Committed test fixtures come only from the container servers' synthetic library.

Server test matrix:

- Throwaway Nextcloud containers (Podman, rootless) run the oldest and newest maintained releases: 33 and 35 as of 2026-10.
- Each release runs twice: once with default settings, and once with Photos, Memories, and HEIC and video previews all enabled.
- Each gets the same test library: JPEG, HEIC, MP4, a live-photo pair, nested folders, and a folder shared from another user.
- Keith's server stays the real-world check.

**0.3 Map the base repo (Claude).** Pin v0.4.2 and decide what happens to each part. Done: the file-by-file map is [docs/base-repo-map.md](docs/base-repo-map.md). v0.4.2 is the latest release and the pin must not be relaxed, since it is the release that added the sync-generation and change-notification behaviour we rely on.

- **Keep (12 files):** the network gate (`RemoteQueryGate`) and its test, the disk cache (`MediaDiskCache`), the Gradle wrapper and settings, the backup-exclusion and network-security configs, the launcher icon, `.gitignore` and `LICENSE`.
- **Adapt (23 files):** the provider shell and its caller check, the credential store, the local MediaStore index, the setup and diagnostics screen, and the Shizuku activation.
- **Replace (12 files):** every Immich API, JSON, model and URL class, plus `ImmichRepository` and `SyncStateStore`.
- **Drop for now:** people, smart search and the API 36 additions. These are methods inside adapted files rather than whole files.
- **Toolchain:** compileSdk and targetSdk 37 (see Decisions). The base builds here on AGP 9.3.1 and Gradle 9.6.1 with three build-file edits.

### Phase 1: Proof of concept

**1.1 Skeleton.** Done: the app builds on the agreed toolchain, the provider registers and answers with an empty library, and activation already writes an explicit allow-list that keeps Google Photos, with restore commands and no `put` fallback or forced reboot. Plain HTTP is off until 4.2. Rename the package and strip out Immich. Move the build to the agreed toolchain. The manifest declares the provider exported, protected by the `MANAGE_CLOUD_MEDIA_PROVIDERS` permission, with the `CLOUD_MEDIA_PROVIDER` intent filter. Add the base's copyright line ("Copyright (c) 2026 Immich Media Picker contributors") to `LICENSE` as soon as any base code is copied in. Make sure no Keystore decryption happens inside `onGetMediaCollectionInfo`: the base decrypts there on a cold start.

**1.2 Hard-coded auth.** Done: the debug build signs in on first start and stores the account in the Keystore; `nextcloud.userId` defaults to the login and `nextcloud.folder` sets the one library folder. Debug builds read the server URL, login name, user ID and app password from a git-ignored `local.properties`. HTTP Basic auth is attached only to requests to that server.

**1.3 Minimal client.** Done, with date-window paging from 2.2 already in place. The SEARCH scope must be the raw folder path (a percent-encoded one gives 404). Parser tests run on the Phase 0.2 fixtures, and `NextcloudClientIntegrationTest` runs against a live server when `NC_TEST_URL` is set (passes on Nextcloud 33 and 35). One WebDAV SEARCH over one test folder, images only, held in memory. Each item records fileId, href, etag, MIME type, size, modification time, date taken, dimensions, favorite and hidden.

**1.4 Connect it to the picker.** Done, with two changes from the text below. The generation is persisted with a fingerprint of the listing, because an in-memory counter restarts at 0 after process death and MediaProvider would read that as going backwards. Picker calls that arrive before the first listing wait for it (queries up to 60 s, previews 2 s) instead of answering with an empty library, which MediaProvider would cache.

- **Collection info** comes from memory. The collection ID is a hash of server, user, folder and an "epoch" counter that is increased to force a full rebuild.
- **Loading** happens in the background when the provider starts. When it finishes, the generation goes from 0 to 1 and the picker is notified, so the real change path is exercised from day one.
- **`onQueryMedia`** returns every column, in the order AOSP uses. It also carries the collection ID and reports which arguments it handled, in the form the picker expects. `onQueryDeletedMedia` returns no rows but carries the same extras.
- **`onOpenPreview`** calls `/core/preview?fileId=…&a=1&mode=cover&forceIcon=0` at 256 or 1024 px. When the picker asks for the full file instead of a thumbnail, it returns the original.
- **`onOpenMedia`** downloads the file to the cache and returns it.

**1.5 Activate and smoke test (Keith, on device).** Write an explicit allow-list (Google Photos where present, plus our package) to both namespaces, and on GrapheneOS also enable cloud media and reboot. Select the provider in the picker's cloud settings, open the picker to start a sync, and check `dumpsys` and logcat. On the stock Pixel this also confirms a third-party provider appears in Google's picker, which Phase 0.1 couldn't test without an APK.

**Exit:** the test folder's photos show in the picker on both phones, thumbnails load, and a photo attached in an app that opens the system picker (WhatsApp's folder button, for example) arrives as the original file. This is the go/no-go point for everything after.

### Phase 2: Sync engine

**2.1 Room database.** Done (schema v2): each media row carries its folder and the generation it last changed in; the sync state holds a random instance ID that is part of the collection ID, so a lost database gives a new collection instead of a generation going backwards; a `folder` table holds etags for 2.4.

- **Media table:** fileId, href, etag, MIME, size, modification time, date taken, dimensions, duration, favorite, live-photo partner, source folder, and the generation it last changed in.
- **Deletion journal:** fileId and generation.
- **Sync state:** collection ID, current generation, epoch, a hash of the folder set, each folder's etag, and the time of the last full check.

**2.2 Full listing.** Done. Runs on the first import, in a weekly check, and when "Refresh now" is pressed.

- One SEARCH request covers all selected folders (Nextcloud 30 and later). Older servers get one SEARCH per folder. **Done:** one scope per folder in one request; Nextcloud orders and limits the results across all of them, shared mounts included (checked on 33 and 35). A folder inside another selected folder is dropped from the set. The per-folder fallback for servers before 30 waits for server detection in 4.1.
- Paging uses date windows: each page asks for items modified at or before the last date seen, deduplicated by fileId. Already in place from Phase 1.3.
- **More than a page of files sharing one modification second: done.** Phase 1.5 hit 2,677 files in one second on Keith's server. Results are newest first, so a full page holds every file newer than its oldest second; that second is then fetched whole with an exact-match query (Nextcloud doesn't cap `nresults`), and the next page starts strictly below it. Unit-tested on a fake server, live-tested on Nextcloud 33 and 35 with a burst of files sharing one second, and on Keith's server the paged listing matches one unpaged request (16,896 images). The response parser streams (SAX), so a large page never sits in memory as a document tree.
- Pages hold 500 to 1000 items.
- Drop hidden items, remove duplicates of files reachable through two mounts, and optionally respect `.nomedia` files. SEARCH over the home folder also returns files from folders other users share, from `.nomedia` folders and from outside the photo folders (confirmed in 0.2), so filtering by the selected folders is the app's job. **Done:**
  - A file reachable at two paths keeps one row, under the alphabetically first path, in full listings and change checks alike, so its row doesn't flip between them. When folders are removed from a library that has such files, the change check lists everything instead, because their files may still be reachable at the other path.
  - Folders holding a `.nomedia`, `.noimage` or `.nomemories` file are hidden with everything below them, as Nextcloud Photos and Memories hide them. One extra SEARCH finds the markers (65 ms on Keith's server). A marker that comes or goes, or a change to the setting (on by default; its switch arrives with the settings in Phase 4), triggers a full listing.

**2.3 Turn changes into generations.** Done: a listing is diffed against the stored rows and committed in one transaction under the next generation, only when something changed. Tokens are keyset positions `m:<top>:<generation>:<id>`. Journal rows are pruned after 180 days behind a floor; a picker behind the floor gets a new collection ID. Tested end to end on SQLite under Robolectric.

- Each listing is compared with the database in one transaction. New or changed rows get the next generation, and missing fileIds go into the deletion journal at that generation. The generation only increases when something actually changed.
- Page tokens name their own pass (`m:` for media, `d:` for deletions), fix the top generation for the whole sync, and carry an offset. The prefix matters because Android 17's picker can pass the last media token into the deletions query.
- Keep journal rows long enough that a picker far behind still gets every deletion (the base keeps only 8 generations and silently loses older ones). Prune by age. If a picker asks from before the oldest kept row, change the collection ID to force a full resync.

**2.4 Change detection.** Done, more cheaply than below: one PROPFIND reads the root's etag; if it moved, one SEARCH returns every folder's etag (rather than descending folder by folder), and only folders whose etag changed are re-listed one level deep. A file gone from one changed folder but present in another is a move. Favouriting changes no etag, so favourites have their own SEARCH. On Keith's phone an unchanged library checks in 0.8 s; a full background listing takes over a minute. Walking folder etags is the standard method, not a later upgrade, because other users may have 100,000 items or more.

- Ask each selected folder for its etag with a Depth 0 PROPFIND. Nextcloud updates a folder's etag whenever anything below it changes, including inside mounts.
- If nothing changed, stop. Otherwise descend only into subfolders whose etag changed, as the desktop client does, re-list those, and compare as in 2.3.
- The weekly full listing catches external-storage changes that etags miss.

**2.5 First import.** Done: a WorkManager job commits every 2,000 files and notifies the picker each time (68 s for Keith's 16,896 images in the background). An interrupted import keeps what it committed and the next run skips it as unchanged. The setup screen shows a running sync's progress ("Listing photos: 4000 found so far", or "Checking for changes…"), a queued or retrying sync, and when the library was last checked. Runs as a background job that can resume after interruption and shows its progress in the app. It commits to the database every 2,000 or so items and notifies the picker each time, so photos appear within seconds rather than after the whole library is done.

**2.5a Network only when allowed.** Done and confirmed on the GrapheneOS phone: the WorkManager job keeps network access with the screen off and the app in the background. Android 17 cuts this app's network off whenever its process isn't in the foreground or being called by MediaProvider (Phase 1.5: the firewall rule flips back to blocked 5 to 10 seconds after a call returns). Listing and change checks must run inside MediaProvider's calls or as WorkManager jobs with a network constraint, never on a free thread after a call returns.

**2.6 Triggers.** Done: `onGetMediaCollectionInfo` asks for an expedited job at most every 30 s, a periodic job runs every 6 hours, and every committed change notifies the picker. "Refresh now" runs a full listing, queued after any sync already running: the picker already runs a change check whenever it opens, so the button's job is to catch what change checks miss, such as files on external storage.

- `onGetMediaCollectionInfo` reads only the database, then schedules a background check at most every 30 seconds. Per 2.5a, that check is a WorkManager job, not a plain thread.
- A WorkManager job also checks every few hours, and there is a "Refresh now" button.
- After any committed change, the picker is notified if we are the active provider.

**2.7 When the collection ID changes.** Done: server, user and folder set (a change resets the library), the database instance, and the epoch. Only when the folder set or account changes, the database is lost, or the user asks for a rebuild. A new ID empties the picker's cloud tab while it rebuilds.

**2.8 Fields.** Done for images; video duration arrives with Phase 5. Date taken comes from Nextcloud's photo metadata, falling back to the modification time. On real servers many files have no metadata yet, so the fallback is the common path. Dimensions come from `metadata-photos-size`, which is already in display orientation; HEIC files only get it from Nextcloud 35. Core Nextcloud has no video duration, so videos report 0, which the picker accepts.

**2.9 Safety net.** Done: `PickerRowCheck` drops and logs bad rows before they reach the cursor. One component builds every row returned to the picker and checks it against the picker's rules: a date taken, size above 0, an image or video MIME type, valid type and duration values, and a well-formed `media_store_uri`. Rows that fail are dropped and logged. Unit tests cover it.

**Exit:** when a file is added, deleted, moved or edited on the server, the change reaches the picker without logcat showing a full reset.

### Phase 3: Duplicate handling

Moved up to straight after the sync engine, because an auto-uploading phone has most recent photos both locally and in Nextcloud.

**3.1 Local index.** Done: the base repo's MediaStore index now returns the phone's photo list for matching, cached until MediaStore reports a change. A change schedules a new match 5 s after MediaStore goes quiet. Adapt the base repo's local MediaStore index. This needs permission to read the device's photos and videos.

**3.2 Matching.** The Nextcloud Android app keeps the original file name when it auto-uploads, so a match on name and size covers most cases. For renamed uploads, the fallback is the same size plus a date taken within 2 seconds. The match goes into `media_store_uri`, which makes the picker hide the cloud copy. The URI must be well-formed (see 2.9). MediaProvider caches rows as sent, so a match found after a row went out must bump that row's generation.

Done, with these changes (findings in [docs/device-notes.md](docs/device-notes.md)):

- **Stored, not worked out per query.** The base matched when the picker asked, against a snapshot that was often not ready, and MediaProvider kept whatever went out. Each row now stores its match (schema 4); `LocalMatcher` runs after every sync, after each first-import batch, and when the phone's media changes, and a row whose match changes moves to the next generation.
- **A photo stored twice in Nextcloud still shows once.** Several rows may name the same phone item: MediaProvider keeps each such row hidden. It first tries the row as visible, which its `UNIQUE (local_id, is_visible)` constraint refuses, and logs `UNIQUE constraint failed` at verbose level before storing the row hidden. That is its normal path, once per matched row; Phase 1.5's 24 such lines were the 24 matched camera photos, not an error. A row keeps its match while it still fits, so matches don't flip.
- **Rules, strongest first:** name (ASCII case ignored) and size; then size and date taken; then name, kind and date taken, for copies edited on one side.
- **Dates allow a time-zone offset.** On Keith's phone every match was exactly an hour apart: Nextcloud and Android read the camera's date in different zones. Dates count as equal within 2 s of any whole offset (15-minute steps, up to 14 hours).
- **Fast enough to run every sync.** SQLite picks out rows with a phone item's size or name (every rule needs one or the other), plus rows already matched. Checking all 16,895 rows took 8.4 s on a dozing phone; the filtered match takes 0.6 s.

**3.3 Local delivery.** Done: `onOpenMedia` opens the stored match first and downloads only if the phone copy can't be opened. When a matched item is selected, `onOpenMedia` hands over the local file with no download.

**Exit:** photos that are also on the phone appear once in the picker, and selecting one doesn't download anything. **Passed** on the GrapheneOS phone (2026-10-02): the 24 matched camera photos were each stored hidden behind the phone's copy, and picking them never reached `onOpenMedia`.

### Phase 4: Accounts, settings and activation

**4.1 Login.** Built: Login Flow v2 in a Custom Tab, polling every 3 s and again whenever the user comes back to the app (Android 17 cuts a backgrounded app's network), with the flow kept across the activity being recreated. The user ID comes from OCS `cloud/user`. A server that resolves to a local address gets `ACCESS_LOCAL_NETWORK` asked for first, with an explanation. Server checks and Memories detection beyond the folder picker's needs wait for the features that use them. Live-tested on 33 and 35 up to the browser step; the browser step needs the phone. Nextcloud's Login Flow v2 opens in an in-app browser tab. The app polls every few seconds for up to 20 minutes, and the app name shows in Nextcloud's security settings. The login name may be an email, so the app then looks up the actual user ID, checks the server's features (see "Supporting any Nextcloud server") and detects Memories. If the server resolves to a private address, request `ACCESS_LOCAL_NETWORK` with an explanation: without it Android 17 blocks the connection (Phase 1.5).

**4.2 Credentials.** Done: Keystore-encrypted as before. Plain HTTP is allowed only to local-network addresses (private IPv4 ranges, loopback, link-local, IPv6 unique-local and 100.64.0.0/10 for Tailscale), checked once connected and before anything is sent, with a warning at sign-in. The debug build no longer embeds an account from `local.properties`. Stored with the base repo's Keystore encryption and excluded from backups. The app trusts system and user-installed certificates, for self-hosted servers. Decide on plain HTTP: the base allows cleartext app-wide for LAN servers; ours should at most allow it for LAN addresses, with a warning.

**4.3 Folder picker.** Built: browse with a checkbox per folder; a folder inside a selected one shows as covered. The first selection is Memories' timeline folders (it may list several, separated by ';') plus `/Photos` and `/InstantUpload` where they exist. Encrypted folders (`nc:is-encrypted`) are hidden. Nothing syncs until folders are chosen. The user browses folders and selects several. The selection is pre-filled from Memories' `timeline_path` when available, plus common folders such as `/Photos` and `/InstantUpload`. End-to-end encrypted folders are excluded. Changing the selection warns that the library will be rebuilt.

**4.4 Failures.** Built: a 401 anywhere (sync, thumbnail or original) stops every request at once, cancels the sync jobs, runs the remote-wipe check in a job (`/core/wipe/check` with the app password as the token; a requested wipe clears everything and confirms), and asks the user to sign in again by notification and on the setup screen. Syncs back off exponentially from one minute; a 503 is checked against `status.php` for maintenance mode, and the last error shows in the diagnostics.

- On the first 401, stop all syncing, run Nextcloud's remote-wipe check, and ask the user to log in again.
- Never retry login failures in a loop. Nextcloud slows every request from the user's IP after failures, and after 10 it blocks with 429 errors.
- Back off exponentially on 429 and 503, and detect maintenance mode.

**4.5 Logout.** Built: "Sign out" revokes the app password (`DELETE /ocs/v2.php/core/apppassword`, live-tested on 33 and 35) and then clears everything; the new instance ID gives a new collection ID, which empties the picker's cloud tab. Revoke the app password on the server, clear the database and caches, and increase the epoch.

**4.6 Activation screen,** adapted from the base repo. Built, with one finding that changes the flow: **the shell can select the provider** (`content call --uri content://media --method set_cloud_provider`, checked on the GrapheneOS phone after an install had cleared the selection). So Shizuku activation now selects the app itself, the adb commands end with that call, and after an update a job selects it again through Shizuku, or posts a notification when Shizuku isn't available (tested on the GrapheneOS phone without Shizuku: the notification path). Shizuku reads MediaProvider's effective allow-list and `isCloudMediaInPhotoPickerEnabled` from `dumpsys`, keeps other allowed providers, and offers a restart where the flag needs one. Google Photos stays only if the user keeps a switch on.

- whether the provider is allowed and whether it is active;
- adb commands ready to copy that write an explicit allow-list (Google Photos when installed and the user wants to keep it, plus our package) to both namespaces, and on builds where cloud media is off, also enable it;
- one-tap activation through Shizuku, which can read MediaProvider's effective list from `dumpsys` and write the same thing, then verify the result through MediaProvider rather than `device_config get`;
- a `<queries>` manifest entry so the app can detect whether Google Photos is installed;
- no silent fallback to `device_config put` if `override` fails (the base does this), and no automatic reboot unless one is needed;
- a reboot prompt where the picker's cloud settings screen isn't enabled yet (GrapheneOS);
- after an app update (`MY_PACKAGE_REPLACED`), a notification if the app was the selected cloud provider and no longer is, linking to the picker's cloud settings. Check whether the shell (and so Shizuku) can re-select it, for example through MediaProvider's `set_cloud_provider` call;
- restore commands (`clear_override` for each flag written);
- buttons to open the picker's cloud settings and to test the picker;
- diagnostics: item count, generation, last sync, last error, cache sizes.

Onboarding says plainly that only one cloud source can be active at a time, and that users switch between Google Photos and this app in the picker's cloud settings.

**4.7 "Send from Nextcloud" shortcut.** Built: a dynamic launcher shortcut (a static one would have to spell out the package, which differs in debug builds), a Quick Settings tile and a button in the app. The picker's read grant is forwarded with the share, and receiving apps get the original (Element X and Reddit in the exit test). Two things the phone test found: the sending activity must stay until the share sheet returns, or Android revokes the grant before the chosen app opens; and the chosen app needs its own task, or the next send brings it back. Apps with their own photo grid, such as Messenger, never open the system picker, so cloud photos can't appear in them (Phase 1.5). A launcher shortcut and a Quick Settings tile open the system picker (`PickVisualMedia`), then hand the picked photos to the Android share sheet (`ACTION_SEND` or `ACTION_SEND_MULTIPLE`), so they can go to any app that accepts shares. Forward the picker's read grant with the share intent if Android allows it; otherwise copy the picked files into the cache and share them through a `FileProvider`. Check that the receiving app gets the original, not a preview.

**4.8 Photo keyboard.** Built: newest first with month headings (schema 5 adds a date index), thumbnails from the picker's cache, conversion to JPEG on the phone when the app doesn't take the photo's type, a button back to the previous keyboard, and set-up text on the main screen. Apps that take no images from keyboards get a button to Send from Nextcloud. Checking other apps' accepted types is for the phone test (the keyboard logs them). For apps with their own photo grid, a keyboard (input method) that shows the Nextcloud library and inserts the tapped photo through the keyboard content API, the way GIF keyboards work. A prototype proved it (branch `proto/photo-keyboard`, debug builds only): Messenger declares that it accepts `image/png`, `image/gif`, `image/jpeg` and `image/webp` from keyboards, and it took a 3 MB JPEG that was fetched and inserted in 400 ms; thumbnails loaded while the keyboard was open, so it has network access. To build:

- browse the library (newest first, then by month or album), using the picker's thumbnails and cache;
- send only the types the app declares: convert HEIC and other unlisted types to JPEG (a large server preview, or on the phone). Messenger takes no video from keyboards, so videos go through 4.7;
- a button back to the previous keyboard (`switchToPreviousInputMethod()`);
- onboarding that enables the keyboard and explains Android's "may collect all the text you type" warning: this keyboard never handles text;
- check which types other apps accept (Facebook, Telegram, Signal) with the prototype's status line.

**Exit:** a fresh install gets to a working picker with nothing hard-coded. **Passed** on the GrapheneOS phone (2026-10-02); see docs/device-notes.md.

### Phase 5: Media delivery and video

**5.1 Thumbnails.** Done except the orientation check. The grid asks for 264 to 291 px tiles on the Pixel Fold; tiles up to 300 px get 256 px previews, up to 600 px 512, larger 1024 (the 1024 bucket used to take every tile above 256 px). A photo or video also on the phone uses MediaStore's thumbnail. When the server has no preview, videos get a frame read through Range requests and images are decoded from the downloaded original, checked on the phone against the default test server; Android has no public API for a HEIC's built-in thumbnail, so the Range-request approach below wasn't possible.

- Request 64, 256 or 1024 px, the sizes the server produces anyway.
- Keep 4 to 6 requests in flight, because the server queues preview generation.
- Cancel the download when the picker cancels.
- Cache about 256 MiB on disk, keyed by fileId, etag and size.
- When HEIC previews are off, read the HEIC's built-in thumbnail through small Range requests and cache it. Any other 404 shows a blank tile.
- Orientation: Phase 0.2 confirmed that Nextcloud previews are already rotated and carry no EXIF, and that `metadata-photos-size` is in display orientation. Report orientation 0. Check on a device that the picker's full-size preview, which gets the original file with its EXIF tag, isn't rotated twice.

**5.2 Streaming large files.** Done: videos open as a proxy file descriptor whose reads are 2 MiB Range requests with one chunk read ahead and the etag pinned (`If-Match`). A dataSync foreground service runs while a stream is open: without it Android 17 blocked every read 5 s after MediaProvider's call returned (see the device notes). Element X read a 1 GB video in 125 s with no failed reads, and the picker's preload step returned in seconds.

- For videos and large files, return a file handle whose reads become HTTP Range requests (`openProxyFileDescriptor`).
- Reads are buffered 1 to 4 MiB ahead. The GPL fork makes one request per read, which is slow.
- The size comes from the listing, and the etag is pinned so a file that changes mid-read fails cleanly.
- Images keep the download-to-cache path. The originals cache is keyed by fileId and etag (the base keys by ID only, so an edited file is served stale) and gets a size cap the user can change (8.2).

**5.3 Videos in the library.** Done: one SEARCH for images and videos, hidden live-photo halves left out (5,945 on Keith's server), a listing version that triggers one full listing. Durations are read from each video's `moov` box after each sync, up to 500 a time (core Nextcloud has none). Frame-grab thumbnails for servers without the Movie preview provider are still to do. Include videos in the SEARCH. Their thumbnails need the server's Movie preview provider. If it's missing, grab a frame on the phone from the streamed file and cache it.

**5.4 Video playback in the picker.** Built and working on the GrapheneOS phone without sound: Android's audio hardening mutes the app's playback and refuses its audio focus, even with a media-playback foreground service, because the app isn't the visible one (see the device notes). The picker handles audio focus itself, so the player doesn't ask for it. Still to test on the stock Pixel's newer picker. A `CloudMediaSurfaceController` built on Media3 ExoPlayer streams the WebDAV file with the user's credentials. It handles the display surface, play and pause, mute and loop, and reports playback states to the picker. Dreaming-Codes' version shows how to map those states. Study it, don't copy it: it's GPL. Test on both phones, since the stock Pixel uses the newer picker and GrapheneOS the older one built into MediaProvider.

**5.5 Live photos.** No code was needed for iPhone live photos: hidden files have been left out since the first listing code, and on Keith's server all 5,945 hidden videos are linked to their photo by Nextcloud's `metadata-files-live-photo`, while none of the 1,454 visible ones are. Still to see on the phone: a live photo showing once, as its photo. Pairing by name for other clients is not done. Hide the hidden MOV half of each pair. Optionally, pair an image and a MOV with the same name in the same folder, for live photos uploaded by clients other than iOS. Testing needs a real iPhone live photo with a licence that allows committing it: neither Nextcloud 33 nor 35 detected the synthetic pair in Phase 0.2.

**5.6 Thumbnail pre-cache (optional setting).** Done: a switch and a range (everything, 12 or 3 months) on the setup screen with an estimate and progress; on Keith's phone 15 KB a thumbnail, about 360 MB for 24,000 items, and a run interrupted by an install carried on where it stopped. Images the server can't preview are skipped rather than downloaded. Downloads grid thumbnails ahead of time, so the picker and the 4.7 share flow show the library at once instead of filling in tile by tile. Runs as a WorkManager job (2.5a) on an unmetered network, preferably while charging, newest first, resuming where it stopped, and re-fetching only thumbnails whose etag changed. Thumbnails from Keith's server measured 9 to 14 KB at 256 px (Phase 0.2), so about 150 MB per 14,000 photos: the setting shows that estimate, offers a scope (everything, or the last N months) and gets its own size cap, separate from the 256 MiB on-demand cache. Cache the sizes the picker actually requests: log `onOpenPreview` sizes on both phones first, since a foldable's grid may ask for more than 256 px. Keep to the same 4 to 6 parallel requests as 5.1.

**Exit:** large videos browse, play in the picker preview, and attach to an app within the 3-minute limit. **Passed on the GrapheneOS phone** (2026-10-02), with one platform limit: previews are silent, as Google Photos' cloud previews are too. A 1 GB video attached to Element X through the stream; the picker's preparing step took 3 s. The stock Pixel's newer picker is still to test.

### Phase 6: Memories layer

**6.1 Detection.** Use `/api/describe` and `/api/config`, sending the `OCS-APIRequest` header, and only switch the layer on for Memories versions known to work.

**6.0 Video dates from the file (before the Memories work).** The `mvhd` box that 5.3 already reads for durations also holds the recording time (`creation_time`, seconds since 1904, UTC). Reading it costs no extra request and gives videos a real capture date on any server, where core Nextcloud uses the file name or the upload time. Memories then mainly fixes HEIC photos.

**6.2 Enrichment, not listing.** WebDAV still decides what's in the library. Memories only overrides, by fileId, the date taken, dimensions, video duration and live-photo pairs. This keeps a single sync model, so a Memories outage can't break the library.

**6.3 Isolation.** Any Memories error silently falls back to the core values. Tests against saved responses catch changes to its API, which is undocumented and internal.

**Exit:** HEIC and video items sort by their real capture date. Turning Memories off changes only those fields.

### Phase 7: Albums

**7.1 Photos albums.**

- Read the user's own albums and shared albums from Nextcloud Photos. If the Photos app is disabled, hide albums.
- Prefix album IDs, so none can collide with names the picker reserves (Favorites, Camera and similar).
- Every album needs a cover image, which the new picker requires. Empty albums are skipped.
- Album contents come from listing the album folder.
- Shared-album thumbnails need the Photos preview endpoint, because the core one returns 404 for files outside the user's own folders.
- Check on a device how the picker handles album photos that sit outside the selected folders.

**7.2 Memories albums** need no separate path if Phase 0.2 confirms they're the same as Photos albums.

**7.3 Stretch: People and other categories.** These use faces from Recognize or Memories, through the API 36 categories feature. The picker only shows them when certain system flags are on, so check the Android 17 builds before building this.

### Phase 8: Robustness and offline

**8.1 Offline.** Collection info and queries come from the database, thumbnails from the cache, and opening a file fails quickly instead of hanging. This also stops Android 17 from deselecting the provider after repeated failures.

**8.2 Background tuning.** WorkManager constraints, cache size settings, and a "Clear cache" button.

**8.3 Browser check.** Vanadium twice stopped loading the server after a sign-in through the app's browser tab, until it was force-stopped (see docs/device-notes.md). Check the sign-in with Chrome and Firefox, and whether a sign-in tab left open is what wedges it.

### Phase 9: Release

**9.1 Store listing.** A name without "Nextcloud", plus a "not affiliated with Nextcloud" disclaimer.

**9.2 Channels.** GitHub releases with Obtainium first, with IzzyOnDroid as a quick extra channel. Every planned library (OkHttp, Room, WorkManager, Media3, the Shizuku API) is open source, so F-Droid with reproducible builds is possible. Google Play is deferred until its policies are checked, since the app needs adb to work at all and duplicate handling needs broad photo-library access.

**9.3 Onboarding.** Explains adb and Shizuku to people who aren't developers.

**9.4 README.**

- a table of activation commands for each Android version;
- GrapheneOS notes;
- how to keep Google Photos selectable;
- how to restore the original settings;
- troubleshooting with logcat tags and the MediaProvider `dumpsys` command;
- MIT attribution to the base repo.

**9.5 Bug reports.**

- a diagnostics export with credentials removed;
- an issue template asking for the device, Android version, MediaProvider module version, and the cloud picker lines from MediaProvider's `dumpsys`.

**9.6 Upgrades.** Database migrations are tested. If a migration ever fails, the app rebuilds the library instead of silently losing state.

**9.7 CI.** GitHub Actions builds, runs unit tests, and produces signed release APKs.

**9.8 Translations and privacy.** Translatable strings, and a privacy statement saying there is no telemetry.

## Out of scope

- Multiple accounts (only one cloud provider is active at a time anyway)
- Search
- Live push updates
- Android below 14
- Nextcloud's single sign-on library: it can't send SEARCH requests, and it is GPL

## Testing

- **Unit tests (Claude):** building SEARCH requests and parsing responses using the saved fixtures, the snapshot comparison and generation logic, page tokens, the 2.9 safety net, and Memories parsing.
- **Simulated server tests (OkHttp MockWebServer):** login failures, 429 back-off, and paging.
- **Server matrix (Claude):** the throwaway containers from Phase 0.2 (`tools/testserver/up.sh`).
- **Devices:**
  - The stock Pixel 11 Pro and the GrapheneOS Pixel 10 Pro Fold are the main devices and run each phase's exit test.
  - Emulators for Android 14, 15 and 16 cover the different activation commands per version. Still to confirm: whether the emulator images include the cloud picker feature.
  - Samsung and other brands come from a public beta, using the structured bug-report template.

## Risks

- **Activation on a stock Pixel is only partly proven.** Phase 0.1 showed that adb can change the allow-list on both phones without root, and that a user-installed provider becomes selectable on GrapheneOS. A third-party provider appearing in the stock Pixel's picker is still untested (Phase 1.5). One Pixel 9 Pro on Android 17 using Shizuku reportedly showed only Google Photos, and that was never resolved.
- **Local network permission.** Confirmed in Phase 1.5: Android 17 blocks apps targeting 37 from LAN addresses unless they hold the runtime permission `ACCESS_LOCAL_NETWORK`. Sign-in asks for it when the server is on the local network (4.1).
- **Background network restriction.** Confirmed in Phase 1.5: no network outside the foreground or MediaProvider's calls (2.5a). Two consequences found in Phase 5: streaming a file to another app needs a foreground service, which Android allows to start only because the picker has the app bound while on screen; and WorkManager can run a job inside the app's own process without the job's network exemption, so long sync steps must stop when their job is stopped.
- **The streaming service may not start everywhere.** If a picker opens a file after it has closed, or another build refuses the start, a large video can't stream (5.2). Check on the stock Pixel's newer picker.
- **Updates deselect the provider.** Every app update switches the picker back to no cloud provider. The app selects itself again through Shizuku, or notifies the user (4.6).
- **Google could close the door.** A future Android or MediaProvider module update could remove these flags from what adb may write. Nothing in the app could work around that.
- **Video previews are silent.** Android 17's audio hardening mutes a cloud provider's preview, Google Photos' included; nothing in the app can change that (5.4).
- **HEIC and video thumbnails** depend on server settings (Phase 0.2). Without them the phone makes them (5.1): video frames cheaply through Range requests, HEIC only by downloading the photo, so the pre-cache skips HEIC.
- **Bad rows** can be silently dropped or abort a sync (Phase 2.9).
- **An unreachable server** could get the provider deselected (Phases 2.6 and 8).
- **Research claims not yet verified:**
  - whether column order matters to the picker (Phase 1);
  - whether the picker's full-size preview path rotates twice (Phase 5.1; the server side was confirmed in 0.2);
  - how albums with photos outside the library behave (Phase 7.1).

## References

- [9dc/immich-media-picker](https://github.com/9dc/immich-media-picker): MIT, the base repo
- Dreaming-Codes/immich-cloud-media and 9dc/immich-cloud-media: GPL, design reference only
- [trajano/cloud-media-provider-proxy](https://github.com/trajano/cloud-media-provider-proxy): test tool for Phase 0.1 only
- [Nextcloud trademark guidelines](https://nextcloud.com/trademarks/)
