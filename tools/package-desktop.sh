#!/usr/bin/env bash
# The Windows build's archive, from the repository root: <name>-no-sounds.zip, the app alone (leoniydew-native.exe and
# leoniydew-import.exe), which uses the sound library installed in the app data folder (the app offers to download it,
# or install `leoniydew-<version>-sounds.zip` from Settings > Install sound library).
#
#   tools/package-desktop.sh NAME BIN_DIR
set -euo pipefail
dir=$1
bin=$2
rm -rf "$dir"
mkdir "$dir"
cp "$bin/leoniydew-native.exe" "$bin/leoniydew-import.exe" README.md LICENSE "$dir/"
rm -f "$dir-no-sounds.zip"
if command -v 7z >/dev/null; then
  7z a "$dir-no-sounds.zip" "$dir" >/dev/null
else
  zip -qr "$dir-no-sounds.zip" "$dir"
fi
echo "wrote $dir-no-sounds.zip"
