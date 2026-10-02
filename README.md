# nc-media-provider

Browse and attach photos from your Nextcloud server straight from Android's system photo picker.

> **Status:** early planning. There is no app to install yet. See [PLAN.md](PLAN.md) for the roadmap and [issues](https://github.com/keithvassallomt/nc-media-provider/issues) for progress.

## What it does

Android's system photo picker (the one Messenger, email clients and most modern apps open when you attach a photo) can show one cloud photo library next to the photos on your phone. Normally that's Google Photos.

nc-media-provider implements Android's `CloudMediaProvider` API so that your Nextcloud photo library can fill that slot instead:

- your Nextcloud photos and videos appear in the picker, in any app that uses it;
- thumbnails are cached on the phone, and the full file is fetched only when you pick it;
- your library is never mirrored to the phone;
- photos that are already on the phone (for example from auto-upload) show up once, not twice.

It works with plain Nextcloud and doesn't depend on a particular gallery app. If [Memories](https://github.com/pulsejet/memories) is installed, the app uses it for better dates and video details.

## Planned requirements

- Android 14 or later
- A Nextcloud server (the currently maintained releases are fully supported; older ones work with fewer features)
- A one-time activation using ADB or [Shizuku](https://shizuku.rikka.app/)

**Root is never required.**

### Why activation is needed

Android only lets apps on a system allow-list act as a cloud photo source. Activation adds this app to that list using standard developer tools (ADB, or Shizuku over wireless debugging). Google Photos stays on the list, and you choose which one is active in the picker's settings. The app will walk you through activation and show how to undo it.

## Credits

The Android provider layer will be adapted from [9dc/immich-media-picker](https://github.com/9dc/immich-media-picker) (MIT), which does the same job for Immich.

## Disclaimer

This project is not affiliated with or endorsed by Nextcloud GmbH. Nextcloud is a trademark of Nextcloud GmbH.

## License

[MIT](LICENSE)
