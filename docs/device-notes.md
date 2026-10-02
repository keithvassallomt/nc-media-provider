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
