# Server notes

Findings from probing Nextcloud servers with [tools/probe_server.py](../tools/probe_server.py) (Phase 0.2, #3). Raw responses from real servers stay in the git-ignored `local/` folder, because they contain private file names, paths and photo metadata.

## Keith's server (real-world target)

Probed 2026-10-02 with an app password.

| Item | Result |
|---|---|
| Version | Nextcloud 35.0.0 |
| Memcache | APCu (local), Redis (distributed and locking) |
| DAV capabilities | `search_supports_creation_time`, `search_supports_upload_time` and `search_supports_last_activity` all true; chunking and bulk upload 1.0 |
| Login vs user ID | identical for this account (the app must still look up the ID, see 4.1) |
| Memories | 9.0.1, timeline path set. Memories adds no entry to the OCS capabilities, so detect it with `/apps/memories/api/describe`. |
| Photos albums | 1 own album, 0 shared, from `/remote.php/dav/photos/<user>/albums` and `/sharedalbums` |
| Memories albums | The old `/apps/memories/api/albums?t=3` returns 404 in Memories 9. `/apps/memories/api/clusters/albums` returns the same single album as Photos, which fits Memories using Photos' albums (7.2). |
| Previews by fileId | HTTP 200, `image/jpeg`, about 0.1 s each at 256 px for JPEG, HEIC, MP4 and MOV. The HEIC and Movie preview providers are enabled here. |
| SEARCH speed | A 500-item page took 0.13 s and returned 595 KiB |
| Hidden files | `nc:hidden` was false on every sampled item |

### Metadata coverage

The 500 most recently modified files of each type (all 142 MP4s). "Real date" means `nc:metadata-photos-original_date_time` differs from the modification time; "fallback" means it equals it to within 2 seconds.

| Type | Items | Has date taken | Real date | Fallback date | Has size | Has EXIF |
|---|---|---|---|---|---|---|
| JPEG | 500 | 110 | 110 | 0 | 110 | 110 |
| HEIC | 500 | 500 | 0 | 500 | 0 | 0 |
| MP4 | 142 | 129 | 83 | 46 | 0 | 0 |

What this means for the app:

- **Expect partial metadata on real servers.** 390 of the 500 newest JPEGs had no metadata at all, so the server's metadata job is behind or never ran on them. Falling back to the modification time (2.8) is the normal case, not an edge case. An admin can fill the gaps with `occ files:scan --all --generate-metadata`.
- **Core never reads a HEIC's EXIF date.** Its "date taken" comes from a date in the file name if there is one, otherwise the modification time (see the container tests below). iPhone-style names (`IMG_1234.HEIC`) carry no date, which is why every HEIC here matches its modification time. Older HEICs here also have no dimensions. Without Memories (Phase 6), HEIC ordering depends on the uploading client preserving the modification time.
- **Dimensions are often missing.** Width and height come only from `metadata-photos-size`, which was present on 110 of 1,142 sampled items.
- **No live photos.** This library comes from a Pixel, which embeds motion in the JPEG instead of pairing it with a MOV. Live-photo handling (5.5) has to be checked against the test library on the container servers.

## Container servers (test matrix)

Built and probed 2026-10-02 with [tools/testserver/](../tools/testserver/). Each server runs rootless in Podman on 127.0.0.1, gets the synthetic library from `tools/testserver/library` uploaded over WebDAV with fixed modification times (as a phone client would), plus a folder shared by a second user, a Photos album and a favourite. Probe output is committed under [testdata/nextcloud/](../testdata/nextcloud/) as fixtures.

| | 33 default | 33 full | 35 default | 35 full |
|---|---|---|---|---|
| Version | 33.0.9 | 33.0.9 | 35.0.1 | 35.0.1 |
| SEARCH by creation, upload and last-activity time | yes | yes | yes | yes |
| Previews: JPEG / HEIC / MP4 / MOV | 200 / 404 / 404 / 404 | all 200 | 200 / 404 / 404 / 404 | all 200 |
| HEIC dimensions (`metadata-photos-size`) | no | no | yes | yes |
| Memories | absent (404) | 8.1.0 | absent (404) | 9.0.1 |
| Memories albums (`/api/clusters/albums`) | n/a | 1, same as Photos | n/a | 1, same as Photos |
| Photos albums | 1 own | 1 own | 1 own | 1 own |

"Full" means the HEIC and Movie preview providers enabled, ffmpeg installed and Memories installed and indexed. "Default" is the stock image.

Findings that hold on every server:

- **Dates.** JPEG EXIF dates are read. HEIC and video dates come from the file name when it contains one, otherwise the modification time. Tested with copies named `PXL_20190102_030405123.mp4`, `VID_20190102_030405.mp4` and `IMG_20190102_030405.heic` uploaded with a 2024 modification time: all three got 2019-01-02 03:04:05, interpreted in the server's timezone. A video's own creation-time metadata is ignored, even with ffmpeg installed.
- **Orientation (PLAN 5.1).** For a JPEG stored as 1200×800 with EXIF orientation 6, `metadata-photos-size` reports 800×1200 (display orientation) and `/core/preview` returns a portrait image (683×1024 at 1024 px) with no EXIF. Server previews are already rotated, so the provider should report orientation 0 and use those dimensions. Still to check on a device: the picker's full-size preview path, which gets the original file with its EXIF tag.
- **Search scope.** SEARCH over the user's home includes files in folders shared by other users, files in folders containing `.nomedia`, and files outside the photo folders. The app has to filter by its selected folders and handle `.nomedia` itself (2.2).
- **Live photos.** The synthetic HEIC + MOV pair (same base name, `ContentIdentifier` on the MOV only) was not detected: no `metadata-files-live-photo`, and the MOV is not hidden. Apple links the pair through maker notes that can't be synthesised, so checking 5.5 needs a real iPhone live photo with a licence that allows committing it.
- **Default servers can't make HEIC or video previews,** confirming the fallbacks in "Supporting any Nextcloud server".
