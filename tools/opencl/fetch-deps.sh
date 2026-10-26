#!/usr/bin/env bash
# Fetch the pinned upstream sources needed by the optional OpenCL engine.
# Nothing is written into the repository: the archive lives in .deps/ (ignored).
set -euo pipefail
BASE=$(cd "$(dirname "$0")" && pwd)
DEPS="${OPENCL_DEPS_DIR:-$BASE/.deps}"
ARCHIVE="$DEPS/whisper-231638a.tar.gz"
URL="https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/231638a0717b7822784297e5980965ffcec05810"
SHA="f69100f71ce6c815624994362cd8a8eaaa26d678e5ce518ed35900d4b679e721"

usage() {
  cat <<'EOF'
Usage: tools/opencl/fetch-deps.sh

Downloads the pinned whisper.cpp source archive used to build the optional
OpenCL engine, verifies its SHA-256, and prints the archive path. The FUTO
speech model is NOT downloaded or bundled; users supply their own model file.

Environment:
  OPENCL_DEPS_DIR   Directory for the pinned archive (default: tools/opencl/.deps)
  WHISPER_ARCHIVE   Existing archive path; if set, nothing is downloaded
EOF
}

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
  usage
  exit 0
fi

if [ -n "${WHISPER_ARCHIVE:-}" ]; then
  printf '%s\n' "$WHISPER_ARCHIVE"
  exit 0
fi

command -v curl >/dev/null || { echo "fetch-deps.sh: curl is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "fetch-deps.sh: sha256sum is required" >&2; exit 1; }
mkdir -p "$DEPS"

if [ ! -f "$ARCHIVE" ]; then
  echo "Fetching pinned whisper.cpp source (${SHA:0:12}...)" >&2
  tmp="$ARCHIVE.partial"
  rm -f "$tmp"
  curl -fLsS --max-time 120 --max-filesize 50000000 "$URL" -o "$tmp"
  printf '%s  %s\n' "$SHA" "$tmp" | sha256sum -c - >&2
  mv "$tmp" "$ARCHIVE"
fi

printf '%s  %s\n' "$SHA" "$ARCHIVE" | sha256sum -c - >/dev/null
printf '%s\n' "$ARCHIVE"
