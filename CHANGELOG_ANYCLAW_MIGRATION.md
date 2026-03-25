2026-03-25 Update: This changelog is preserved as historical migration context from the earlier legacy-branded stage. The current formal project name is Pocket Lobster, and current release-facing materials now live under docs/pocket-lobster/.

2026-03-25 更新：本文件作为早期旧命名阶段的历史迁移记录保留。当前正式项目名称为口袋大龙虾，面向当前版本的正式文档已统一收口到 docs/pocket-lobster/ 目录。

# CHANGELOG (Historical Migration Track)

## 2026-02-28

- Completed full migration handover in the earlier Android test environment.
- Verified and stabilized capability baseline:
  - Global shared storage access
  - Shizuku system-shell execution
  - OpenClaw direct chat readiness
- Reconstructed OpenClaw auth mapping from in-app Codex credentials.
- Fixed migration scripts for current app sandbox paths.
- Added fallback strategy for package-repair download path in constrained apt environments.
- Imported source snapshot baseline (`72f8f53`) into active workspace.
- Added handover documentation and lineage records to repository docs.
- Implemented OpenClaw memory/toolchain preflight with external recovery fallback:
  - `ensureOpenClawToolchain`
  - `runOpenClawToolchainPreflight`
  - `runExternalRecoveryScripts`
  - `runOpenClawPreflight`
- Added Conversation Manager entry and activity:
  - Unified list across Codex threads and OpenClaw sessions
  - Per-session rename/delete/copy/export actions
- Added Model Manager entry and activity for OpenClaw:
  - Switch primary model
  - Edit provider `baseUrl` and `apiKey`
- Added explicit "Delete selected prompt" action in Prompt Manager.
- Added per-message operations in Codex chat UI:
  - copy
  - delete from message (rollback)
  - branch from message (fork + rollback)
- Bumped app version to `versionCode=35`, `versionName=0.2.5-beta-memoryfix-dialog-modelmgr-fixedsign1`.

## 2026-02-27

- Finalized migration package and handover bundle.
- Consolidated fixed-signing chain documentation and checksums.
- Consolidated dual-token security rules (Token A for model auth, Token B for GitHub release pipeline).
- Documented short/mid/long-term roadmap aligned with Operit reference direction.
