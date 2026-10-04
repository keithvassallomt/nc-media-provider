# How it works

Technical notes: what the app does under the hood, the activation commands, GrapheneOS, what to collect for a bug report, and how to build it. Setting the app up needs none of this; the [setup guide](setup-guide.md) covers that.

## The photo picker and cloud providers

Android's photo picker, the screen many apps open when you attach a photo, can show one cloud library next to the photos on the phone. Normally that's Google Photos. NC Media Provider implements Android's `CloudMediaProvider` API so a Nextcloud library can take that place:

- photos and videos show in the picker in any app that uses it, newest first, mixed with the phone's own;
- albums from Nextcloud Photos or Memories show as albums, including ones shared with you;
- people that Memories found (with Recognize) show as a People section on phones with the newer picker, and named people show as albums on the older one;
- photos already on the phone, from auto-upload for example, show once, and the phone's copy is handed over instead of downloading it;
- thumbnails are cached, with an optional pre-cache that downloads them ahead of time on Wi-Fi; the full file is fetched only when you pick it, and large videos are streamed;
- nothing is mirrored to the phone, and nothing on the server changes.

It works with plain Nextcloud over WebDAV. If [Memories](https://github.com/pulsejet/memories) is installed, the app uses it for real capture dates, live photos and people; [Nextcloud Photos](https://github.com/nextcloud/photos) provides albums. Tested with Nextcloud 33 and 35, and Memories 8 and 9.

There are two pickers. Stock Pixels and other Google-signed builds use the newer photo picker app (`com.google.android.photopicker`); AOSP builds such as GrapheneOS use the older picker built into MediaProvider. The app serves both. [device-notes.md](device-notes.md) has what each one did in testing.

Apps with their own photo grid, such as Messenger, never open the system picker, so no cloud library can appear there. For those there's **Send from Nextcloud** (a shortcut and a Quick Settings tile that pick through the system picker and share into the app) and the optional photo keyboard.

## Activation

Android only lets apps on a system allow-list act as a cloud photo source, and ordinary apps can't change that list. Activation adds this app to it as `device_config` local overrides, the same thing adb does from a computer, then selects it in the picker. The overrides survive restarts. Every app update deselects the provider; if Shizuku is running the app selects itself again, otherwise a notification takes you to the picker's settings.

With Shizuku, the app starts the allow-list from MediaProvider's current list, so other providers stay allowed, and checks the result through MediaProvider itself. It never uses `device_config put`: a `put` value can be overwritten by Google's server sync, and `clear_override` wouldn't undo it.

### The commands

The app shows these, ready to copy, in its first setup step and under **Details and troubleshooting**. To keep Google Photos as a choice:

```sh
adb shell device_config override mediaprovider allowed_cloud_providers com.google.android.apps.photos,com.keithvassallo.ncmediaprovider
adb shell device_config override mediaprovider cloud_media_feature_enabled true
adb shell device_config override storage_native_boot allowed_cloud_providers com.google.android.apps.photos,com.keithvassallo.ncmediaprovider
adb shell device_config override storage_native_boot cloud_media_feature_enabled true
adb shell content call --uri content://media --method set_cloud_provider --extra cloud_provider:s:com.keithvassallo.ncmediaprovider.cloudmedia
```

To make this app the only choice, leave `com.google.android.apps.photos,` out of both lists. The last command should print `Result: Bundle[{set_cloud_provider_result=true}]`.

The allow-list replaces whatever was there, so it has to name every provider you want to keep. Debug builds use the package `com.keithvassallo.ncmediaprovider.debug`: put that in the commands instead.

| Android | Commands | Tested on |
|---|---|---|
| 17 | as above | Pixel 11 Pro (stock), Pixel 10 Pro Fold (GrapheneOS) |
| 14 to 16 | the same, as far as known | not yet: reports welcome |

### Switching between this app and Google Photos

Only one cloud source shows at a time. Switch in the picker's own settings: in the app, **Details and troubleshooting > Photo picker settings**, or from a computer:

```sh
adb shell am start -a android.provider.action.PICK_IMAGES_SETTINGS
```

### Undoing it

**Details and troubleshooting > Turn off with Shizuku** selects Google Photos again where it's allowed, then clears the overrides. Without Shizuku, run:

```sh
adb shell device_config clear_override mediaprovider allowed_cloud_providers
adb shell device_config clear_override mediaprovider cloud_media_feature_enabled
adb shell device_config clear_override storage_native_boot allowed_cloud_providers
adb shell device_config clear_override storage_native_boot cloud_media_feature_enabled
```

Clearing the overrides hands control back to the phone's defaults; nothing else is changed. Sign out in the app first if you want its app password removed from your server (signing out deletes it there).

## GrapheneOS

- Cloud media is off by default and the allow-list is empty, so Google Photos isn't a cloud source until you add it.
- The picker's cloud sources only switch on when the phone starts. After the four `device_config` commands, restart the phone, then run the `set_cloud_provider` command (or tap the button in the app again). Until the restart the picker's settings screen won't open.
- GrapheneOS uses the older picker built into MediaProvider. It has no People section, so named people show as albums instead.
- Sandboxed Google Photos can stay on the allow-list alongside this app.

## Logs and state for a bug report

The easiest way is **Details and troubleshooting > Share for a bug report** in the app. It collects the app and phone versions, the picker's state, the library's state and the app's recent log lines, masks your server's address and user name, and hands the text to the app you choose. Check it, then attach it to [an issue](https://github.com/keithvassallomt/nc-media-provider/issues).

With a computer, the app's log tags are:

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

Look for `allowedCloudProviderPackages`, `isCloudMediaInPhotoPickerEnabled` and `mCloudProvider`. `adb shell device_config list_local_overrides` shows what activation changed.

## Building

Android Studio, or from the command line with JDK 17 or later:

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

Signed releases are built and published by GitHub Actions from a version tag: see [releasing.md](releasing.md).
