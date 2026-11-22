#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST="$ROOT/runtime/gnome-46"

clone_or_check() {
    local name=$1
    local branch=$2
    local url="https://git.launchpad.net/ubuntu/+source/$name"
    local target="$DEST/$name"

    if [[ ! -d "$target/.git" ]]; then
        git clone --depth 1 --single-branch --branch "$branch" "$url" "$target"
        git -C "$target" switch -c linuxhub/ubuntu-noble
        git -C "$target" tag -f upstream-baseline
    else
        if [[ -n "$(git -C "$target" status --porcelain)" ]]; then
            echo "refusing to update dirty downstream tree: $target" >&2
            return 1
        fi
        git -C "$target" fetch --depth 1 origin "$branch"
    fi

    printf '%-12s %s %s\n' \
        "$name" \
        "$(git -C "$target" rev-parse --short HEAD)" \
        "$(dpkg-parsechangelog -l "$target/debian/changelog" -S Version 2>/dev/null || sed -n '1s/.*(\(.*\)).*/\1/p' "$target/debian/changelog")"
}

mkdir -p "$DEST"
clone_or_check gnome-shell applied/ubuntu/noble-security
clone_or_check mutter applied/ubuntu/noble-updates
clone_or_check gjs applied/ubuntu/noble
