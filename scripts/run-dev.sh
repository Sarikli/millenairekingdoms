#!/usr/bin/env bash
# Linux helper for NeoForge 1.21.1's non-ASCII classpath URI limitation.
set -euo pipefail
task=${1:-runClient}
if (($#)); then shift; fi
case "$task" in
    runClient|runServer|runServerJar|verifyConquest|verifyWars|verifyStage5|verifyCapitals|verifyAutonomy|verifyGui|verifyFounding) ;;
    *) printf 'Supported tasks: runClient, runServer, runServerJar, verifyConquest, verifyWars, verifyStage5, verifyCapitals, verifyAutonomy, verifyGui, verifyFounding\n' >&2; exit 2 ;;
esac

project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
if [[ "$project_dir" != *[!\ -~]* ]]; then
    exec "$project_dir/gradlew" --project-dir "$project_dir" \
        --gradle-user-home "$project_dir/.gradle-user-home" --console=plain "$task" "$@"
fi

# A symlink does not work: Gradle canonicalizes it back to the non-ASCII path.
dev_dir=$(mktemp -d /tmp/millenairekingdoms-dev.XXXXXXXX)
printf 'ASCII test workspace: %s\n' "$dev_dir"
printf 'This directory is retained after exit; save test data elsewhere before system temp cleanup.\n'
for name in settings.gradle build.gradle gradle.properties gradlew gradlew.bat gradle src; do
    cp -a -- "$project_dir/$name" "$dev_dir/"
done
millenaire_version=$(sed -n 's/^millenaire_version=//p' "$project_dir/gradle.properties")
cp -- "$project_dir/millenaire-${millenaire_version}.jar" "$dev_dir/"
mkdir -p "$dev_dir/.gradle-user-home"
for name in caches wrapper; do
    if [[ -d "$project_dir/.gradle-user-home/$name" ]]; then
        cp -a --reflink=auto -- "$project_dir/.gradle-user-home/$name" "$dev_dir/.gradle-user-home/"
    fi
done

set +e
"$dev_dir/gradlew" --project-dir "$dev_dir" \
    --gradle-user-home "$dev_dir/.gradle-user-home" --console=plain build "$task" "$@"
result=$?
set -e
for run in client server server-jar gametest; do
    if [[ -d "$dev_dir/run/$run/logs" ]]; then
        mkdir -p "$project_dir/run/$run-ascii"
        cp -a -- "$dev_dir/run/$run/logs" "$project_dir/run/$run-ascii/"
    fi
done
printf 'Test workspace retained at: %s\n' "$dev_dir"
exit "$result"
