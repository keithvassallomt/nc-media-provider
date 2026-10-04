#!/usr/bin/env python3
"""Probe a Nextcloud server for the features nc-media-provider depends on (issue #3).

Reads nextcloud.url, nextcloud.login and nextcloud.appPassword from local.properties by default.
Raw responses go to --out (default: local/server-probe/<timestamp>/, which is git-ignored because
real servers return private file names and metadata). The printed summary holds no names or paths.

Usage: tools/probe_server.py [--props local.properties] [--out DIR] [--url U --login L --password P]
"""
import argparse
import base64
import collections
import email.utils
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

NS = {"d": "DAV:", "oc": "http://owncloud.org/ns", "nc": "http://nextcloud.org/ns"}
PROPS = [
    "d:getcontenttype", "d:getcontentlength", "d:getetag", "d:getlastmodified", "oc:fileid", "oc:favorite",
    "nc:has-preview", "nc:hidden", "nc:upload_time", "nc:creation_time",
    "nc:metadata-photos-original_date_time", "nc:metadata-photos-size", "nc:metadata-photos-exif",
    "nc:metadata-photos-gps", "nc:metadata-files-live-photo", "nc:metadata-blurhash",
]
SAMPLES = {"jpeg": "image/jpeg", "heic": "image/heic", "mp4": "video/mp4", "mov": "video/quicktime"}


def read_props(path):
    props = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip().replace("\\:", ":")
    return props


class Probe:
    def __init__(self, url, login, password, out):
        self.base = url.rstrip("/")
        self.auth = "Basic " + base64.b64encode(f"{login}:{password}".encode()).decode()
        self.out = out
        self.ctx = ssl.create_default_context()
        os.makedirs(out, exist_ok=True)

    def request(self, name, path, method="GET", body=None, headers=None, auth=True):
        hdrs = {"OCS-APIRequest": "true", "Accept": "application/json", **(headers or {})}
        if auth:
            hdrs["Authorization"] = self.auth
        req = urllib.request.Request(self.base + path, data=body.encode() if body else None,
                                     method=method, headers=hdrs)
        started = time.monotonic()
        try:
            with urllib.request.urlopen(req, context=self.ctx, timeout=120) as r:
                status, rh, data = r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            status, rh, data = e.code, dict(e.headers), e.read()
        elapsed = round(time.monotonic() - started, 2)
        ctype = rh.get("Content-Type", "")
        ext = "json" if "json" in ctype else "xml" if "xml" in ctype else "bin"
        with open(os.path.join(self.out, f"{name}.{ext}"), "wb") as f:
            f.write(data)
        with open(os.path.join(self.out, f"{name}.meta.json"), "w") as f:
            json.dump({"method": method, "path": path, "status": status, "seconds": elapsed,
                       "headers": rh}, f, indent=1)
        return status, ctype, data, elapsed

    def json(self, name, path, auth=True):
        status, _, data, _ = self.request(name, path, auth=auth)
        try:
            return status, json.loads(data)
        except ValueError:
            return status, None

    def search(self, name, user, mime, limit=1):
        props = "".join(f"<{p}/>" for p in PROPS)
        body = f"""<?xml version="1.0" encoding="UTF-8"?>
<d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
 <d:basicsearch>
  <d:select><d:prop>{props}</d:prop></d:select>
  <d:from><d:scope><d:href>/files/{user}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
  <d:where><d:eq><d:prop><d:getcontenttype/></d:prop><d:literal>{mime}</d:literal></d:eq></d:where>
  <d:orderby><d:order><d:prop><d:getlastmodified/></d:prop><d:descending/></d:order></d:orderby>
  <d:limit><d:nresults>{limit}</d:nresults></d:limit>
 </d:basicsearch>
</d:searchrequest>"""
        status, _, data, elapsed = self.request(name, "/remote.php/dav/", "SEARCH", body,
                                                {"Content-Type": "text/xml", "Accept": "*/*"})
        items = []
        if status == 207:
            for resp in ET.fromstring(data).findall("d:response", NS):
                item = {}
                for ps in resp.findall("d:propstat", NS):
                    if "200" not in (ps.findtext("d:status", "", NS) or ""):
                        continue
                    for el in ps.find("d:prop", NS):
                        key = el.tag.split("}")[1]
                        item[key] = (el.text or "").strip() if len(el) == 0 else "<structured>"
                items.append(item)
        return status, items, elapsed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--props", default="local.properties")
    ap.add_argument("--out")
    ap.add_argument("--url")
    ap.add_argument("--login")
    ap.add_argument("--password")
    a = ap.parse_args()
    props = read_props(a.props) if os.path.exists(a.props) else {}
    url = a.url or props.get("nextcloud.url")
    login = a.login or props.get("nextcloud.login")
    password = a.password or props.get("nextcloud.appPassword")
    if not (url and login and password):
        sys.exit("missing url, login or password")
    out = a.out or os.path.join("local", "server-probe", time.strftime("%Y%m%d-%H%M%S"))
    p = Probe(url, login, password, out)
    s = {}

    status, st = p.json("status", "/status.php", auth=False)
    s["status"] = {k: st.get(k) for k in ("version", "versionstring", "maintenance", "installed")} if st else status

    _, caps = p.json("capabilities", "/ocs/v2.php/cloud/capabilities?format=json")
    c = (caps or {}).get("ocs", {}).get("data", {}).get("capabilities", {})
    s["capabilities"] = {"dav": c.get("dav"), "has_files": "files" in c, "memories": "memories" in c}

    _, me = p.json("user", "/ocs/v2.php/cloud/user?format=json")
    user = (me or {}).get("ocs", {}).get("data", {}).get("id")
    s["user_id_differs_from_login"] = user != login
    if not user:
        print(json.dumps(s, indent=1))
        sys.exit("could not resolve user id")

    status, info = p.json("serverinfo", "/ocs/v2.php/apps/serverinfo/api/v1/info?format=json")
    system = (info or {}).get("ocs", {}).get("data", {}).get("nextcloud", {}).get("system", {})
    s["serverinfo"] = {"http": status, "memcache": {k: system.get(k) for k in system if "memcache" in k}} \
        if system else {"http": status}

    s["samples"] = {}
    for label, mime in SAMPLES.items():
        status, items, elapsed = p.search(f"search-{label}", user, mime, limit=5 if label == "mov" else 1)
        entry = {"http": status, "seconds": elapsed, "found": len(items)}
        if items:
            it = items[0]
            entry["props_present"] = sorted(k for k, v in it.items() if v)
            entry["hidden"] = it.get("hidden")
            fid = it.get("fileid")
            if fid:
                st, ctype, data, el = p.request(
                    f"preview-{label}",
                    f"/index.php/core/preview?fileId={fid}&x=256&y=256&a=1&mode=cover&forceIcon=0",
                    headers={"Accept": "image/*"})
                entry["preview"] = {"http": st, "type": ctype, "bytes": len(data), "seconds": el}
            if label == "mov":
                entry["live_photo_halves"] = sum(1 for i in items if i.get("metadata-files-live-photo"))
                entry["live_photo_halves_hidden"] = sum(
                    1 for i in items if i.get("metadata-files-live-photo") and i.get("hidden") == "true")
        s["samples"][label] = entry

    status, desc = p.json("memories-describe", "/index.php/apps/memories/api/describe")
    s["memories"] = {"describe_http": status}
    if status == 200 and desc:
        s["memories"]["version"] = desc.get("version")
        _, cfg = p.json("memories-config", "/index.php/apps/memories/api/config")
        if cfg:
            s["memories"]["timeline_path_set"] = bool(cfg.get("timeline_path"))
            s["memories"]["config_keys"] = sorted(cfg)[:40]
        st, albums = p.json("memories-albums", "/index.php/apps/memories/api/clusters/albums")
        s["memories"]["albums"] = {"http": st, "count": len(albums) if isinstance(albums, list) else None}
        # The timeline the app's enrichment reads (#40): the day list, then every day's items.
        st, days = p.json("memories-days", "/index.php/apps/memories/api/days")
        if isinstance(days, list) and days:
            ids = ",".join(str(d.get("dayid")) for d in days)
            dst, detail = p.json("memories-day", f"/index.php/apps/memories/api/days/{ids}")
            keys = collections.Counter(k for it in detail or [] for k in it)
            s["memories"]["days"] = {"http": st, "days": len(days), "detail_http": dst,
                                     "items": len(detail or []), "item_keys": dict(sorted(keys.items()))}
        else:
            s["memories"]["days"] = {"http": st, "days": None}

    s["coverage"] = {}
    for label, mime in (("jpeg", "image/jpeg"), ("heic", "image/heic"), ("mp4", "video/mp4")):
        st, items, elapsed = p.search(f"coverage-{label}", user, mime, limit=500)
        c = collections.Counter()
        for it in items:
            for k in ("photos-original_date_time", "photos-size", "photos-exif", "files-live-photo"):
                if it.get("metadata-" + k):
                    c[k] += 1
            odt, lm = it.get("metadata-photos-original_date_time"), it.get("getlastmodified")
            if odt and lm:
                same = abs(int(odt) - email.utils.parsedate_to_datetime(lm).timestamp()) < 2
                c["date_equals_mtime" if same else "date_differs_from_mtime"] += 1
            if it.get("hidden") == "true":
                c["hidden"] += 1
        s["coverage"][label] = {"http": st, "items": len(items), "seconds": elapsed, **c}

    album_props = ('<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:nc="http://nextcloud.org/ns">'
                   "<d:prop><nc:nbItems/><nc:last-photo/><nc:dateRange/></d:prop></d:propfind>")
    s["photos_albums"] = {}
    for kind in ("albums", "sharedalbums"):
        st, _, data, _ = p.request(f"photos-{kind}", f"/remote.php/dav/photos/{user}/{kind}", "PROPFIND",
                                   album_props, {"Depth": "1", "Content-Type": "text/xml", "Accept": "*/*"})
        count = len(ET.fromstring(data).findall("d:response", NS)) - 1 if st == 207 else None
        s["photos_albums"][kind] = {"http": st, "count": count}
        # Each album's files, with the properties the app's listing asks for (#43).
        if st == 207:
            hrefs = [r.findtext("d:href", namespaces=NS) for r in ET.fromstring(data).findall("d:response", NS)][1:]
            file_props = ('<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" '
                          'xmlns:nc="http://nextcloud.org/ns"><d:prop>' + "".join(f"<{x}/>" for x in PROPS) +
                          "<d:resourcetype/></d:prop></d:propfind>")
            for i, href in enumerate(hrefs):
                ast, _, adata, _ = p.request(f"photos-{kind}-{i}", href, "PROPFIND", file_props,
                                             {"Depth": "1", "Content-Type": "text/xml", "Accept": "*/*"})
                files = len(ET.fromstring(adata).findall("d:response", NS)) - 1 if ast == 207 else None
                s["photos_albums"][kind].setdefault("files", []).append({"http": ast, "count": files})

    print(json.dumps(s, indent=1))
    print(f"raw responses: {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
