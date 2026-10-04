# Changelog

All notable changes to NC Media Provider are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

List each change under [Unreleased] as it's made, under one of Added, Changed, Deprecated, Removed, Fixed or Security. `just release` turns that section into the new version (see [docs/releasing.md](docs/releasing.md)).

## [Unreleased]

## [0.1.0] - 2026-10-04

### Added

- Your Nextcloud photos and videos in Android's photo picker, mixed in with the phone's own, newest first.
- Albums from Nextcloud Photos and Memories, including albums shared with you.
- The people Memories has recognised: a People category in the newer picker, albums of named people in the older one.
- A photo that's already on the phone shows once, and the phone's copy is used.
- Thumbnails download as you scroll, and the full photo or video only when you pick it. An optional pre-cache fetches thumbnails over Wi-Fi.
- Send from Nextcloud, which shares a photo into apps that don't use the picker, and an optional photo keyboard.
- A setup checklist that activates the app with Shizuku on the phone or adb from a computer. No root needed.
- Share for a bug report, which collects diagnostics with the server's address and the user name masked.

[unreleased]: https://github.com/keithvassallomt/nc-media-provider/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/keithvassallomt/nc-media-provider/releases/tag/v0.1.0
