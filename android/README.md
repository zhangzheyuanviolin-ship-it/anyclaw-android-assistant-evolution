# Pocket Lobster for Android

Pocket Lobster for Android is the native Android packaging layer for the Pocket Lobster project. The APK embeds a Termux-style Linux bootstrap environment, installs OpenClaw and Codex on first run, and presents the Pocket Lobster control interface inside a WebView.

## Architecture

```
┌─────────────────────────────────────────┐
│              Android APK                │
│                                         │
│  ┌──────────────┐  ┌────────────────┐   │
│  │   WebView    │  │  Bootstrap     │   │
│  │              │  │  Installer     │   │
│  │ localhost:   │  │                │   │
│  │   18923      │  │  Extracts      │   │
│  │              │  │  Linux env     │   │
│  └──────┬───────┘  └───────┬────────┘   │
│         │                  │            │
│         ▼                  ▼            │
│  ┌──────────────────────────────────┐   │
│  │    /data/data/com.codex.mobile/  │   │
│  │    files/usr/  (runtime prefix)  │   │
│  │                                  │   │
│  │    ├── bin/node                   │   │
│  │    ├── bin/codex                  │   │
│  │    └── lib/node_modules/         │   │
│  │        └── runtime components    │   │
│  └──────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

## Prerequisites

- Android Studio or Android SDK command-line tools
- Java 17+
- curl for bootstrap download helpers

## Build Instructions

### 1. Download the bootstrap

```bash
cd android
./scripts/download-bootstrap.sh
```

### 2. Bundle the server assets

```bash
./scripts/build-server-bundle.sh
```

### 3. Build the APK

```bash
./gradlew assembleDebug
```

For a release build:

```bash
./gradlew assembleRelease
```

## First Run

On first launch, the app will extract the bootstrap environment, install required packages, prepare Codex and OpenClaw runtime components, and start the embedded control interface.

## Minimum Requirements

- Android 7.0 (API 24) or higher
- arm64-v8a device
- Enough free storage for the runtime, dependencies, and generated data
- Internet access for first-run downloads and cloud-connected tasks
