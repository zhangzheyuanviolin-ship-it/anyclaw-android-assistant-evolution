# CHANGELOG (AnyClaw Migration Track)

## 2026-02-28

- Completed full migration handover in Android AnyClaw test environment.
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
