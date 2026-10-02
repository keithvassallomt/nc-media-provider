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
- **Core's HEIC "date taken" is always the modification time,** never the EXIF date, and HEIC files get no dimensions. Without Memories (Phase 6), HEIC ordering depends on the uploading client preserving the modification time.
- **Dimensions are often missing.** Width and height come only from `metadata-photos-size`, which was present on 110 of 1,142 sampled items.
- **No live photos.** This library comes from a Pixel, which embeds motion in the JPEG instead of pairing it with a MOV. Live-photo handling (5.5) has to be checked against the test library on the container servers.

## Container servers (test matrix)

Not yet built. Plan: Nextcloud 33 and 35, each with default settings and with Photos, Memories, and HEIC and video previews enabled, all loaded with the same synthetic library. Fixtures committed to the repo come only from these servers.
