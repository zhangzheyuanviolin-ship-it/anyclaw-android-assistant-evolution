#!/system/bin/sh
#
# First-run setup script for Codex inside the Termux bootstrap environment.
# Called by the Android app after bootstrap extraction, or can be run manually
# from a shell inside the prefix.
#
# This script:
#   1. Updates the package index
#   2. Installs Node.js LTS
#   3. Installs @openai/codex and codex-web-local globally via npm
#
# Exit codes:
#   0 = success
#   1 = package install failure
#   2 = npm install failure

set -eu

echo "[setup] Updating package index..."
apt-get update -y || {
    echo "[setup] WARNING: apt-get update failed, continuing anyway"
}

echo "[setup] Installing Node.js LTS..."
apt-get install -y nodejs-lts || {
    echo "[setup] ERROR: Failed to install nodejs-lts"
    exit 1
}

echo "[setup] Node.js version: $(node --version)"
echo "[setup] npm version: $(npm --version)"

echo "[setup] Installing @openai/codex..."
npm install -g @openai/codex || {
    echo "[setup] ERROR: Failed to install @openai/codex"
    exit 2
}

echo "[setup] Installing codex-web-local..."
npm install -g codex-web-local || {
    echo "[setup] ERROR: Failed to install codex-web-local"
    exit 2
}

echo "[setup] Codex CLI: $(codex --version 2>/dev/null || echo 'installed')"
echo "[setup] Setup complete!"
