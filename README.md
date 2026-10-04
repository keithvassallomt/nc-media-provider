# NC Media Provider

Your Nextcloud photos and videos in Android's own photo picker.

> **Status:** in testing, not released yet. It will be published on Google Play and as GitHub releases. See [PLAN.md](PLAN.md) for the roadmap and the [issues](https://github.com/keithvassallomt/nc-media-provider/issues) for progress.

## What it does

Android's photo picker, the screen many apps open when you attach a photo, can show one cloud library next to the photos on your phone. Normally that's Google Photos. NC Media Provider implements Android's `CloudMediaProvider` API so your Nextcloud library can take that place:

- your photos and videos show in the picker in any app that uses it, newest first, mixed with the phone's own;
- albums from Nextcloud Photos or Memories show as albums, including ones shared with you;
- people that Memories found (with Recognize) show as a People section on phones with the newer picker, and named people show as albums on the older one;
- photos already on the phone, from auto-upload for example, show once, and the phone's copy is handed over instead of downloading it;
- thumbnails are cached, with an optional pre-cache that downloads them ahead of time on Wi-Fi; the full file is fetched only when you pick it, and large videos are streamed;
- nothing is mirrored to the phone, and nothing on your server changes.

Apps with their own photo grid, such as Messenger, never open the system picker, so no cloud library can appear there. For those there's **Send from Nextcloud** (a shortcut and a Quick Settings tile that pick through the system picker and share into the app) and an optional **photo keyboard**: switch to it while writing a message, browse by month, album or person, and tap a photo to insert it.

It works with plain Nextcloud. If [Memories](https://github.com/pulsejet/memories) is installed, the app uses it for real capture dates, live photos and people; [Nextcloud Photos](https://github.com/nextcloud/photos) provides albums.

## Requirements

- Android 14 or later.
- A Nextcloud server. Tested with Nextcloud 33 and 35, and Memories 8 and 9.
- A one-time activation with adb from a computer, or with [Shizuku](https://shizuku.rikka.app/) on the phone. **Root is never needed.**

## Setting it up

Install the app and open it: a short checklist walks you through activation, signing in and choosing your photo folders. Activation is the only fiddly step; [the setup guide](docs/setup-guide.md) explains it for people who have never used adb or Shizuku.

### Why activation is needed

Android only lets apps on a system allow-list act as a cloud photo source, and ordinary apps can't change that list. Activation adds this app to it with the same developer tools a computer would use, then selects it in the picker. The setting survives restarts. Every app update deselects the provider; if Shizuku is running the app selects itself again, otherwise a notification takes you to the picker's settings.

### Activation by hand

The app shows these commands, ready to copy, under **Details and troubleshooting**. To keep Google Photos as a choice:

```sh
adb shell device_config override mediaprovider allowed_cloud_providers com.google.android.apps.photos,com.keithvassallo.ncmediaprovider
adb shell device_config override mediaprovider cloud_media_feature_enabled true
adb shell device_config override storage_native_boot allowed_cloud_providers com.google.android.apps.photos,com.keithvassallo.ncmediaprovider
adb shell device_config override storage_native_boot cloud_media_feature_enabled true
adb shell content call --uri content://media --method set_cloud_provider --extra cloud_provider:s:com.keithvassallo.ncmediaprovider.cloudmedia
```

To make this app the only choice, leave `com.google.android.apps.photos,` out of both lists. The last command should print `Result: Bundle[{set_cloud_provider_result=true}]`.

| Android | Commands | Tested on |
|---|---|---|
| 17 | as above | Pixel 11 Pro (stock), Pixel 10 Pro Fold (GrapheneOS) |
| 14 to 16 | the same, as far as known | not yet: reports welcome |

The allow-list replaces whatever was there, so it has to name every provider you want to keep. Debug builds use the package `com.keithvassallo.ncmediaprovider.debug`: put that in the commands instead.

### Switching between this app and Google Photos

Only one cloud source shows at a time. Switch in the picker's own settings: in the app, **Details and troubleshooting > Photo picker settings**, or from a computer:

```sh
adb shell am start -a android.provider.action.PICK_IMAGES_SETTINGS
```

### Undoing it

Sign out in the app first if you want its app password removed from your server (signing out deletes it there), then uninstall the app. To put Android's settings back exactly as they were:

```sh
adb shell device_config clear_override mediaprovider allowed_cloud_providers
adb shell device_config clear_override mediaprovider cloud_media_feature_enabled
adb shell device_config clear_override storage_native_boot allowed_cloud_providers
adb shell device_config clear_override storage_native_boot cloud_media_feature_enabled
```

Clearing the overrides hands control back to the phone's defaults; nothing else is changed.

## GrapheneOS

- Cloud media is off by default and the allow-list is empty, so Google Photos isn't a cloud source until you add it.
- The picker's cloud sources only switch on when the phone starts. After the four `device_config` commands, restart the phone, then run the `set_cloud_provider` command (or tap the button in the app again). Until the restart the picker's settings screen won't open.
- GrapheneOS uses the older picker built into MediaProvider. It has no People section, so named people show as albums instead.
- Sandboxed Google Photos can stay on the allow-list alongside this app.

## Troubleshooting

Most problems show under **Details and troubleshooting** in the app: whether Android lists and selects the app, the last sync and any error, the caches and Memories.

- **The app isn't in the picker's settings.** On GrapheneOS, restart the phone after activation. Elsewhere, check that the app says it's allowed; if not, run the activation again.
- **It was there, now it isn't.** An app update deselects it. Start Shizuku, or tap the notification and select the app again.
- **Photos show twice.** Allow **Use local photos** on the app's home screen, so it can recognise photos that are also on the phone.
- **A server on your home network won't connect.** Android 17 asks for local network access; allow it when asked, or under **Details and troubleshooting**.
- **Video previews in the picker are silent.** That's Android: it mutes a cloud provider's preview playback, Google Photos' included. The picked video has its sound.

### Logs and state for a bug report

The app's log tags:

```sh
adb logcat -s NcCloudMediaProvider LibraryRepository LibraryDatabase LibrarySyncWorker NextcloudClient VideoPreview PhotoKeyboard ProviderReselect KeepAlive
```

The picker's side, MediaProvider and the newer picker app, logs under `PickerSyncController`, `PickerDataLayerV2` and `PickerSearchProviderClient`.

MediaProvider's own view of the cloud picker. Stock Pixels and other Google-signed builds:

```sh
adb shell dumpsys activity provider com.google.android.providers.media.module/com.android.providers.media.MediaProvider
```

AOSP builds such as GrapheneOS:

```sh
adb shell dumpsys activity provider com.android.providers.media.module/com.android.providers.media.MediaProvider
```

Look for `allowedCloudProviderPackages`, `isCloudMediaInPhotoPickerEnabled` and `mCloudProvider`. `adb shell device_config list_local_overrides` shows what activation changed. Please include these with the phone model, Android version and what the app's details screen says when you [open an issue](https://github.com/keithvassallomt/nc-media-provider/issues).

## Privacy

The app talks only to your Nextcloud server. There are no analytics, ads or tracking. It signs in with Nextcloud's login flow and keeps the resulting app password on the phone; signing out revokes it on the server.

## Building

Android Studio, or from the command line with JDK 17 or later:

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

Signed releases are built and published by GitHub Actions from a version tag: see [docs/releasing.md](docs/releasing.md).

[docs/](docs/) has the notes behind the design: what the two test phones do ([device-notes.md](docs/device-notes.md)), what Nextcloud servers return ([server-notes.md](docs/server-notes.md)) and how the base project maps onto this one ([base-repo-map.md](docs/base-repo-map.md)).

## Credits

The Android provider layer started from [9dc/immich-media-picker](https://github.com/9dc/immich-media-picker) v0.4.2 (MIT), which does the same job for Immich. Its copyright notice is kept in [LICENSE](LICENSE).

## Disclaimer

NC Media Provider is not affiliated with or endorsed by Nextcloud GmbH. Nextcloud is a trademark of Nextcloud GmbH.

## License

[MIT](LICENSE)
