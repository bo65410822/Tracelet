# Tracelet 架构设计

## 总体结构

Tracelet 采用分层、事件驱动的本地架构。诊断能力按 `core → report/performance → tracelet-sdk` 组织；蓝牙垂直业务能力由独立的 `tracelet-ble` 模块承载。宿主按使用的能力选择依赖模块，不直接接触具体采集器、存储实现或蓝牙平台状态机。

```text
App 宿主（按能力依赖 tracelet-sdk 或 tracelet-ble）
        |
        +--------------------+
        v                    v
tracelet-sdk          tracelet-ble
（诊断 API + 组装）    （蓝牙连接/协议）
   /      |      \
  v       v       v
core  performance report
（事件/接口）（采集器）（存储/报告）
```

## 模块职责

### `tracelet-core`

提供事件模型、`Collector`/`EventStore` 接口、初始化、配置、生命周期、页面上下文和线程安全的事件分发。该模块不得依赖 Compose，也不得依赖具体采集器实现（`performance`）或存储实现（`report`）。

### `tracelet-performance`

实现主线程卡顿、启动和其他具有确定系统边界的性能采集器。它只依赖 `tracelet-core`，不能直接操作业务页面或网络服务。页面标识属于可自动获取的上下文；依赖用户操作、路由发起、网络完成或内容就绪语义的完整页面耗时，不属于自动采集范围。

### `tracelet-report`

负责事件序列化、本地文件存储、查询、导出和过期清理。它只依赖 `tracelet-core`，存储实现通过 `EventStore` 接口注入，便于测试和未来替换。

### `tracelet-sdk`

对外唯一入口模块。公开 `Tracelet.initialize/start/stop` 等公共 API，并在内部完成默认采集器（`performance`）与默认存储（`report`）的组装。`tracelet-core` 作为公共类型以 `api` 暴露，`performance` 和 `report` 只作为内部实现引入。宿主只需依赖这一个模块。

### `tracelet-ble`

独立的统一蓝牙设备业务 SDK。它对业务层提供统一的设备发现、身份确认、连接、双向通信和恢复能力，底层通过传输适配器支持 BLE GATT、Classic RFCOMM/SPP 以及音频 Profile 状态观察。上层统一设备语义，下层保留不同协议的真实差异；A2DP/HFP 不被当作任意业务数据通道。它可以依赖 Android 蓝牙 API 和通用基础库，但不得让 `tracelet-core`、`tracelet-performance` 或 `tracelet-report` 依赖蓝牙实现。蓝牙业务事件、协议和状态模型以本模块为边界；需要统一诊断时通过明确接口接入，不直接耦合性能采集链路。

### `tracelet-compose`

提供 Compose 页面和首帧相关的可选适配，暂不建立；它不应成为核心模块的必选依赖。

### `app`

当前仓库中的示例宿主应用，用于演示接入、制造可控卡顿和展示报告。它不是生产 SDK 模块，只依赖 `tracelet-sdk`。

## 核心数据模型

所有诊断信息统一为不可变 `TraceletEvent`：

- `id`：事件唯一标识。
- `type`：事件类型，例如 `freeze`、`startup`、`page`。
- `timestampMs`：事件发生时间。
- `sessionId`：应用进程会话标识。
- `page`：当前页面上下文，可为空。
- `attributes`：受控大小的扩展字段。
- `samples`：可选的堆栈或采样数据。

具体事件（如 `FreezeEvent`）只增加自己的字段，不改变公共字段语义。事件 schema 必须带版本号，后续读取器需要兼容旧版本。

## 运行时流程

1. 宿主应用调用 `tracelet-sdk` 公开的 `Tracelet.initialize`，由它校验配置、注册默认采集器并创建会话。
2. 性能采集器注册到 Android 生命周期、`Looper` 或 `Choreographer`。
3. 采集器发现异常或完成一次计时后，创建不可变事件并提交给 `EventStore`。
4. 存储层在后台批量写入应用私有目录，采集线程不执行磁盘 I/O。
5. 报告读取器按需解析事件，导出 JSON；任何解析或写入失败都被隔离并记录为内部诊断日志。

## 关键约束

- 采集器不能阻塞主线程。
- SDK 内部异常必须被捕获，不能传播到业务代码。
- 默认关闭高成本能力；所有采样、阈值和存储上限可配置。
- 页面上下文使用线程安全的当前值或不可变快照，避免泄漏 Activity。
- 公共 API 不暴露 Android 内部实现细节，便于后续替换采集策略。
- 测试必须能注入时钟、堆栈采样器和存储，避免依赖真实等待。

## 采集器准入规则

新增自动采集能力必须同时满足以下条件：

1. 起点、终点和状态信号来自明确的 Android 系统回调或 SDK 自身可控边界。
2. 指标在不同宿主架构下保持同一语义，并能准确说明包含和不包含的阶段。
3. 不依赖对用户操作、路由发起、网络完成、业务内容就绪等宿主语义的猜测。
4. 不使用 UI 静默、固定延迟或重绘次数等启发式结果冒充确定指标；启发式诊断若未来引入，必须单独标记置信度和适用范围。
5. 能通过可注入依赖、单元测试或可复现实验验证。

不满足这些条件的能力不得作为零侵入自动指标进入默认采集器。若它仍有诊断价值，应设计成宿主显式标记的可选能力，并与自动指标使用不同名称和事件字段。

## 首批技术决策

- 卡顿检测优先采用 `Looper` 消息监控，堆栈采样作为卡顿发生期间的补充。
- 事件先写本地 JSON，不在 SDK 内绑定数据库或网络库。
- 核心 API 使用 Kotlin 定义，并通过 `@JvmOverloads` 或 Java 友好类型提供 Java 接入。
- 已完成 `core → report/performance → tracelet-sdk → app` 的诊断模块拆分；`tracelet-sdk` 是诊断能力的对外入口。
- `tracelet-ble` 已建立为独立垂直业务模块，蓝牙能力不要求宿主依赖诊断 SDK，也不改变诊断模块的依赖方向。
- `compose` 模块等出现实际 Compose 适配 API 后再建立。

## 可演进方向

后续可以在不改变核心事件契约的前提下增加内存、网络、帧率和 Native 采集器；蓝牙垂直能力继续在 `tracelet-ble` 内演进。Gradle Plugin 或字节码插桩只能作为可选模块引入，不能成为基础接入的前置条件。
