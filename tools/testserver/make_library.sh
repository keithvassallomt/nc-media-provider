#!/usr/bin/env bash
# Generate the synthetic test library. Runs inside the Containerfile.gen image:
#   podman run --rm -v "$PWD/tools/testserver:/work" nc-media-provider-libgen bash /work/make_library.sh /work/library
# Every capture date differs from the file's upload mtime (mtimes.tsv), so "real date" and
# "fallback to mtime" can be told apart. Git doesn't keep mtimes, hence the manifest.
set -euo pipefail
OUT=${1:?output dir}
rm -rf "$OUT"
mkdir -p "$OUT"
cd "$OUT"
IM=$(command -v magick || command -v convert)
MTIMES=mtimes.tsv
: > "$MTIMES"

mtime() { printf '%s\t%s\n' "$1" "$(date -u -d "$2" +%s)" >> "$MTIMES"; }
img() { "$IM" -size "$2" "$3" "$1"; }
exif_date() { exiftool -q -overwrite_original -DateTimeOriginal="$2" -CreateDate="$2" -Make=Synthetic -Model=Test "$1"; }
video() { # file, seconds, creation time, size
  ffmpeg -loglevel error -f lavfi -i "testsrc=size=$4:rate=30" -t "$2" -c:v libx264 -pix_fmt yuv420p \
    -metadata creation_time="$3" -movflags +faststart "$1"
}

# alice: the test user whose library the app reads
mkdir -p "alice/Photos/2021/Summer" "alice/Photos/2022" "alice/Photos/2023/Private" "alice/Documents"

f="alice/Photos/2021/Summer/beach-exif.jpg"   # real EXIF date and GPS
img "$f" 1200x800 gradient:navy-orange
exif_date "$f" "2021:07:14 15:30:00"
exiftool -q -overwrite_original -GPSLatitude=35.9 -GPSLatitudeRef=N -GPSLongitude=14.5 -GPSLongitudeRef=E "$f"
mtime "$f" "2024-02-01 10:00:00"

f="alice/Photos/2021/Summer/no-exif.jpg"      # no EXIF: date must fall back to mtime
img "$f" 800x600 gradient:green-yellow
mtime "$f" "2021-07-20 18:00:00"

f="alice/Photos/2021/Summer/rotated-exif6.jpg"  # stored landscape, EXIF says rotate 90 (#33)
img "$f" 1200x800 gradient:red-blue
exif_date "$f" "2021:07:15 08:00:00"
exiftool -q -overwrite_original -n -Orientation=6 "$f"
mtime "$f" "2024-02-01 10:05:00"

img /tmp/heic-src.png 1024x768 gradient:purple-white
f="alice/Photos/2022/IMG_1001.HEIC"           # live photo still, with EXIF date
heif-enc -q 60 -o "$f" /tmp/heic-src.png >/dev/null
exif_date "$f" "2022:03:05 09:00:00"
mtime "$f" "2024-02-02 10:00:00"

f="alice/Photos/2022/IMG_1001.MOV"            # live photo motion half: same base name
video "$f" 2 "2022-03-05T09:00:00Z" 640x480
exiftool -q -overwrite_original -Keys:ContentIdentifier="$(uuidgen)" "$f"
mtime "$f" "2024-02-02 10:00:01"

f="alice/Photos/2022/portrait.heic"           # plain HEIC, lower-case extension
img /tmp/heic-src2.png 768x1024 gradient:teal-black
heif-enc -q 60 -o "$f" /tmp/heic-src2.png >/dev/null
exif_date "$f" "2022:04:01 12:00:00"
mtime "$f" "2024-02-02 11:00:00"

f="alice/Photos/2023/clip.mp4"                # video with container creation time
video "$f" 3 "2023-05-06T12:00:00Z" 1280x720
mtime "$f" "2024-02-03 10:00:00"

f="alice/Photos/2023/Ünïcødé & spaces #1.jpg"  # href encoding
img "$f" 640x480 gradient:gold-maroon
exif_date "$f" "2023:06:01 10:00:00"
mtime "$f" "2024-02-03 11:00:00"

f="alice/Photos/2023/screenshot.png"          # PNG, no EXIF
img "$f" 1080x2400 xc:slategray
mtime "$f" "2023-06-02 09:30:00"

f="alice/Photos/2023/Private/.nomedia"        # folder opted out of galleries (#13)
: > "$f"
mtime "$f" "2023-06-03 09:00:00"
f="alice/Photos/2023/Private/hidden-by-nomedia.jpg"
img "$f" 640x480 gradient:black-gray
mtime "$f" "2023-06-03 09:00:00"

f="alice/Documents/outside-timeline.jpg"      # outside the library folders
img "$f" 640x480 gradient:white-red
mtime "$f" "2023-07-01 09:00:00"

f="alice/Documents/notes.txt"                 # not media
echo "not a photo" > "$f"
mtime "$f" "2023-07-01 09:00:00"

# bob: shares a folder with alice
mkdir -p "bob/Shared trip"
for i in 1 2; do
  f="bob/Shared trip/trip-$i.jpg"
  img "$f" 1000x750 gradient:orange-navy
  exif_date "$f" "2020:09:0$i 14:00:00"
  mtime "$f" "2024-02-04 10:0$i:00"
done

rm -f /tmp/heic-src.png /tmp/heic-src2.png
find . -type f | sort
