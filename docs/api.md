# 公共 API 草案

以下 API 是 Tracelet 对外接入协议，统一放在 `tracelet-sdk` 模块中，宿主只需依赖该模块。其中生命周期、实时监听、本地事件读取与导出已实现；页面上下文等能力仍为草案。

## 初始化

```kotlin
Tracelet.initialize(context) {
    freezeThresholdMs = 700
    enableStartup = true
    reportDirectory = context.filesDir
}
```

初始化要求：

- 可重复调用但只创建一个有效会话，或明确返回已初始化状态。
- 配置非法时使用安全默认值或拒绝初始化，不能影响宿主流程。
- 默认不联网。
- Android API 24+。

## 生命周期控制

```kotlin
Tracelet.start()
Tracelet.stop()
```

停止后不得继续产生新的采集事件；重复 start/stop 不应崩溃。

## 页面上下文

```kotlin
Tracelet.page("Home") {
    // 页面加载逻辑
}
```

页面标识必须是轻量、线程安全的值，不能持有 Activity、View 或 Compose 状态对象。

## Java 兼容

公共 Kotlin API 应使用 Java 友好的类型，并在必要处使用 `@JvmOverloads` 或显式 builder。实现细节不应暴露给接入方。
