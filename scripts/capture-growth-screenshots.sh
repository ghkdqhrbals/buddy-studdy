#!/usr/bin/env bash
set -euo pipefail

# Capture actual DEBUG fixture UI from an explicitly selected, booted iOS simulator.
# Build/install StudyMateiOS first. This does not upload or modify App Store Connect.
simulator_id="${1:?Usage: capture-growth-screenshots.sh <booted-simulator-UDID> [output-directory]}"
output_directory="${2:-app-store/growth-screenshots/6.5}"
bundle_id="io.github.ghkdqhrbals.StudyMate"

xcrun simctl list devices booted -j | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
if not any(d["udid"] == sys.argv[1] and d["state"] == "Booted"
           for group in devices.values() for d in group):
    raise SystemExit("Select an already booted iOS screenshot simulator.")
' "$simulator_id"

for locale in ko en ja; do
  destination_locale="$locale"
  if [[ "$locale" == en ]]; then destination_locale="en-US"; fi
  mkdir -p "$output_directory/$destination_locale"
  for entry in \
    'learning-result:01-learning-result' \
    'statistics:02-topic-progress' \
    'feed:03-interest-feed' \
    'interests:04-interest-topics'; do
    fixture="${entry%%:*}"
    filename="${entry#*:}"
    SIMCTL_CHILD_BUDDYSTUDY_SCREENSHOT_FIXTURE="$fixture" \
    SIMCTL_CHILD_BUDDYSTUDY_SCREENSHOT_LANGUAGE="$locale" \
      xcrun simctl launch --terminate-running-process "$simulator_id" "$bundle_id"
    # A bounded settling delay for initial SwiftUI layout and sheet presentation.
    sleep 5
    xcrun simctl io "$simulator_id" screenshot \
      "$output_directory/$destination_locale/$filename.png"
  done
done

echo "Captured candidate screenshots in $output_directory. Inspect every image before publishing."
