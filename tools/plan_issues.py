#!/usr/bin/env python3
"""Keep GitHub issues in step with PLAN.md: one issue per phase, each numbered sub-task as a sub-issue.

Usage:
  tools/plan_issues.py              dry run: show issues that would be created or updated
  tools/plan_issues.py --apply      create missing issues, link them, and update changed bodies
Issues are matched by title ("Phase N: ..." and "N.M ..."), so a renamed sub-task creates a new issue.
"""
import json
import re
import subprocess
import sys
import time

REPO = "keithvassallomt/nc-media-provider"
PLAN_URL = f"https://github.com/{REPO}/blob/main/PLAN.md"
LABELS = {
    "phase": ("1d76db", "Tracking issue for a plan phase"),
    "on-device": ("d93f0b", "Needs a test on the physical phones"),
    "stretch": ("c5def5", "Optional, only if time and platform allow"),
}


def parse(path):
    text = open(path, encoding="utf-8").read()
    phases = []
    for m in re.finditer(r"^### (Phase (\d+): [^\n]+)\n(.*?)(?=^##+ |\Z)", text, re.M | re.S):
        heading, num, body = m.group(1), m.group(2), m.group(3)
        exit_m = re.search(r"^\*\*Exit:\*\* (.+)$", body, re.M)
        if exit_m:
            body = body[: exit_m.start()]
        starts = list(re.finditer(rf"^\*\*{num}\.(\d+) (.+?)\*\*", body, re.M))
        subs = []
        for i, s in enumerate(starts):
            end = starts[i + 1].start() if i + 1 < len(starts) else len(body)
            subs.append({"title": f"{num}.{s.group(1)} {s.group(2).rstrip('.,:')}",
                         "text": body[s.start():end].strip()})
        anchor = re.sub(r"[^a-z0-9 -]", "", heading.lower()).replace(" ", "-")
        link = f"[PLAN.md: {heading}]({PLAN_URL}#{anchor})"
        parts = [body[: starts[0].start()].strip() if starts else body.strip()]
        if exit_m:
            parts += [f"**Exit test:** {exit_m.group(1).strip()}",
                      "Sub-tasks are linked below. Close this issue when the exit test passes."]
        else:
            parts.append("Sub-tasks are linked below. Close this issue when they are all done.")
        parts.append(f"Plan: {link}")
        phases.append({"title": heading, "body": "\n\n".join(p for p in parts if p), "link": link, "subs": subs})
    return phases


def gh(*args, payload=None):
    out = subprocess.run(["gh", *args], input=json.dumps(payload) if payload else None,
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args)} failed: {out.stderr.strip()}")
    return json.loads(out.stdout) if out.stdout.strip() else None


def ensure(existing, title, body, labels, apply):
    issue = existing.get(title)
    if issue is None:
        print(f"create  {title}")
        if not apply:
            return None
        issue = gh("api", "-X", "POST", f"repos/{REPO}/issues", "--input", "-",
                   payload={"title": title, "body": body, "labels": labels})
        time.sleep(1.5)
    elif (issue["body"] or "").strip() != body.strip():
        print(f"update  #{issue['number']} {title}")
        if apply:
            gh("api", "-X", "PATCH", f"repos/{REPO}/issues/{issue['number']}", "--input", "-",
               payload={"body": body})
    return issue


def main():
    apply = "--apply" in sys.argv
    phases = parse("PLAN.md")
    if apply:
        have = {lab["name"] for lab in gh("api", f"repos/{REPO}/labels", "--paginate")}
        for name, (color, desc) in LABELS.items():
            if name not in have:
                gh("label", "create", name, "--repo", REPO, "--color", color, "--description", desc)
    existing = {i["title"]: i for i in gh("api", f"repos/{REPO}/issues?state=all&per_page=100", "--paginate")
                if "pull_request" not in i}
    for p in phases:
        parent = ensure(existing, p["title"], p["body"], ["phase"], apply)
        linked = set()
        if parent and apply:
            linked = {s["id"] for s in gh("api", f"repos/{REPO}/issues/{parent['number']}/sub_issues")}
        for s in p["subs"]:
            labels = (["on-device"] if "on device" in s["title"] else []) + \
                     (["stretch"] if "Stretch" in s["title"] else [])
            ref = f"#{parent['number']}" if parent else p["title"]
            child = ensure(existing, s["title"], f"Part of {ref}. Plan: {p['link']}\n\n{s['text']}", labels, apply)
            if apply and parent and child and child["id"] not in linked:
                gh("api", "-X", "POST", f"repos/{REPO}/issues/{parent['number']}/sub_issues",
                   "-F", f"sub_issue_id={child['id']}")
                time.sleep(0.5)
    if not apply:
        print("dry run; pass --apply to make changes")


if __name__ == "__main__":
    main()
