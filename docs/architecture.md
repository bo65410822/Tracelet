# Tracelet 架构设计

## 总体结构

Tracelet 采用分层、事件驱动的本地架构。模块按 `core → report/performance → tracelet-sdk` 组织，宿主只依赖对外的 `tracelet-sdk` 模块，不直接接触具体采集器或存储实现。

```text
App 宿主（只依赖 tracelet-sdk）
        |
        v
  tracelet-sdk（对外 API + 默认组装）
     /       |        \
    v        v         v
tracelet-core   tracelet-performance   tracelet-report
（事件模型/接口）  （采集器实现）          （存储/查询/导出）
```

## 模块职责

### `tracelet-core`

提供事件模型、`Collector`/`EventStore` 接口、初始化、配置、生命周期、页面上下文和线程安全的事件分发。该模块不得依赖 Compose，也不得依赖具体采集器实现（`performance`）或存储实现（`report`）。

### `tracelet-performance`

实现主线程卡顿、启动和页面耗时等采集器。它只依赖 `tracelet-core`，不能直接操作业务页面或网络服务。

### `tracelet-report`

负责事件序列化、本地文件存储、查询、导出和过期清理。它只依赖 `tracelet-core`，存储实现通过 `EventStore` 接口注入，便于测试和未来替换。

### `tracelet-sdk`

对外唯一入口模块。公开 `Tracelet.initialize/start/stop` 等公共 API，并在内部完成默认采集器（`performance`）与默认存储（`report`）的组装。`tracelet-core` 作为公共类型以 `api` 暴露，`performance` 和 `report` 只作为内部实现引入。宿主只需依赖这一个模块。

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

## 首批技术决策

- 卡顿检测优先采用 `Looper` 消息监控，堆栈采样作为卡顿发生期间的补充。
- 事件先写本地 JSON，不在 SDK 内绑定数据库或网络库。
- 核心 API 使用 Kotlin 定义，并通过 `@JvmOverloads` 或 Java 友好类型提供 Java 接入。
- 已完成 `core → report/performance → tracelet-sdk → app` 的模块拆分；`tracelet-sdk` 是对外唯一入口，宿主只依赖它。`compose` 模块等出现实际 Compose 适配 API 后再建立。

## 可演进方向

后续可以在不改变核心事件契约的前提下增加内存、网络、帧率和 Native 采集器；Gradle Plugin 或字节码插桩只能作为可选模块引入，不能成为基础接入的前置条件。
