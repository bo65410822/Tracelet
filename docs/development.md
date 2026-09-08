# 开发指南

## 当前状态

当前仓库已完成分层模块化：

- `tracelet-core`：事件模型、`Collector`/`EventStore` 契约、配置与上下文。
- `tracelet-performance`：采集器实现，只依赖 `core`。
- `tracelet-report`：JSON 持久化、读取与导出，只依赖 `core`。
- `tracelet-sdk`：对外 API 与默认组装，宿主只需依赖该模块。
- `app`：Compose Demo 宿主，通过 SDK 公共接口演示真实接入与卡顿检测验证。

SDK 模块不是 `app` 的一部分；`app` 不再直接依赖 `performance`/`report`。

## 环境

- Android Studio 可打开项目根目录。
- `minSdk` 为 24，`targetSdk` 为 34。
- Kotlin 版本和依赖版本以 `gradle/libs.versions.toml` 为准。

## 常用检查

在 Gradle Wrapper 可用且缓存权限正常时运行：

```text
./gradlew test
./gradlew assembleDebug
```

涉及 Android 设备或模拟器时，再运行：

```text
./gradlew connectedDebugAndroidTest
```

若命令因环境、依赖下载或设备不可用失败，必须记录原始错误；失败不等于代码有问题，成功也只证明对应命令覆盖的范围。

## Demo 验收路径

第一版应提供一个可控卡顿入口：点击后阻塞主线程超过默认 700ms。验收顺序为：

1. 启动 Demo。
2. 打开卡顿测试入口并触发阻塞。
3. 确认生成一个 `FreezeEvent`。
4. 确认事件包含时间、页面和至少一个主线程堆栈样本。
5. 重启进程后仍能读取事件。

## 开发约束

- 采集路径不得执行磁盘 I/O。
- SDK 内部异常不得传播到宿主应用。
- 新增行为必须有对应测试或可复现验收步骤。
- 与现有设计不一致时，先记录决策，再修改实现。
