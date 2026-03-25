# 口袋大龙虾 版本谱系与稳定基线
生成日期：2026-03-25

## 中文版本

目前已确认的关键版本关系如下。

`v157` 和 `v158` 对应内部版本线 `v161`，共同指向提交 `b068425e0f629bc9244451f2565179eae2f382a8`。

`v159` 对应内部版本 `v162`，对应提交 `4f6077af4a840476b98d42c36e69df9ce04b3bd5`。

`v160` 对应内部版本 `v163`，对应提交 `7a038e6a6273a556d9ac79420502a0cf88bf0b11`。

`v161` 是较早的 sidecar 验证包，包名为 `com.codex.mobile.pocketlobster.v162verify`，但尚未修复 Codex 对 Ubuntu 运行时的稳定识别问题。

`v162` 对应当前已经通过覆盖更新与重新安装双重验证的 sidecar 修复版，提交为 `84f3bbdcf1735e6eb8def5d38c069b44f48dbe9d`，包名为 `com.codex.mobile.pocketlobster.v162verify`，内部 versionCode 为 `163`。

当前稳定联络环境是设备上已经安装的 `com.codex.mobile.pocketlobster.test`，versionCode 为 `162`。当前主分支正式发布配置已经切换到 `com.codex.mobile.pocketlobster`，并作为首个正式发布通道的默认包名。

## English Version

The currently verified version lineage is as follows.

`v157` and `v158` both map to the internal `v161` line and point to commit `b068425e0f629bc9244451f2565179eae2f382a8`.

`v159` maps to internal version `v162`, built from commit `4f6077af4a840476b98d42c36e69df9ce04b3bd5`.

`v160` maps to internal version `v163`, built from commit `7a038e6a6273a556d9ac79420502a0cf88bf0b11`.

`v161` was an earlier sidecar verification package under `com.codex.mobile.pocketlobster.v162verify`, but it did not yet resolve the stable Ubuntu-runtime visibility problem for Codex.

`v162` is the sidecar repair build that passed both in-place upgrade testing and clean reinstall testing. It corresponds to commit `84f3bbdcf1735e6eb8def5d38c069b44f48dbe9d`, package name `com.codex.mobile.pocketlobster.v162verify`, and internal versionCode `163`.

The stable operator environment currently installed on-device remains `com.codex.mobile.pocketlobster.test` with versionCode `162`. The main branch now targets `com.codex.mobile.pocketlobster` as the default official release package.
