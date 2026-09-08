# 001：MVP 阶段的模块策略

## 背景

架构规划包含 `core`、`report`、`performance`、`compose`、`tracelet-sdk` 和 `app`。当前仓库已完成分层模块化拆分，`app` 以 SDK 公共接口接入并验证卡顿采集闭环。

## 决策

MVP 早期先保持单 Gradle 模块以验证闭环；稳定后按固定顺序拆分成独立 Gradle 模块：

1. 已建立 `tracelet-core`：事件模型、接口、生命周期与配置。
2. 已拆分 `tracelet-report` 与 `tracelet-performance`：实现层，只依赖 `core`。
3. 已建立 `tracelet-sdk`：对外 API + 默认组装，宿主只需依赖它。

`compose` 作为可选模块继续保留，直到出现实际 Compose 适配 API 后再建立。依赖方向固定为 `core → report/performance → tracelet-sdk → app`，禁止反向依赖。

## 原因

这样可以先验证完整的卡顿检测闭环，再在协议稳定后按固定顺序拆分，避免反复调整 Gradle 模块边界。`tracelet-sdk` 作为唯一对外入口，让后续新增检测器时不改动宿主代码。

## 影响

模块拆分阶段禁止跨层反向依赖；Demo 代码不能进入 SDK 包。宿主只依赖 `tracelet-sdk`，不直接依赖 `core`/`report`/`performance` 内部实现；`app` 通过 SDK 公共 API 驱动真实采集链路进行自动化验收。
