#!/usr/bin/env bash
#
# Build the codex-web-local frontend and CLI, then copy them into
# APK assets so they can optionally be deployed without npm install.
#
# Usage:
#   ./scripts/build-server-bundle.sh
#
# Prerequisites:
#   - Node.js and npm installed on the build machine
#   - Run from the android/ directory OR the project root

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_ROOT="$(dirname "$ANDROID_DIR")"

ASSETS_DIR="$ANDROID_DIR/app/src/main/assets/server-bundle"

echo "=== Building codex-web-local ==="

cd "$PROJECT_ROOT"

# Install dependencies if needed
if [ ! -d "node_modules" ]; then
    echo "Installing npm dependencies..."
    npm install
fi

# Build frontend (Vue) and CLI (Express server)
echo "Building frontend..."
npm run build:frontend

echo "Building CLI server..."
npm run build:cli

# Copy the built artifacts into assets
echo "Copying build artifacts to Android assets..."
rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR/dist"
mkdir -p "$ASSETS_DIR/dist-cli"

cp -r "$PROJECT_ROOT/dist/"* "$ASSETS_DIR/dist/"
cp -r "$PROJECT_ROOT/dist-cli/"* "$ASSETS_DIR/dist-cli/"
cp "$PROJECT_ROOT/package.json" "$ASSETS_DIR/package.json"

# Install production dependencies into the bundle
echo "Installing production dependencies for bundle..."
cd "$ASSETS_DIR"
npm install --omit=dev --ignore-scripts 2>/dev/null || true
cd "$PROJECT_ROOT"

echo ""
echo "=== Server bundle ready ==="
echo "Location: $ASSETS_DIR"
du -sh "$ASSETS_DIR" 2>/dev/null || true
