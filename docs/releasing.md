# Releasing

GitHub Actions builds and publishes releases (PLAN 9.7). There are two workflows in `.github/workflows/`:

- `ci.yml` runs on every push and pull request to `main`: unit tests, lint and a debug APK. When a step fails, the test and lint reports are attached to the run as the `reports` artifact.
- `release.yml` runs when a tag starting with `v` is pushed. It runs the unit tests, builds the signed APK and AAB, checks them, and publishes a GitHub release with both files and a `SHA256SUMS` file.

## Repository secrets

The release workflow signs with the release key (alias `release`). It needs four repository secrets, under Settings, Secrets and variables, Actions:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The keystore file, base64 encoded on one line |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore's password |
| `ANDROID_KEY_ALIAS` | `release` |
| `ANDROID_KEY_PASSWORD` | The key's password |

Make the base64 value with `base64 -w0 release.jks` and paste the output as the secret. With the GitHub CLI the value never reaches the clipboard:

```sh
base64 -w0 release.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD    # prompts for the value
gh secret set ANDROID_KEY_ALIAS --body release
gh secret set ANDROID_KEY_PASSWORD
```

If a secret is missing, the workflow stops before building and names it. The keystore is decoded into the runner's temporary folder and deleted at the end of the job, pass or fail.

## The changelog

[CHANGELOG.md](../CHANGELOG.md) follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/): MAJOR.MINOR.PATCH. List each change under `## [Unreleased]` as it's made, under `### Added`, `Changed`, `Deprecated`, `Removed`, `Fixed` or `Security`. Each release's section becomes its GitHub release notes.

The store changelogs in `fastlane/metadata/android/en-US/changelogs/`, one file per `versionCode`, are separate and still written by hand.

## Cutting a release

Run `just release` on `main` (needs [just](https://just.systems)), or `just release 0.2.0` to skip the question. It:

1. Checks that the working tree is clean and `main` isn't behind `origin/main`.
2. Asks which version to release, showing the current one. The version must be newer than the current one, or the current one if it hasn't been tagged yet, as for the first release.
3. Checks CHANGELOG.md has at least one change for it: in its own `## [0.2.0]` section, or under `## [Unreleased]`, which becomes the version's section. The section is dated today.
4. Sets `versionName` to the version and adds one to `versionCode` in `app/build.gradle.kts`.
5. Shows the changes and asks before going on. Saying no undoes them.
6. Commits them as "Release 0.2.0", pushes `main`, then tags the commit `v0.2.0` and pushes the tag.

`tools/release.py` does steps 2 to 4 and changes nothing until every check passes. The tag starts the Release workflow, which publishes `nc-media-provider-v0.2.0.apk`, `nc-media-provider-v0.2.0.aab` and `SHA256SUMS` under the tag, with the version's changelog section as the notes. Obtainium picks up the APK from there.

The workflow stops without publishing if CHANGELOG.md has no section for the tag, if the tag doesn't match `versionName` (tag `v0.2.0` needs `versionName = "0.2.0"`), or if the APK isn't signed with the release key. To try again after a fix, delete the tag on GitHub and locally, then run `just release` again with the same version:

```sh
git push --delete origin v0.2.0
git tag -d v0.2.0
```

## One key for every channel

The same key signs the GitHub releases and the Play builds. When setting up Play App Signing, choose "Provide a copy of your app signing key" and upload this key (see [play-notes.md](play-notes.md)). The AAB on each GitHub release is signed with it too, ready to upload to Play. GitHub and Play installs then carry the same signature, so people can move between them without uninstalling.

`RELEASE_CERT_SHA256` in `release.yml` holds the key's certificate fingerprint, the one in [play-notes.md](play-notes.md). The workflow refuses an APK signed with any other key, so a wrong keystore secret can't publish a release that existing installs would reject.

Keep the keystore and its passwords backed up outside the repository: without them, GitHub and Obtainium installs can never be updated again. `*.jks`, `*.keystore` and `local.properties` are git-ignored.

## Building a release locally

Set `ANDROID_KEYSTORE_PATH` to the keystore file, and the other three variables as above, then run `./gradlew :app:assembleRelease`. The build stops if only some of the four are set. With none set, it builds an unsigned release.
