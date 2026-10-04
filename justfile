set positional-arguments

# List the recipes
default:
    @just --list

# Release a version: check its changelog, bump it, then commit, push and tag. The tag starts the Release workflow.
release version="":
    #!/usr/bin/env bash
    set -euo pipefail
    die() { echo "error: $*" >&2; exit 1; }

    [ "$(git branch --show-current)" = main ] || die "Release from main."
    [ -z "$(git status --porcelain --untracked-files=no)" ] || die "Commit or stash your changes first."
    git fetch --quiet --tags origin main
    git merge-base --is-ancestor origin/main HEAD || die "main is behind origin/main: pull first."

    version="${1:-}"
    if [ -z "$version" ]; then
        read -rp "The current version is $(tools/release.py version). Version to release (MAJOR.MINOR.PATCH): " version
    fi
    tag="v$version"
    if git rev-parse --quiet --verify "refs/tags/$tag" > /dev/null; then
        die "$tag is already tagged."
    fi

    tools/release.py bump "$version"
    echo
    git --no-pager diff
    echo
    read -rp "Commit, push and tag $tag? [y/N] " answer
    if [ "$answer" != y ] && [ "$answer" != Y ]; then
        git restore -- :/
        die "Cancelled, and the changes undone."
    fi

    if ! git diff --quiet; then
        git commit --all --message "Release $version"
    fi
    git push origin main
    git tag --annotate "$tag" --message "Release $version"
    git push origin "$tag"
    echo "Pushed $tag. The Release workflow is building it: gh run watch"
