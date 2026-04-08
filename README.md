<div align="center">

# 🦞 AnyClaw

### 🚀 OpenClaw + Codex + Claude Code — Running Natively on Android 🚀

[![Android](https://img.shields.io/badge/Android-7.0+-3DDC84?logo=android&logoColor=white&style=for-the-badge)](https://developer.android.com)
[![OpenClaw](https://img.shields.io/badge/OpenClaw-Gateway-E8941A?logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxMDAgMTAwIj48dGV4dCB5PSIuOWVtIiBmb250LXNpemU9IjkwIj7wn6aePC90ZXh0Pjwvc3ZnPg==&style=for-the-badge)](https://openclaw.ai)
[![Codex](https://img.shields.io/badge/Codex_CLI-Agent-4A90D9?logo=openai&logoColor=white&style=for-the-badge)](https://github.com/openai/codex)
[![Claude Code](https://img.shields.io/badge/Claude_Code-on_Android!-D97706?logo=anthropic&logoColor=white&style=for-the-badge)](https://claw-code.codes)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white&style=for-the-badge)](https://kotlinlang.org)
[![Status](https://img.shields.io/badge/Status-🔥%20IT%20WORKS-brightgreen?style=for-the-badge)](https://friuns2.github.io/openclaw-android-assistant/)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)](LICENSE)

<br />

> **Three AI coding agents. One APK. Your pocket.**
> **OpenClaw gateway + OpenAI Codex CLI + Claude Code (via Claw Code / OpenClaude)**
> **— all running in a full Linux environment on your Android phone.**

<br />

```
 █████╗ ███╗   ██╗██╗   ██╗ ██████╗██╗      █████╗ ██╗    ██╗
██╔══██╗████╗  ██║╚██╗ ██╔╝██╔════╝██║     ██╔══██╗██║    ██║
███████║██╔██╗ ██║ ╚████╔╝ ██║     ██║     ███████║██║ █╗ ██║
██╔══██║██║╚██╗██║  ╚██╔╝  ██║     ██║     ██╔══██║██║███╗██║
██║  ██║██║ ╚████║   ██║   ╚██████╗███████╗██║  ██║╚███╔███╔╝
╚═╝  ╚═╝╚═╝  ╚═══╝   ╚═╝    ╚═════╝╚══════╝╚═╝  ╚═╝ ╚══╝╚══╝
         T H R E E  ·  A G E N T S  ·  O N E  ·  A P K
```

</div>

[Download APK](https://friuns2.github.io/openclaw-android-assistant/) ·
[Google Play](https://play.google.com/store/apps/details?id=gptos.intelligence.assistant&hl=en) ·
[OpenClaw Docs](https://docs.openclaw.ai) ·
[Project Spec](PROJECT_SPEC.md)

---

## 🤯 What Is This?

This project puts **three AI coding agents** on your Android phone in a single APK:

- **[OpenClaw](https://openclaw.ai)** — a personal AI assistant with multi-channel support, agent routing, skills, Canvas, and a full Control UI dashboard
- **[OpenAI Codex CLI](https://github.com/openai/codex)** — a terminal-based coding agent that reads your codebase, writes code, and runs commands
- **[Claw Code](https://claw-code.codes) (OpenClaude)** — the leaked Claude Code architecture, rebranded as OpenClaude, now running on Android. **Yes, Claude Code on your phone.**

All three run inside an embedded Linux environment extracted from the APK. No root required. No Termux dependency. Everything is self-contained.

> 🧠 **TL;DR** — We took the Claude Code source (leaked from Anthropic's npm package, 512K lines of TypeScript), which was rewritten as [Claw Code](https://github.com/instructkr/claw-code) (48K+ GitHub stars) and rebranded as OpenClaude — and made it run on Android alongside OpenClaw and Codex. **Three AI coding agents in your pocket.**

---

## 🆕 Claude Code on Android — via Claw Code / OpenClaude

In March 2026, Anthropic's Claude Code had its [full source code accidentally leaked](https://claw-code.codes/source-leak-timeline) via an npm source map. The community built [Claw Code](https://github.com/instructkr/claw-code) — a clean-room rewrite that hit 48K+ stars in hours. It was later rebranded as **OpenClaude**.

**We ported it to Android.** It now runs inside AnyClaw alongside OpenClaw and Codex:

| | What | Description |
|---|---|---|
| 🦀 | **Claw Code / OpenClaude** | Claude Code architecture — 19 tools, 15 slash commands, multi-agent swarms, MCP support |
| 🔧 | **19 Built-in Tools** | File I/O, shell execution, Git operations, web scraping, notebook editing — permission-gated |
| 🧠 | **Query Engine** | LLM API calls, response streaming, caching, multi-step orchestration |
| 🔌 | **MCP Integration** | Model Context Protocol with 6 transport types — connect external tool servers |
| 👥 | **Multi-Agent Swarms** | Spawn sub-agents to parallelize tasks in isolated contexts |
| 🔮 | **Provider Agnostic** | Claude, OpenAI, local models — not locked to Anthropic |
| ⚡ | **Rust Core** | 6-crate workspace, 16 runtime modules, native aarch64-musl binary |

---

## 📱 Screenshots

<div align="center">
<table>
<tr>
<td align="center" width="50%">
<img src="https://raw.githubusercontent.com/friuns2/openclaw-android-assistant/main/screenshots/screenshot.png" width="300" />
<br /><b>💬 AI Coding Agent</b><br />
<sub>Full conversational coding with streaming responses and reasoning visibility.</sub>
</td>
<td align="center" width="50%">
<img src="https://raw.githubusercontent.com/friuns2/openclaw-android-assistant/main/screenshots/screenshot2.png" width="300" />
<br /><b>🧩 Dashboard & Sessions</b><br />
<sub>Multi-thread sessions, agent routing, skills, Canvas — running on your phone.</sub>
</td>
</tr>
</table>
</div>

---

## 🌍 What Can You Do?

| | Feature | Description |
|---|---|---|
| 🦞 | **OpenClaw Dashboard** | Full Control UI — chat, agents, sessions, skills, Canvas. Runs locally on your device |
| 💬 | **Codex Chat** | Conversational coding agent with streaming responses and reasoning visibility |
| 🦀 | **Claude Code (OpenClaude)** | Full Claw Code agent — 19 tools, query engine, MCP, multi-agent swarms |
| 🐧 | **Embedded Linux** | Complete Termux-derived userland — sh, apt, Node.js, Python, SSL certs |
| ⚡ | **Execute Commands** | All three agents run shell commands in the embedded Linux environment |
| 🧵 | **Multi-Thread Sessions** | Parallel conversations, each with its own context and working directory |
| 🔓 | **Full Auto-Approval** | No permission popups — `danger-full-access` mode by default |
| 🔋 | **Background Execution** | Foreground service keeps everything alive when you switch apps |
| 🔑 | **OAuth Login** | One-time browser-based auth — shared between all agents |
| 📴 | **Offline Bootstrap** | Linux environment extracted from APK — works without internet after setup |

---

## ⚡ Quick Start

```bash
git clone https://github.com/friuns2/openclaw-android-assistant.git
cd openclaw-android-assistant

npm install && npm run build

cd android && bash scripts/download-bootstrap.sh
bash scripts/build-server-bundle.sh && ./gradlew assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.codex.mobile/.MainActivity
```

Or [download the latest APK](https://friuns2.github.io/openclaw-android-assistant/) directly, or get it from [Google Play](https://play.google.com/store/apps/details?id=gptos.intelligence.assistant&hl=en).

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────┐
│                      Android APK                          │
│                                                           │
│  ┌────────────┐  ┌──────────────────────────────────────┐ │
│  │  WebView   │  │  APK Assets                          │ │
│  │  (Vue.js)  │  │  bootstrap-aarch64.zip               │ │
│  └─────┬──────┘  │  server-bundle/ (Vue + Express)      │ │
│        │         │  proxy.js / bionic-compat.js          │ │
│        │         └──────────────────────────────────────┘ │
│  ┌─────▼────────────────────────────────────────────────┐ │
│  │             CodexServerManager                        │ │
│  │  Bootstrap → Node.js → Codex + OpenClaw → Auth        │ │
│  │  Proxy → Gateway → Control UI → Web Server            │ │
│  └─────┬────────────────────────────────────────────────┘ │
│        │                                                  │
│  ┌─────▼────────────────────────────────────────────────┐ │
│  │             Embedded Linux ($PREFIX)                   │ │
│  │                                                       │ │
│  │  codex-web-local   → :18923 (HTTP, WebView target)    │ │
│  │    └─ codex app-server (native Rust/musl, JSON-RPC)   │ │
│  │                                                       │ │
│  │  openclaw gateway  → :18789 (WebSocket)               │ │
│  │  openclaw ctrl UI  → :19001 (static file server)      │ │
│  │                                                       │ │
│  │  claw-code agent   → Claude Code architecture         │ │
│  │                                                       │ │
│  │  proxy.js          → :18924 (CONNECT proxy, DNS/TLS)  │ │
│  └───────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

### Services

| Port  | Service           | Purpose                                             |
| ----- | ----------------- | --------------------------------------------------- |
| 18789 | OpenClaw Gateway  | WebSocket control plane for agents, sessions, tools |
| 18923 | codex-web-local   | HTTP server with Vue.js UI (WebView target)         |
| 18924 | CONNECT Proxy     | DNS/TLS bridge for musl-linked binaries             |
| 19001 | Control UI Server | Static file server for OpenClaw dashboard           |

---

## 🔥 How It Works

### 🐧 Embedded Linux

The APK bundles Termux's `bootstrap-aarch64.zip` — a minimal Linux userland with `sh`, `apt-get`, `dpkg-deb`, SSL certificates, and core libraries. On first launch, it's extracted to the app's private storage at `$PREFIX = /data/user/0/com.codex.mobile/files/usr`.

### 🦀 Native Rust Binary

The Codex CLI ships a 73MB native Rust binary compiled for `aarch64-unknown-linux-musl`. npm refuses to install it on Android, so we download the tarball directly from the npm registry and extract it manually.

### 🔌 DNS/TLS Proxy

The musl-linked binary reads `/etc/resolv.conf` for DNS — which doesn't exist on Android. A Node.js CONNECT proxy on port 18924 bridges this: Node.js uses Android's Bionic DNS resolver, and the native binary routes all HTTPS through `HTTPS_PROXY`.

### 🤖 Android Compatibility

The `bionic-compat.js` shim patches `process.platform` to return `"linux"`, fixes `os.cpus()` and `os.networkInterfaces()` for Android's non-standard formats.

### 🔓 W^X Bypass

Android 10+ enforces SELinux W^X (Write XOR Execute) policies. We use `targetSdk = 28` to bypass this, same approach as Termux (F-Droid).

---

## 🚀 Startup Sequence

1. 🔋 Battery optimization exemption + foreground service
2. 🐧 Bootstrap extraction (Termux userland)
3. 📦 proot + Node.js + Python installation
4. 🔧 `bionic-compat.js` extraction
5. 🦞 OpenClaw build deps + install + koffi build + path patching
6. 🦀 Claw Code / OpenClaude agent installation
7. 💻 Codex CLI + native platform binary installation
8. ⚙️ Full-access config (`approval_policy = "never"`)
9. 🔌 CONNECT proxy startup
10. 🔑 OAuth login (`codex login` via browser)
11. ✅ Health check
12. 🏗️ OpenClaw auth config + gateway + Control UI server
13. 🌐 codex-web-local server startup
14. 📱 WebView loads `http://127.0.0.1:18923/`

---

## 📁 Project Structure

```
android/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/
│   │   ├── proxy.js                 # CONNECT proxy (DNS/TLS bridge)
│   │   ├── bionic-compat.js         # Android platform shim
│   │   └── server-bundle/           # Pre-built Vue + Express + deps
│   └── java/com/codex/mobile/
│       ├── BootstrapInstaller.kt    # Linux environment setup
│       ├── CodexForegroundService.kt # Background persistence
│       ├── CodexServerManager.kt    # Install, auth, proxy, OpenClaw, server
│       └── MainActivity.kt         # WebView + setup orchestration
├── scripts/
│   ├── download-bootstrap.sh        # Fetch Termux bootstrap
│   └── build-server-bundle.sh       # Bundle frontend into APK assets
src/                                  # codex-web-local (TypeScript + Vue)
├── api/                              # RPC client, gateway, SSE
├── components/                       # Vue components (composer, threads, sidebar)
├── composables/                      # useDesktopState (reactive state)
├── server/                           # Express + codex app-server bridge
└── cli/                              # CLI entry point
clawcode-android/                     # Claw Code / OpenClaude (Python + Rust)
```

---

## 🎯 Requirements

- 📱 **Android 7.0+** (API 24) — ARM64 device
- 🌐 **Internet connection** — for first-run setup + API calls
- 🔑 **OpenAI account** — authenticated via OAuth browser flow
- 💾 **~500MB storage** — for Linux environment + Node.js + Codex + OpenClaw + Claw Code

---

## 🧬 Tech Stack

| Layer       | Technology                      | Version        |
| ----------- | ------------------------------- | -------------- |
| 🦞 AI Gateway  | OpenClaw                    | 2026.2.21-2    |
| 💻 AI Agent    | OpenAI Codex CLI             | 0.104.0        |
| 🦀 AI Agent    | Claw Code / OpenClaude       | latest         |
| 🧠 Model       | gpt-5.3-codex (via Codex OAuth) | —           |
| 🔮 LLM Support | Provider-agnostic (Claude, OpenAI, local) | — |
| ⚙️ Runtime     | Node.js (via Termux)          | 24.13.0        |
| 🔧 Build Tools | Clang/LLVM, CMake, Make, LLD  | 21.1.8 / 4.2.3 |
| 🖥️ Frontend    | Vue.js 3 + Vite + TailwindCSS | 3.x           |
| 📱 Android     | Kotlin + WebView              | 2.1.0          |
| 🐧 Linux       | Termux bootstrap (aarch64)    | —              |

---

## 🐛 Troubleshooting

| Problem                                | Solution                                                   |
| -------------------------------------- | ---------------------------------------------------------- |
| App crashes on launch                  | Check `adb logcat` for errors                              |
| "Permission denied" executing binaries | Ensure `targetSdk = 28` in `build.gradle.kts`              |
| OpenClaw gateway fails to start        | Check `~/.openclaw/openclaw.json` config and auth-profiles |
| koffi build fails                      | Verify clang/cmake/make are installed and binary-patched   |
| "No address associated with hostname"  | Check internet; CONNECT proxy may not be running           |
| Login page doesn't open                | Ensure a default browser is set on the device              |
| App killed in background               | Grant battery optimization exemption in Android settings   |

---

## 📜 Credits

- 🦞 [OpenClaw](https://openclaw.ai) — Personal AI assistant by Peter Steinberger and community
- 💻 [OpenAI Codex CLI](https://github.com/openai/codex) — Terminal-based coding agent
- 🦀 [Claw Code](https://github.com/instructkr/claw-code) — Open-source Claude Code rewrite (48K+ ⭐), rebranded as OpenClaude
- 🌐 [Claw Code Website](https://claw-code.codes) — Architecture docs, tool system, comparisons
- 📱 [AidanPark/openclaw-android](https://github.com/AidanPark/openclaw-android) — Android patches and bionic-compat.js
- 🐧 [Termux](https://termux.dev) — Linux environment bootstrap for Android

---

<div align="center">

**Three AI agents. One APK. Your pocket.** 🔬
*They leaked Claude Code. We put it on a phone.* 😏

</div>

[Download APK](https://friuns2.github.io/openclaw-android-assistant/) · [Google Play](https://play.google.com/store/apps/details?id=gptos.intelligence.assistant&hl=en) · [OpenClaw Docs](https://docs.openclaw.ai) · [Project Spec](PROJECT_SPEC.md)
