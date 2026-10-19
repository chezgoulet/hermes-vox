#!/usr/bin/env bash
# Ask hermes-vox-site to regenerate the site for a released tag.
#
#   scripts/notify-site.sh v0.9.0
#
# The site rebuilds itself from the tag: version, date, APK size and checksum and
# the release-note bullets. It also has a daily self-heal, so a missed dispatch
# costs a day of staleness rather than drift.
#
# Needs a `gh` token with access to chezgoulet/hermes-vox-site.
set -euo pipefail
TAG="${1:?usage: notify-site.sh <tag> (e.g. v0.9.0)}"

gh api repos/chezgoulet/hermes-vox-site/dispatches \
  -f event_type=hermes-vox-release \
  -F "client_payload[tag]=$TAG" >/dev/null

echo "site rebuild requested for $TAG"
