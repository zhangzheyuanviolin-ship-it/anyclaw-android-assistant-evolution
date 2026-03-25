# 口袋大龙虾 三通道包名与发布治理
生成日期：2026-03-25

## 中文版本

当前项目固定采用三通道包名策略，用来同时保证正式发布、内部测试和稳定联络三条线互不影响。

稳定联络通道固定保留当前已经安装并经过长期验证的包名 `com.codex.mobile.pocketlobster.test`。这条通道只承担张老师与 Codex 的稳定联络、环境维护和项目推进，不承担高风险新功能试错。

内部测试通道固定为 `com.codex.mobile.pocketlobster.beta`。后续所有新功能、灰度修复、实验性能力和覆盖更新验证都优先在这条通道完成。

正式发布通道固定为 `com.codex.mobile.pocketlobster`。主分支当前默认发布配置已经对齐这条正式通道，后续公开 release 使用该包名和正式图标名称 `口袋大龙虾`。

执行原则是先在内部测试通道完成覆盖更新与重装验证，再考虑提升到正式发布通道；稳定联络通道默认冻结，除非存在必须迁移且已经充分验证的理由。

## English Version

Pocket Lobster uses a three-channel package strategy so that public releases, internal testing, and the stable operator contact environment never overwrite each other by accident.

The stable operator channel remains permanently pinned to `com.codex.mobile.pocketlobster.test`. This channel exists to preserve the working Codex environment that Zhang Laoshi relies on for coordination, maintenance, and ongoing development.

The internal testing channel is fixed as `com.codex.mobile.pocketlobster.beta`. All new features, experimental capabilities, repair builds, and upgrade-validation work should be delivered here first.

The official release channel is fixed as `com.codex.mobile.pocketlobster`. The main branch now aligns its default release configuration with this public channel and uses the normal launcher label `口袋大龙虾`.

The governing rule is simple: validate in the internal testing channel first, promote to the public release channel only after upgrade and reinstall checks pass, and keep the stable operator channel frozen unless a migration is absolutely necessary and fully verified.
