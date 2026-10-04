#!/usr/bin/env bash
# Gives a test server an album that bob shares with alice (#43): one photo alice can also
# reach through bob's shared folder, and one she can reach only through the album.
#   tools/testserver/shared_album.sh <base URL> <password>
# Called by up.sh; safe to run again on a running server. Photos takes the collaborators as JSON.
set -euo pipefail
BASE=${1:?base URL, e.g. http://127.0.0.1:8135}
PASS=${2:?password of the test users}
HERE=$(cd "$(dirname "$0")" && pwd)
LIB="$HERE/library"
ALBUM="Road%20trip"

dav() { curl -sS -o /dev/null -w '%{http_code}' -u "bob:$PASS" "$@"; }

dav -X MKCOL "$BASE/remote.php/dav/files/bob/Private" >/dev/null
dav -T "$LIB/alice/Photos/2021/Summer/no-exif.jpg" -H "X-OC-Mtime: 1700000000" \
  "$BASE/remote.php/dav/files/bob/Private/bob-only.jpg" >/dev/null
dav -X MKCOL "$BASE/remote.php/dav/photos/bob/albums/$ALBUM" >/dev/null
for f in "Shared%20trip/trip-1.jpg" "Private/bob-only.jpg"; do
  dav -X COPY -H "Destination: $BASE/remote.php/dav/photos/bob/albums/$ALBUM/${f##*/}" \
    "$BASE/remote.php/dav/files/bob/$f" >/dev/null
done
status=$(dav -X PROPPATCH -H "Content-Type: text/xml" "$BASE/remote.php/dav/photos/bob/albums/$ALBUM" --data \
  '<?xml version="1.0"?><d:propertyupdate xmlns:d="DAV:" xmlns:nc="http://nextcloud.org/ns"><d:set><d:prop><nc:collaborators>[{"id":"alice","label":"alice","type":0}]</nc:collaborators></d:prop></d:set></d:propertyupdate>')
[ "$status" = 207 ] || { echo "sharing the album failed: HTTP $status" >&2; exit 1; }
