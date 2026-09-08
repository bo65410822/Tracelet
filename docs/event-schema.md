# 事件协议

## 目标

所有诊断数据都使用版本化、不可变事件。采集器创建事件，存储层保存事件，报告层读取事件；三者不互相承担职责。

## 公共字段

| 字段 | 类型 | 要求 |
|---|---|---|
| `schemaVersion` | Int | 当前事件协议版本，初始为 1 |
| `id` | String | 事件唯一标识 |
| `type` | String | 例如 `freeze`、`startup`、`page` |
| `timestampMs` | Long | 事件发生时间 |
| `sessionId` | String | 当前进程会话标识 |
| `page` | String? | 当前页面，可为空 |
| `attributes` | Object | 受控大小的扩展字段 |
| `samples` | Array | 可选堆栈或采样数据 |

## FreezeEvent

```json
{
  "schemaVersion": 1,
  "id": "01JFREEZE001",
  "type": "freeze",
  "timestampMs": 1720000000000,
  "sessionId": "session-001",
  "page": "Home",
  "attributes": {
    "durationMs": 850,
    "thresholdMs": 700
  },
  "samples": [
    "com.example.HomeActivity.loadData(HomeActivity.kt:42)"
  ]
}
```

## 兼容规则

- 新增字段必须可选，读取旧事件不能失败。
- 不删除或改变已有字段语义；破坏性变化提升 `schemaVersion`。
- `attributes`、`samples`、单个事件和事件目录都必须有大小上限。
- 无法解析的文件只影响该文件，并记录内部诊断信息。
