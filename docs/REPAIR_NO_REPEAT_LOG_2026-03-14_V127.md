PocketLobster test package repair log (do-not-repeat baseline)

Date: 2026-03-14
Scope: Gateway instability, token mismatch, openclaw not found, stalled chat.

Confirmed invalid hypotheses (do not retry the same way):
1) "Only package-level port conflict between test and main app" as sole root cause.
   - Even single-package installs reproduced failures.
2) "UI reload/renderer-only fixes" for no-reply/stuck processing.
   - Traditional dashboard and new chat UI both failed under the same runtime state.
3) "One-off restart and naive retry scripts" without process/source isolation.
   - Produced temporary improvement but regressed after reinstall.

Confirmed root-cause chain:
1) Both app packages can keep background gateway/server/proxy alive (foreground service, START_STICKY).
2) Fixed ports across packages allow stale listeners and cross-instance collisions (EADDRINUSE).
3) Runtime script helpers using bundled grep/sed may fail under Android linker mismatch, so cleanup silently fails.
4) Startup readiness can pass against stale listeners, causing token mismatch and false-ready.

Required hard fixes implemented in this round:
1) Per-package dynamic port resolution for server/proxy/gateway/control-ui/shizuku bridge.
2) All major local RPC URL builders switched to resolved ports.
3) Runtime script patching switched from bundled grep/sed to /system/bin equivalents where critical.
4) Stale process cleanup hardened via /proc scanning tied to current app sandbox path.
5) Proxy/server startup now checks child-process early exit to fail fast.
6) Server wait loop now aborts if the just-spawned process exited before readiness.

Acceptance gate for next rounds:
1) Must validate clean-install path (not only upgrade path).
2) Must validate with main package not opened during test package acceptance.
3) If regression returns, compare against this log first and avoid reusing invalid hypotheses.
