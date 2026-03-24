#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/example"
command -v maestro >/dev/null 2>&1 || {
  echo "Install Maestro: https://docs.maestro.dev/getting-started/installing-maestro" >&2
  exit 1
}
exec maestro test "$ROOT/maestro/media-engine-suite.yaml"
