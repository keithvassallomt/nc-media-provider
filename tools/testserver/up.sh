#!/usr/bin/env bash
# Start a throwaway Nextcloud test server, load the synthetic library like a client would, and probe it.
#   tools/testserver/up.sh <33|35> <default|full>
# Serves on 127.0.0.1 only: default 80<ver>, full 81<ver>. Remove with: podman rm -f nc<ver>-<variant>
# Probe output (committed fixtures) goes to testdata/nextcloud/<ver>-<variant>/.
set -euo pipefail
VER=${1:?version, e.g. 35}
VARIANT=${2:?default or full}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
LIB="$HERE/library"
NAME="nc$VER-$VARIANT"
PASS="throwaway-$VER-$VARIANT"   # local-only test server

case $VARIANT in
  default) PORT=80$VER; IMAGE="docker.io/library/nextcloud:$VER-apache" ;;
  full) PORT=81$VER; IMAGE="localhost/nc-media-provider-test:$VER-full"
        podman build -q --network=host -t "$IMAGE" --build-arg NC_VERSION="$VER" -f "$HERE/Containerfile.full" "$HERE" >/dev/null ;;
  *) echo "variant must be default or full" >&2; exit 1 ;;
esac
BASE="http://127.0.0.1:$PORT"
[ -f "$LIB/mtimes.tsv" ] || { echo "no library: run tools/testserver/build_library.sh first" >&2; exit 1; }

podman rm -f "$NAME" >/dev/null 2>&1 || true
podman run -d --name "$NAME" -p "127.0.0.1:$PORT:80" \
  -e SQLITE_DATABASE=nextcloud -e NEXTCLOUD_ADMIN_USER=admin -e NEXTCLOUD_ADMIN_PASSWORD="$PASS" \
  -e NEXTCLOUD_TRUSTED_DOMAINS="127.0.0.1 localhost" "$IMAGE" >/dev/null

occ() { podman exec -u www-data "$NAME" php occ "$@"; }
enc() { python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$1"; }
dav() { curl -fsS -o /dev/null -u "$1:$PASS" "${@:2}"; }

echo "[$NAME] waiting for install on $BASE"
for _ in $(seq 1 200); do
  curl -fsS "$BASE/status.php" 2>/dev/null | grep -q '"installed":true' && break
  sleep 3
done
curl -fsS "$BASE/status.php" | grep -q '"installed":true' || { echo "install timed out" >&2; exit 1; }

occ config:system:set skeletondirectory --value="" >/dev/null   # no sample files for new users
occ config:system:set templatedirectory --value="" >/dev/null
for u in alice bob; do
  podman exec -u www-data -e OC_PASS="$PASS" "$NAME" php occ user:add --password-from-env "$u" >/dev/null
done

if [ "$VARIANT" = full ]; then
  i=0
  for p in PNG JPEG GIF BMP XBitmap HEIC Movie; do
    occ config:system:set enabledPreviewProviders "$i" --value="OC\\Preview\\$p" >/dev/null
    i=$((i + 1))
  done
  occ app:install memories >/dev/null
  occ user:setting alice memories timelinePath "/Photos" >/dev/null
fi

echo "[$NAME] uploading library"
upload_tree() { # user
  local user=$1
  (cd "$LIB/$user" && find . -mindepth 1 -type d | sort) | while read -r d; do
    dav "$user" -X MKCOL "$BASE/remote.php/dav/files/$user/$(enc "${d#./}")"
  done
  while IFS=$'\t' read -r path epoch; do
    [[ $path == "$user/"* ]] || continue
    dav "$user" -T "$LIB/$path" -H "X-OC-Mtime: $epoch" "$BASE/remote.php/dav/files/$user/$(enc "${path#"$user"/}")"
  done < "$LIB/mtimes.tsv"
}
upload_tree bob
upload_tree alice

curl -fsS -o /dev/null -u "bob:$PASS" -H "OCS-APIRequest: true" -X POST \
  "$BASE/ocs/v2.php/apps/files_sharing/api/v1/shares" \
  --data-urlencode "path=/Shared trip" -d shareType=0 -d shareWith=alice

if dav alice -X MKCOL "$BASE/remote.php/dav/photos/alice/albums/Holiday" 2>/dev/null; then   # Photos app enabled
  for f in "Photos/2021/Summer/beach-exif.jpg" "Photos/2022/portrait.heic"; do
    dav alice -X COPY -H "Destination: $BASE/remote.php/dav/photos/alice/albums/Holiday/$(enc "$(basename "$f")")" \
      "$BASE/remote.php/dav/files/alice/$(enc "$f")"
  done
fi

dav alice -X PROPPATCH -H "Content-Type: text/xml" "$BASE/remote.php/dav/files/alice/$(enc Photos/2021/Summer/beach-exif.jpg)" \
  --data '<?xml version="1.0"?><d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:set><d:prop><oc:favorite>1</oc:favorite></d:prop></d:set></d:propertyupdate>'

echo "[$NAME] running background jobs"
podman exec -u www-data "$NAME" php cron.php
podman exec -u www-data "$NAME" php cron.php
[ "$VARIANT" = full ] && occ memories:index >/dev/null

OUT="$ROOT/testdata/nextcloud/$VER-$VARIANT"
rm -rf "$OUT"
mkdir -p "$OUT"
python3 "$ROOT/tools/probe_server.py" --props /dev/null --url "$BASE" --login alice --password "$PASS" --out "$OUT" \
  > "$OUT.summary.json"
echo "[$NAME] probe summary: ${OUT#"$ROOT"/}.summary.json"
