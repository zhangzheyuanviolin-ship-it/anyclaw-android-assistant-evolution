# 口袋大龙虾 云端资产备份方案
生成日期：2026-03-25

## 中文版本

本项目采用手机本地与 GitHub 云端双重备份体系，目标是在手机本地误删后仍然能够恢复关键安装包、源码快照、工作流产物和版本索引。

本地第一现场归档目录固定为 `/sdcard/Download/口袋大龙虾本地仓库`。这个目录保存稳定基线安装包、工作流 artifact、源码快照、运行时资产、版本映射、校验文件和审核文案。

云端第二现场备份使用同一 GitHub 仓库中的恢复 release 与恢复分支。大体积二进制资产放入 GitHub Releases 的 assets，中小型索引文件、SHA256 清单、版本谱系、恢复说明和治理文档放入仓库文本文件持续维护。

备份原则是每个重要版本都必须同时具备 APK、源码提交号、SHA256 校验、来源说明和恢复路径，避免出现版本号相近但真实内容不同的混淆。

## English Version

Pocket Lobster uses a dual backup strategy across local device storage and GitHub cloud storage so that critical APKs, source snapshots, workflow artifacts, and version indexes remain recoverable even after accidental local deletion.

The primary on-device archive is fixed at `/sdcard/Download/口袋大龙虾本地仓库`. It stores stable baseline APKs, workflow artifacts, source snapshots, runtime assets, version mappings, checksum manifests, and reviewed release documents.

The secondary cloud backup uses a recovery release and a recovery branch inside the same GitHub repository. Large binaries are stored as GitHub Release assets, while smaller manifests, SHA256 indexes, version lineage files, recovery notes, and governance documents are maintained directly in the repository.

The governing rule is that every important version must be traceable through an APK, an exact source commit, checksum records, provenance notes, and a clear recovery path, so similar version numbers can never become ambiguous again.
