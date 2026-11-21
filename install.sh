#!/usr/bin/env bash
# Build TV Panel, install it on the TV and, optionally, push its Home Assistant address and token.
#   TV_HOST=<tv ip> ./install.sh [config.json]
# A first install opens TV Panel's status page (it shows the pairing code); an update doesn't, unless OPEN=1.
# The ADB tools (tools/tv.py and its venv) come from a TV Panel Launcher checkout next to this one, else a CleanYourTV
# one; set TV_TOOLS to another tools folder.
#   # {"url": "http://192.168.1.10:8123", "token": "..."}
set -euo pipefail
CONFIG="${1:-}"
if [ -n "$CONFIG" ]; then
  [ -f "$CONFIG" ] || { echo "no such file: $CONFIG" >&2; exit 1; }
  CONFIG="$(cd "$(dirname "$CONFIG")" && pwd)/$(basename "$CONFIG")"
  if command -v cygpath >/dev/null 2>&1; then CONFIG="$(cygpath -m "$CONFIG")"; fi
fi
: "${TV_HOST:?set TV_HOST to the TV IP address}"
cd "$(dirname "$0")"
TOOLS=""
for d in ${TV_TOOLS:-} ../tv-panel-launcher/tools "${CLEANYOURTV:-../cleanyourtv}/tools"; do
  if [ -f "$d/tv.py" ]; then TOOLS="$(cd "$d" && pwd)"; break; fi
done
[ -n "$TOOLS" ] || { echo "No tools/tv.py: clone github.com/denov/tv-panel-launcher next to this repo, or set TV_TOOLS." >&2; exit 1; }
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
PKG=com.fullstacksystems.tvpanel
tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }

[ -f local.properties ] || { [ -n "${ANDROID_HOME:-}" ] && echo "sdk.dir=${ANDROID_HOME//\\//}" > local.properties; } || true
JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk17}"
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
./gradlew assembleRelease -q --console=plain

# An update leaves the screen alone: opening the status page would pause whatever is playing.
case "$(tv "pm path $PKG")" in *package:*) UPDATE=1 ;; *) UPDATE=0 ;; esac

"$PY" -I "$TOOLS/b64push.py" app/build/outputs/apk/release/app-release.apk /data/local/tmp/tv-panel.apk
OUT=$(tv 'pm install -r /data/local/tmp/tv-panel.apk; rm -f /data/local/tmp/tv-panel.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: pm install failed" >&2; exit 1 ;; esac

# The server and login go in as a file through the app's provider (only the ADB shell may write it), so the
# password never sits on a command line on the TV. The app reads it once and deletes it.
if [ -n "$CONFIG" ]; then
  STAGE=/data/local/tmp/tv-panel-config.json
  tv --push "$CONFIG" "$STAGE" >/dev/null
  # On some TVs the shell cannot start this provider until the app is running.
  # Start it before writing, then allow its process to come up.
  tv "am start -n $PKG/.StatusActivity >/dev/null; sleep 2; echo opened"
  # The provider can take a moment to appear after the app starts. tv.py does not pass the remote
  # shell's exit status through, so require a success marker from content write itself. Android's
  # content command can print a provider error and still exit 0, so success must also be silent.
  OUT=$(tv "ok=0; for attempt in 1 2 3; do if write_out=\$(content write --user 0 --uri content://$PKG.config/config.json < $STAGE 2>&1) && [ -z \"\$write_out\" ]; then ok=1; break; fi; [ -z \"\$write_out\" ] || printf '%s\n' \"\$write_out\"; [ \"\$attempt\" -eq 3 ] || sleep 1; done; if rm -f $STAGE && [ \"\$ok\" -eq 1 ]; then echo __TV_PANEL_CONFIG_OK__; fi")
  case "$OUT" in
    *__TV_PANEL_CONFIG_OK__*) echo "config pushed" ;;
    *) echo "$OUT" >&2; echo "ERROR: config write failed (or staged config could not be removed)" >&2; exit 1 ;;
  esac
fi
if [ "$UPDATE" = 1 ] && [ -z "$CONFIG" ] && [ "${OPEN:-0}" != 1 ]; then
  echo "Updated. Nothing was opened on the TV (OPEN=1 opens TV Panel's status page)."
else
  tv "am start -n $PKG/.StatusActivity >/dev/null; echo opened"
  echo "Installed. TV Panel is open on the TV: it shows the pairing code. Press Home to see the widget row."
fi
