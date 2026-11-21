#!/usr/bin/env bash
# Copy the integration to a Home Assistant over SSH (the Terminal & SSH add-on), for development without a release.
#
#   HA_SSH=root@homeassistant.local [HA_SSH_KEY=~/.ssh/key] ./deploy-ha.sh            # copy, then restart HA
#   HA_SSH=... ./deploy-ha.sh --page                                                 # only the page's JS: no restart
#
# Python changes need a restart; the page's JS only a hard refresh in the browser. HACS keeps showing the version
# it installed, and its next update overwrites what this copied.
set -euo pipefail
cd "$(dirname "$0")"
: "${HA_SSH:?Set HA_SSH, for example root@homeassistant.local}"
ssh_opts=(-o BatchMode=yes -o ConnectTimeout=8)
[[ -n "${HA_SSH_KEY:-}" ]] && ssh_opts+=(-i "$HA_SSH_KEY" -o IdentitiesOnly=yes)
dest=/config/custom_components

if [[ "${1:-}" == "--page" ]]; then
  tar -C custom_components -cf - tv_panel/frontend | ssh "${ssh_opts[@]}" "$HA_SSH" "tar -C $dest -xf -"
  echo "Page copied. Hard-refresh the TV Panel page (Ctrl+Shift+R)."
  exit 0
fi

tar -C custom_components --exclude=__pycache__ -cf - tv_panel \
  | ssh "${ssh_opts[@]}" "$HA_SSH" "rm -rf $dest/tv_panel.new && mkdir $dest/tv_panel.new && tar -C $dest/tv_panel.new -xf - \
      && rm -rf $dest/tv_panel && mv $dest/tv_panel.new/tv_panel $dest/tv_panel && rmdir $dest/tv_panel.new"
echo "Copied. Checking the configuration, then restarting Home Assistant…"
ssh "${ssh_opts[@]}" "$HA_SSH" "ha core check && ha core restart"
echo "Restarting. It's back in about a minute."
