#!/usr/bin/env bash
# Fetch only the dependency pinned by this checkout. Never redistribute it.
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
version=$(sed -n 's/^millenaire_version=//p' "$project_dir/gradle.properties")
checksum=$(sed -n 's/^millenaire_sha256=//p' "$project_dir/gradle.properties")
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && $checksum =~ ^[0-9a-f]{64}$ ]] || exit 2
target="$project_dir/millenaire-${version}.jar"
if [[ ! -f $target ]]; then
    download=$(mktemp "${TMPDIR:-/tmp}/millenaire.XXXXXXXX.jar")
    trap 'rm -f "$download"' EXIT
    curl --fail --location --silent --show-error --max-time 180 \
        "https://www.millenaire.org/api/downloads/${version}~millenaire-${version}.jar" -o "$download"
    printf '%s  %s\n' "$checksum" "$download" | sha256sum --check -
    cp -n -- "$download" "$target"
fi
printf '%s  %s\n' "$checksum" "$target" | sha256sum --check -
