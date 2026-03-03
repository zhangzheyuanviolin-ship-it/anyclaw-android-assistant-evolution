# OpenClaw 聊天页故障修复尝试记录（2026-03-03）

说明：每次尝试必须记录“假设、改动、验证结果、结论、是否可复用”。

## Attempt 1
- 时间：2026-03-03
- 假设：注入脚本语法错误 + 端口残留进程导致旧代码复用。
- 改动：修复 normalizeSpace 写法；启动前清理 19001/18923 孤儿进程；增加 readiness 检查。
- 验证结果：用户反馈仍复现，且后续出现页面原生内容消失。
- 结论：仅靠该方案不足以解决根因。
- 可复用：部分可复用（进程清理与 readiness 检查保留）。

## Attempt 2
- 时间：2026-03-03
- 假设：控制页缺 gatewayUrl 参数、WebView 缓存和注入脚本风险导致页面异常。
- 改动：URL 规范化补 gatewayUrl/simple/cache-bust；注入脚本增加 parse 校验失败自动降级。
- 验证结果：用户反馈问题仍未解决，且页面内容进一步消失。
- 结论：该方案未命中当前主要根因。
- 可复用：部分可复用（降级保护可保留）。

## Attempt 3（进行中）
- 时间：2026-03-03
- 假设：OpenClaw control-ui 资源或运行时初始化阶段存在更早失败（非网关）导致 web component 未渲染。
- 改动：待执行。
- 验证结果：待执行。
- 结论：待执行。
- 可复用：待执行。
- 证据补充：直接请求 http://127.0.0.1:19001/chat 时，注入脚本内 `normalizeSpace` 实际变成了 `replace(/s+/g," ")`，与源码预期 `\s+` 不一致，确认存在 shell/字符串转义污染。
- 证据补充：19001/18789/18923 均监听正常，资源 `/assets/index-C_C6XOMD.js` 和 `/assets/index-CM7kTShz.css` 返回 200，说明问题更偏向前端运行时初始化或注入链路，而非端口未起。

## Attempt 4
- 时间：2026-03-03
- 假设：最近若干补丁链路已污染运行路径，直接回到已知稳定源码快照可快速止损。
- 改动：将仓库跟踪文件整体对齐到 `v16`（0.3.6-beta-search-suite-p0-tavily）源码，仅将 `versionCode` 从 46 提升到 55 以保证覆盖安装。
- 验证结果：进行中（等待云端构建与用户安装验证）。
- 结论：待验证。
- 可复用：是（作为紧急回退策略）。
- 构建产物：AnyClaw_v55_0.3.6-beta-search-suite-p0-tavily-snapshot-rollback.apk
- 工作流：run 22631066096（success）
- 备注：该包基于 v16 快照源码，仅提升 versionCode 以支持覆盖安装。

## Attempt 5
- 时间：2026-03-04
- 假设：`Loading chat` 反复复发与“启动时破坏性清理+会话索引脏数据+网关鉴权与控制页状态漂移”叠加有关，且不完全属于 APK 代码覆盖范畴。
- 改动：
  - 启动网关时移除“每次启动都删除 identity/device-auth 与 token reset”的破坏性逻辑，改为仅清理锁文件与缓存。
  - 在配置阶段新增会话索引自愈：自动修复 `.openclaw/agents/main/sessions/sessions.json` 的 `agent:main:main` 映射，缺失时补齐并回填 session 文件。
  - 网关改为 `auth.mode=token`，自动生成并持久化 `gateway.auth.token`，Control UI 启动注入中强制同步 `gatewayUrl/token/session` 到本地设置。
  - 关闭 `dangerouslyDisableDeviceAuth`，保留 `allowInsecureAuth=true` 以兼容本地 loopback 控制页。
  - Android 兼容降级：默认关闭 sqlite-vec 向量扩展，保留 FTS 路径。
  - 版本治理：安装与对齐均固定到 `openclaw@2026.3.2`，避免 `latest` 漂移。
- 验证结果：待用户安装新包验证。
- 结论：该方案同时覆盖“版本层+持久化状态层”两条根因路径。
- 可复用：是（后续版本统一沿用）。

## Attempt 6
- 时间：2026-03-04
- 假设：`unauthorized: gateway token missing` 的主因是 Control UI token 注入时机晚于主模块脚本启动，触发首连竞态；并非用户手动配置错误。
- 改动：
  - 将 gateway token/session/gatewayUrl 的注入新增为“前置 early snippet”，并在 `<script type="module">` 之前写入 localStorage，避免首连时 token 为空。
  - 保留原有后置补丁脚本作为兜底与中文化逻辑，不改变用户操作入口。
  - 修正文案，移除插件提示中的 `env TAVILY_API_KEY` 误导描述。
  - 上调版本号以便系统侧明确区分新旧包。
- 验证结果：待云端构建后安装验证。
- 结论：该方案可在保持 token 鉴权的同时避免手动粘贴 token。
- 可复用：是（可作为后续 gateway 鉴权默认注入策略）。
