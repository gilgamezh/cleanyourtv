#!/usr/bin/env bash
# Take a TV screenshot straight into screenshots/<name>.jpg (versioned in git, never in /tmp).
# Usage: TV_HOST=<ip> tools/shot.sh v2-immich-wallpaper [KEY ...]
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="${1:?usage: tools/shot.sh <name> [KEY ...]}"; shift
TMP="$(mktemp --suffix=.png)"; trap 'rm -f "$TMP"' EXIT
tools/venv/bin/python -I tools/tv.py --screenshot "$TMP" "$@" --wait 3 >/dev/null
python3 -c "import sys; from PIL import Image; Image.open(sys.argv[1]).convert('RGB').save(sys.argv[2], quality=85)" "$TMP" "screenshots/$NAME.jpg"
echo "screenshots/$NAME.jpg"
