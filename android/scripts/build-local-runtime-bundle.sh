#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_ROOT="$(dirname "$ANDROID_DIR")"

ASSETS_DIR="$ANDROID_DIR/app/src/main/assets/runtime"
WORK_DIR="$PROJECT_ROOT/.tmp/local-runtime-bundle"
STAGE_DIR="$WORK_DIR/stage"
ARCHIVE_PATH="$WORK_DIR/runtime-core.tar.gz"

SOURCE_PREFIX="${SOURCE_PREFIX:-/data/user/0/com.codex.mobile.beta/files/usr}"
SOURCE_HOME="${SOURCE_HOME:-/data/user/0/com.codex.mobile.beta/files/home}"
BUNDLE_VERSION="${BUNDLE_VERSION:-2026.3.2-offline-r1}"
PART_SIZE_MB="${PART_SIZE_MB:-90}"

if [ ! -d "$SOURCE_PREFIX" ]; then
  echo "Missing SOURCE_PREFIX: $SOURCE_PREFIX"
  exit 1
fi

if [ ! -d "$SOURCE_HOME" ]; then
  echo "Missing SOURCE_HOME: $SOURCE_HOME"
  exit 1
fi

TB="/system/bin/toybox"
if [ ! -x "$TB" ]; then
  echo "Missing toybox at /system/bin/toybox"
  exit 1
fi

echo "Preparing local runtime bundle..."
echo "  SOURCE_PREFIX=$SOURCE_PREFIX"
echo "  SOURCE_HOME=$SOURCE_HOME"
echo "  BUNDLE_VERSION=$BUNDLE_VERSION"

rm -rf "$WORK_DIR"
mkdir -p "$STAGE_DIR/files/usr/bin" "$STAGE_DIR/files/usr/lib" "$STAGE_DIR/files/usr/libexec" "$STAGE_DIR/files/usr/share" "$STAGE_DIR/files/home/.openclaw-android/native/davey"

copy_if_exists() {
  local src="$1"
  local dst="$2"
  if [ -e "$src" ]; then
    mkdir -p "$(dirname "$dst")"
    cp -a "$src" "$dst"
  fi
}

echo "Copying essential binaries..."
BIN_LIST=(
  node npm npx codex openclaw git
  python python3 pip pip3
  proot
  curl wget tar gzip sed awk grep find ps kill
)
for bin in "${BIN_LIST[@]}"; do
  if [ -e "$SOURCE_PREFIX/bin/$bin" ]; then
    cp -a "$SOURCE_PREFIX/bin/$bin" "$STAGE_DIR/files/usr/bin/"
  fi
done

echo "Copying shared libraries..."
if compgen -G "$SOURCE_PREFIX/lib/lib*.so*" >/dev/null 2>&1; then
  cp -a "$SOURCE_PREFIX/lib"/lib*.so* "$STAGE_DIR/files/usr/lib/" || true
fi

echo "Copying Node.js runtime modules..."
mkdir -p "$STAGE_DIR/files/usr/lib/node_modules"
copy_if_exists "$SOURCE_PREFIX/lib/node_modules/openclaw" "$STAGE_DIR/files/usr/lib/node_modules/openclaw"
copy_if_exists "$SOURCE_PREFIX/lib/node_modules/@openai" "$STAGE_DIR/files/usr/lib/node_modules/@openai"
copy_if_exists "$SOURCE_PREFIX/lib/node_modules/npm" "$STAGE_DIR/files/usr/lib/node_modules/npm"

echo "Pruning non-runtime OpenClaw weight..."
rm -rf "$STAGE_DIR/files/usr/lib/node_modules/openclaw/docs" \
       "$STAGE_DIR/files/usr/lib/node_modules/openclaw/extensions" \
       "$STAGE_DIR/files/usr/lib/node_modules/openclaw/.github" \
       "$STAGE_DIR/files/usr/lib/node_modules/openclaw/.changeset" 2>/dev/null || true

echo "Normalizing OpenClaw launcher shebang..."
if [ -f "$STAGE_DIR/files/usr/lib/node_modules/openclaw/openclaw.mjs" ]; then
  /system/bin/sed -i '1s|^#!/data/.*/files/usr/bin/node|#!/usr/bin/env node|' \
    "$STAGE_DIR/files/usr/lib/node_modules/openclaw/openclaw.mjs" || true
fi

echo "Copying git runtime support..."
copy_if_exists "$SOURCE_PREFIX/libexec/git-core" "$STAGE_DIR/files/usr/libexec/git-core"
copy_if_exists "$SOURCE_PREFIX/share/git-core/templates" "$STAGE_DIR/files/usr/share/git-core/templates"
copy_if_exists "$SOURCE_PREFIX/share/terminfo" "$STAGE_DIR/files/usr/share/terminfo"

echo "Copying OpenClaw native binding cache..."
if [ -f "$SOURCE_HOME/.openclaw-android/native/davey/davey.android-arm64.node" ]; then
  cp -a "$SOURCE_HOME/.openclaw-android/native/davey/davey.android-arm64.node" "$STAGE_DIR/files/home/.openclaw-android/native/davey/"
else
  echo "WARNING: davey native cache not found at $SOURCE_HOME/.openclaw-android/native/davey/davey.android-arm64.node"
fi

cat > "$STAGE_DIR/files/home/.openclaw-android/runtime-bundle.txt" <<EOF
bundleVersion=$BUNDLE_VERSION
generatedAt=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
sourcePrefix=$SOURCE_PREFIX
EOF

echo "Packing runtime archive..."
mkdir -p "$WORK_DIR"
(
  cd "$STAGE_DIR/files"
  "$TB" tar -cf - . | "$TB" gzip > "$ARCHIVE_PATH"
)

if "$TB" sha256sum /system/bin/sh >/dev/null 2>&1; then
  ARCHIVE_SHA256="$("$TB" sha256sum "$ARCHIVE_PATH" | awk '{print $1}')"
else
  ARCHIVE_SHA256="$(shasum -a 256 "$ARCHIVE_PATH" | awk '{print $1}')"
fi
ARCHIVE_SIZE="$(wc -c < "$ARCHIVE_PATH" | tr -d ' ')"

echo "Splitting runtime archive into asset parts..."
rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR"
"$TB" split -b "${PART_SIZE_MB}m" -a 3 "$ARCHIVE_PATH" "$ASSETS_DIR/runtime-core.tar.gz.part-"

PARTS=()
for name in $(cd "$ASSETS_DIR" && ls -1 runtime-core.tar.gz.part-* | sort); do
  PARTS+=("$name")
done

if [ "${#PARTS[@]}" -eq 0 ]; then
  echo "No runtime parts generated."
  exit 1
fi

MANIFEST="$ASSETS_DIR/manifest.json"
{
  echo "{"
  echo "  \"schemaVersion\": 1,"
  echo "  \"bundleVersion\": \"${BUNDLE_VERSION}\","
  echo "  \"archiveName\": \"runtime-core.tar.gz\","
  echo "  \"archiveSize\": ${ARCHIVE_SIZE},"
  echo "  \"archiveSha256\": \"${ARCHIVE_SHA256}\","
  echo "  \"parts\": ["
  for i in "${!PARTS[@]}"; do
    part="${PARTS[$i]}"
    if [ "$i" -lt "$(( ${#PARTS[@]} - 1 ))" ]; then
      echo "    \"${part}\","
    else
      echo "    \"${part}\""
    fi
  done
  echo "  ]"
  echo "}"
} > "$MANIFEST"

echo "Local runtime bundle ready."
echo "  archive=$ARCHIVE_PATH"
echo "  archive_size=$ARCHIVE_SIZE"
echo "  archive_sha256=$ARCHIVE_SHA256"
echo "  assets_dir=$ASSETS_DIR"
echo "  parts=${#PARTS[@]}"
