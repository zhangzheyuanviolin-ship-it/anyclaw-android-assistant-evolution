# 口袋大龙虾 Pocket Lobster

## 中文介绍

口袋大龙虾是一款运行在普通 Android 手机上面的双智能体原生 AI 助手应用。它把 OpenAI 旗下的编码智能体 Codex 与 2026 年以来最具代表性的全能私人 AI 智能体形态之一 OpenClaw 同时带到一台非 root 安卓手机上，并让它们共享安卓原生终端、完整 Ubuntu Linux 运行时和通过 Shizuku 打通的 system shell 三条执行链路。

## English Overview

Pocket Lobster is a dual-agent native AI assistant for ordinary Android phones. It brings OpenAI Codex, one of the strongest coding agents in the field, together with OpenClaw, one of the most representative general-purpose personal AI agent systems of 2026, onto a non-root Android device and gives them three shared execution paths: an Android-native terminal, a full Ubuntu Linux runtime, and a Shizuku-enabled system shell.

## 当前核心能力 Core Capabilities

- 双智能体协作：Codex 负责强工程与代码执行，OpenClaw 负责更广义的任务代理与交付。 English: Dual-agent collaboration, with Codex focused on engineering and code execution, and OpenClaw focused on broader task orchestration and delivery.
- 双终端环境：同时提供安卓原生终端与完整 Ubuntu Linux 开发环境。 English: Dual terminal environments, including an Android-native terminal and a full Ubuntu Linux development runtime.
- 系统级链路：通过 Shizuku 打通 system shell，并具备 UI 自动化基础能力。 English: A system-level execution path through a Shizuku-backed system shell, with a foundation for UI automation.
- 全局文件访问：支持共享存储全局文件处理与结果直接交付。 English: Global shared-storage file access so agents can read, process, and deliver results directly in user-visible locations.
- 网络工具链：支持多搜索源、网页访问、Web 自动化、MCP 与 skills 扩展。 English: Network tooling with multi-source search, web access, web automation, MCP connectivity, and skills extensibility.
- 手机友好交互：提供权限管理、提示词管理、对话管理、模型管理和附件上传入口。 English: Phone-friendly interaction with dedicated entry points for permission management, prompt profiles, conversation management, model management, and file attachments.
- 自进化能力：智能体可直接参与修改源码、触发云端构建、产出并安装新版本。 English: Self-evolution capability, allowing agents to modify source code, trigger cloud builds, produce new APKs, and install updated builds.

## 正式文档 Official Documents

- [项目正式介绍 Project Overview](docs/pocket-lobster/PROJECT_OVERVIEW_2026-03-25.md)
- [首版正式发布文案 First Release Copy](docs/pocket-lobster/FIRST_RELEASE_COPY_2026-03-25.md)
- [三通道包名与发布治理 Release Channels And Package IDs](docs/pocket-lobster/RELEASE_CHANNELS_AND_PACKAGE_IDS_2026-03-25.md)
- [上游血缘与许可证说明 Upstream And License](docs/pocket-lobster/UPSTREAM_AND_LICENSE_2026-03-25.md)
- [云端资产备份方案 Cloud Backup Policy](docs/pocket-lobster/CLOUD_BACKUP_POLICY_2026-03-25.md)
- [版本谱系与稳定基线 Version Lineage](docs/pocket-lobster/VERSION_LINEAGE_2026-03-25.md)

## 项目血缘 Project Lineage

- 官方源头 Official source: `openclaw/openclaw`
- 直接安卓实现上游 Direct Android upstream: `friuns2/openclaw-android-assistant`
- 当前项目 Current project: `zhangzheyuanviolin-ship-it/pocket-lobster-android`

## 当前稳定基线 Current Stable Baseline

当前已经通过覆盖更新与重新安装双重验证、确认两个智能体都能稳定使用三条执行链路的验证基线，是 `cloud_run162_sidecar_v163_codex_ubuntu_fix.apk`，对应提交 `84f3bbdcf1735e6eb8def5d38c069b44f48dbe9d`。 English: The currently verified stable baseline, confirmed through both in-place upgrade testing and clean reinstall testing with all three execution paths working for both agents, is `cloud_run162_sidecar_v163_codex_ubuntu_fix.apk`, built from commit `84f3bbdcf1735e6eb8def5d38c069b44f48dbe9d`.
