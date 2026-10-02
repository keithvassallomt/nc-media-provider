#!/usr/bin/env bash
# Regenerate the committed synthetic library in tools/testserver/library. Needs Podman.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
podman build -q --network=host -t localhost/nc-media-provider-libgen -f "$HERE/Containerfile.gen" "$HERE" >/dev/null
podman run --rm --network=none -v "$HERE:/work" localhost/nc-media-provider-libgen bash /work/make_library.sh /work/library
