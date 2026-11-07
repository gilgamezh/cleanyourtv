#!/usr/bin/env bash
# Writes the Plex + Immich settings for the launcher and pushes them to the TV.
# Run it YOURSELF in a terminal (it handles secrets; nothing secret is printed):
#   TV_HOST=<tv ip> tools/services-setup.sh
# Your own values go in ~/.config/tv-launcher/setup.env (sourced if present, never committed), e.g.:
#   IMMICH_URL=https://immich.example.org
#   IMMICH_ALBUM_ID=<the id at the end of the album's URL>      # or IMMICH_ALBUM=<album name>
#   PLEX_URL=http://<plex server ip>:32400
#   PLEX_TOKEN_CMD='<command that prints your Plex token>'      # or export PLEX_TOKEN yourself
#   TV_HOST=<tv ip>
# Output: ~/.config/tv-launcher/services.json (mode 600), copied to the launcher's private folder on the TV.
# Re-run any time to change album, rotation, rows, etc. Values can be overridden with env vars:
#   IMMICH_URL, IMMICH_ALBUM (name) or IMMICH_ALBUM_ID (from the album's URL), PLEX_URL, PHOTOS, ROTATION_MINUTES, DIM,
#   FIT (1 = whole photo letterboxed, 0 = crop to fill the screen)
set -euo pipefail
umask 077
TV="${TV_HOST:?set TV_HOST to the TV IP}:${TV_PORT:-5555}"
CONF_DIR="$HOME/.config/tv-launcher"
OUT="$CONF_DIR/services.json"
mkdir -p "$CONF_DIR"
# shellcheck disable=SC1091
[ -f "$CONF_DIR/setup.env" ] && . "$CONF_DIR/setup.env"

: "${IMMICH_URL:?set IMMICH_URL (e.g. in $CONF_DIR/setup.env)}"
: "${PLEX_URL:?set PLEX_URL (e.g. in $CONF_DIR/setup.env)}"
[ -n "${IMMICH_ALBUM_ID:-}${IMMICH_ALBUM:-}" ] || { echo "set IMMICH_ALBUM_ID or IMMICH_ALBUM" >&2; exit 1; }

# --- Plex token: PLEX_TOKEN, or the output of PLEX_TOKEN_CMD (e.g. read from the server's Preferences.xml) ---
if [ -z "${PLEX_TOKEN:-}" ] && [ -n "${PLEX_TOKEN_CMD:-}" ]; then PLEX_TOKEN=$(sh -c "$PLEX_TOKEN_CMD"); fi
[ -n "${PLEX_TOKEN:-}" ] || { echo "no Plex token: set PLEX_TOKEN or PLEX_TOKEN_CMD" >&2; exit 1; }
curl -sf -m 8 -o /dev/null -H "X-Plex-Token: $PLEX_TOKEN" "$PLEX_URL/library/sections" \
  && echo "Plex: token OK against $PLEX_URL" || { echo "Plex: token rejected by $PLEX_URL" >&2; exit 1; }

# --- Immich API key: typed by you, never echoed ---
KEY_FILE="$HOME/.config/tv-launcher/immich.key"
if [ -z "${IMMICH_API_KEY:-}" ] && [ -s "$KEY_FILE" ]; then IMMICH_API_KEY=$(tr -d '[:space:]' < "$KEY_FILE"); fi
if [ -z "${IMMICH_API_KEY:-}" ]; then
  if [ -t 0 ]; then
    read -rsp "Immich API key (Account Settings > API Keys; needs album.read, asset.read, asset.view): " IMMICH_API_KEY; echo
  else
    echo "No Immich API key: run this in a real terminal (it asks for the key), or save it to $KEY_FILE (mode 600)." >&2
    exit 1
  fi
fi
if [ -n "${IMMICH_ALBUM_ID:-}" ]; then
  # An album picked by ID (the last part of its Immich URL); its name is read back for the summary.
  IMMICH_ALBUM=$(curl -sf -m 10 -H "x-api-key: $IMMICH_API_KEY" -H 'Accept: application/json' "$IMMICH_URL/api/albums/$IMMICH_ALBUM_ID?withoutAssets=true" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("albumName",""))') \
    || { echo "Immich: album $IMMICH_ALBUM_ID not found, key rejected or $IMMICH_URL unreachable" >&2; exit 1; }
  ALBUM_ID="$IMMICH_ALBUM_ID"
else
ALBUM_ID=$(curl -sf -m 10 -H "x-api-key: $IMMICH_API_KEY" -H 'Accept: application/json' "$IMMICH_URL/api/albums" \
  | python3 -c 'import json,sys; name=sys.argv[1].lower(); print(next((a["id"] for a in json.load(sys.stdin) if a.get("albumName","").lower()==name), ""))' "$IMMICH_ALBUM") \
  || { echo "Immich: API key rejected or $IMMICH_URL unreachable" >&2; exit 1; }
fi
[ -n "$ALBUM_ID" ] || { echo "Immich: no album named '$IMMICH_ALBUM'" >&2; exit 1; }
echo "Immich: album '$IMMICH_ALBUM' = $ALBUM_ID"

PLEX_TOKEN="$PLEX_TOKEN" IMMICH_API_KEY="$IMMICH_API_KEY" python3 - "$OUT" <<EOF
import json, os, sys
cfg = {
  "immich": {
    "url": "$IMMICH_URL", "api_key": os.environ["IMMICH_API_KEY"],
    "album_id": "$ALBUM_ID", "album_name": "$IMMICH_ALBUM",
    "photos": int("${PHOTOS:-40}"), "refresh_hours": 6,
    "rotation_minutes": int("${ROTATION_MINUTES:-3}"), "dim": int("${DIM:-25}"),
    "landscape_only": True,
    "fit": "${FIT:-1}" == "1"
  },
  "plex": {
    "url": "$PLEX_URL", "token": os.environ["PLEX_TOKEN"],
    "rows": ["continue", "recent"], "items": 12, "refresh_minutes": 5
  }
}
with open(sys.argv[1], "w") as f: json.dump(cfg, f, indent=2)
EOF
chmod 600 "$OUT"
DEST=/sdcard/Android/data/com.gathin.tvhome/files/services.json
adb connect "$TV" >/dev/null
adb -s "$TV" push "$OUT" "$DEST" >/dev/null && echo "pushed to the TV ($DEST)"
adb -s "$TV" shell am force-stop com.gathin.tvhome
adb -s "$TV" shell input keyevent HOME
echo "done; Home restarted"
