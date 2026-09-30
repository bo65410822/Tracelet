# Tracelet 文档索引

本目录汇总 Tracelet 各模块的设计、决策与规范文档。按模块和主题组织，便于查阅与维护。

## 总览

- [vision.md](vision.md) — 产品愿景与目标
- [architecture.md](architecture.md) — 总体架构、模块职责与依赖方向
- [mvp.md](mvp.md) — MVP 范围
- [api.md](api.md) — 对外 API
- [development.md](development.md) — 开发说明
- [testing.md](testing.md) — 测试约定
- [event-schema.md](event-schema.md) — 诊断事件 schema

## 诊断核心（core / performance / report / sdk）

- [architecture.md](architecture.md) — 诊断分层与采集器准入规则
- [event-schema.md](event-schema.md) — 事件模型与版本

## 蓝牙模块（tracelet-ble）

- [ble-sdk-design.md](ble-sdk-design.md) — 统一蓝牙设备 SDK 设计（含 4.2 扫描实现说明）
- [ble-connection-design.md](ble-connection-design.md) — 连接与 GATT 操作实现设计与决策清单
- [ble.md](ble.md) — BLE 设备到云端的数据传输架构
- [decisions/003-ble-connection-transport-scope.md](decisions/003-ble-connection-transport-scope.md) — 连接仅支持 BLE、Classic 待触发

## 长连接模块（tracelet-lc）

- [lc-design.md](lc-design.md) — 长连接模块设计与设计决策清单
- [lc-frame-protocol.md](lc-frame-protocol.md) — 帧协议格式定义

## 架构决策记录（ADR）

- [decisions/001-mvp-module-strategy.md](decisions/001-mvp-module-strategy.md)
- [decisions/002-deterministic-collection-boundary.md](decisions/002-deterministic-collection-boundary.md)
- [decisions/003-ble-connection-transport-scope.md](decisions/003-ble-connection-transport-scope.md)

## 文档维护规范

- 每个模块维护一份 `*-design.md`，包含：模块定位与边界、分层架构、设计决策清单、核心不变量、待定项、进度。
- 设计决策清单每条采用统一格式：状态 / 决定 / 理由 / 约束影响 / 日期。
- 状态区分「已实现 / 实现中 / 已冻结未实现 / 待定 / 已废弃」，不把计划当已实现。
- 架构级、跨模块的重大决策另建 `decisions/NNN-*.md`（ADR），模块文档引用。
- 协议等细节维护在专题文档（如 `lc-frame-protocol.md`），设计文档只引用不重复。
- 每当在讨论中确认一个新决策，追加到对应模块文档的决策清单，注明状态与日期。
