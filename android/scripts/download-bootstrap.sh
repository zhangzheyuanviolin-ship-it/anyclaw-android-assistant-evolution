#!/usr/bin/env bash
#
# Download Termux bootstrap archives into APK assets.
# Run this before building the APK.
#
# Usage:
#   ./scripts/download-bootstrap.sh [--arch ARCH]
#
# ARCH defaults to "aarch64". Other valid values: arm, x86_64, i686

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets"

ARCH="${1:-aarch64}"

# Use the latest bootstrap from Termux releases
BOOTSTRAP_VERSION="bootstrap-2026.02.12-r1+apt.android-7"
BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/download/${BOOTSTRAP_VERSION}/bootstrap-${ARCH}.zip"

# Alternative: SourceForge mirror
MIRROR_URL="https://sourceforge.net/projects/termux-packages.mirror/files/${BOOTSTRAP_VERSION}/bootstrap-${ARCH}.zip/download"

DEST="$ASSETS_DIR/bootstrap-${ARCH}.zip"

mkdir -p "$ASSETS_DIR"

if [ -f "$DEST" ]; then
    echo "Bootstrap already exists at $DEST"
    echo "Delete it to re-download."
    exit 0
fi

echo "Downloading bootstrap-${ARCH}.zip..."
echo "  URL: $BOOTSTRAP_URL"

if curl -fSL --retry 3 -o "$DEST" "$BOOTSTRAP_URL" 2>/dev/null; then
    echo "Downloaded from GitHub releases."
elif curl -fSL --retry 3 -L -o "$DEST" "$MIRROR_URL" 2>/dev/null; then
    echo "Downloaded from SourceForge mirror."
else
    echo "ERROR: Failed to download bootstrap archive."
    echo "You can manually download it and place it at:"
    echo "  $DEST"
    echo ""
    echo "Download from:"
    echo "  $BOOTSTRAP_URL"
    echo "  or $MIRROR_URL"
    rm -f "$DEST"
    exit 1
fi

SIZE=$(wc -c < "$DEST" | tr -d ' ')
echo "Bootstrap saved to $DEST ($SIZE bytes)"
