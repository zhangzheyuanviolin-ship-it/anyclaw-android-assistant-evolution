2026-03-25 更新：本文件现作为历史备份策略记录保留。当前正式资产备份方案请以 docs/pocket-lobster/CLOUD_BACKUP_POLICY_2026-03-25.md 为准。

2026-03-25 Update: This file is preserved as historical backup-policy context. The current authoritative backup policy is docs/pocket-lobster/CLOUD_BACKUP_POLICY_2026-03-25.md.

# 重要资产备份与公开仓库边界（2026-02-28）

## 1. 公开仓库定位

公开仓库用于：
- 源码版本管理
- 文档与迭代历史记录
- 工作流与构建流程管理

公开仓库不用于：
- 存储任何令牌明文
- 存储签名私钥与可还原私钥材料

## 2. 关键资产分类

- A 类（高敏）：
  - GitHub 高权限令牌
  - Android 签名私钥（p12 / jks / base64）
- B 类（中敏）：
  - 各类环境配置中涉及认证映射的文件
- C 类（可公开）：
  - 源码
  - 非敏感文档
  - 迭代记录

## 3. 备份策略

- A 类资产采用离线多副本备份，不进入公开 Git 历史。
- B 类资产默认放置在应用私有目录，最小权限读取。
- C 类资产持续提交到公开仓库。

## 4. 发布策略

- 发布前执行敏感文件检查，确认未将 A/B 类资产误入暂存区。
- 发布后更新文档，记录版本、变更和风险。

