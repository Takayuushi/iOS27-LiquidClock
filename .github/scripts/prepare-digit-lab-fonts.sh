#!/usr/bin/env bash
set -euo pipefail
WORK_DIR="${RUNNER_TEMP}/digit-lab-sf-pro"
mkdir -p "$WORK_DIR"
if ! command -v 7z >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y p7zip-full
fi
curl --fail --show-error --silent --location --retry 3 --retry-all-errors \
  "https://devimages-cdn.apple.com/design/resources/download/SF-Pro.dmg" \
  --output "$WORK_DIR/SF-Pro.dmg"
7z x -y "$WORK_DIR/SF-Pro.dmg" -o"$WORK_DIR" >/dev/null
while IFS= read -r -d '' PKG; do
  7z x -y "$PKG" -o"$WORK_DIR" >/dev/null
done < <(find "$WORK_DIR" -type f -name '*.pkg' -print0)
while IFS= read -r -d '' PAYLOAD; do
  7z x -y "$PAYLOAD" -o"$WORK_DIR" >/dev/null
done < <(find "$WORK_DIR" -type f -name 'Payload' -print0)
while IFS= read -r -d '' PAYLOAD; do
  7z x -y "$PAYLOAD" -o"$WORK_DIR" >/dev/null
done < <(find "$WORK_DIR" -type f -name 'Payload~' -print0)

find_font() {
  local name="$1"
  local found
  found="$(find "$WORK_DIR" -type f -name "$name" -print -quit)"
  if [[ -z "$found" ]]; then echo "Font not found: $name" >&2; exit 1; fi
  printf '%s' "$found"
}
ASSET_DIR="clock-renderer/src/main/assets/fonts"
mkdir -p "$ASSET_DIR"
cp "$(find_font 'SF-Pro.ttf')" "$ASSET_DIR/SF-Pro.ttf"
cp "$(find_font 'SF-Pro-Display-Thin.otf')" "$ASSET_DIR/sf_pro_display_thin.otf"
python3 -m pip install --disable-pip-version-check --quiet fonttools
python3 - "$ASSET_DIR/SF-Pro.ttf" <<'PY'
import os, sys
from fontTools.ttLib import TTFont
from fontTools.subset import Options, Subsetter
path = sys.argv[1]
font = TTFont(path)
options = Options()
options.name_IDs = ["*"]
options.name_languages = ["*"]
options.layout_features = ["*"]
options.glyph_names = True
options.notdef_glyph = True
options.notdef_outline = True
options.recommended_glyphs = True
subsetter = Subsetter(options=options)
subsetter.populate(text="0123456789:")
subsetter.subset(font)
axes = {axis.axisTag for axis in font["fvar"].axes}
if not {"wght", "wdth"}.issubset(axes):
    raise SystemExit("SF Pro subset lost required variable axes.")
temporary = path + ".clock-subset"
font.save(temporary)
font.close()
check = TTFont(temporary)
if not {"wght", "wdth"}.issubset({axis.axisTag for axis in check["fvar"].axes}):
    raise SystemExit("Validated font subset is missing a variable axis.")
check.close()
size = os.path.getsize(temporary)
if size > 1_000_000:
    raise SystemExit(f"Unexpectedly large digit-only SF Pro subset: {size} bytes")
os.replace(temporary, path)
print(f"Prepared digit-only SF Pro variable font: {size:,} bytes")
PY
test -s "$ASSET_DIR/SF-Pro.ttf"
test -s "$ASSET_DIR/sf_pro_display_thin.otf"
