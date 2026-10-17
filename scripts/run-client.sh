#!/usr/bin/env bash
# Linux development client; handles non-ASCII workspace paths.
set -euo pipefail
exec "$(dirname -- "${BASH_SOURCE[0]}")/run-dev.sh" runClient "$@"
