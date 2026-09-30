# tracelet-lc 长连接帧协议

本文件定义 `tracelet-lc` 长连接 SDK 的 **wire 协议（帧格式）**。这是 SDK 自身的通信契约（A 类），与上层业务无关，接入方不应修改。与业务相关的策略参数（B 类）见文末 `LcConfig`。

基于原始 TCP。TCP 是字节流，没有消息边界，因此采用**长度前缀分帧**：发送方按本格式编码为完整帧，接收方按本格式从字节流中切帧（处理粘包/半包）。

## 1. 帧结构

```text
| magic | version | type | flags | seq  | length | payload   | crc32 |
|  2B   |   1B    | 1B   |  1B   |  4B  |  4B    | length B  |  4B   |
```

- 固定头 = `magic(2) + version(1) + type(1) + flags(1) + seq(4) + length(4)` = **13 字节**
- 整帧长度 = `13 + length + 4(crc32)`
- 所有多字节字段统一 **大端序（big-endian）**

## 2. 字段定义

| 字段 | 长度 | 说明 |
|---|---|---|
| magic | 2B | 固定 `0xA55A`。帧同步与坏帧快速识别；开头不匹配即判坏帧 |
| version | 1B | 协议版本，当前 = `1` |
| type | 1B | 帧类型，见第 3 节 |
| flags | 1B | 预留标志位，当前恒为 `0`（未来扩展：压缩、是否需 ACK 等） |
| seq | 4B | 无符号序号（大端）。DATA 递增分配；DATA_ACK 填被确认 DATA 的 seq；心跳用于 ping/pong 配对 |
| length | 4B | **payload 的字节数**（大端），不含头与 crc；心跳/ACK 帧 length = 0 |
| payload | length B | 变长业务字节，长度等于 length |
| crc32 | 4B | 对 `[magic .. payload]`（除 crc 自身外全部）计算的标准 CRC32，大端存储 |

## 3. 帧类型（type）

| 名称 | 值 | 用途 |
|---|---|---|
| HANDSHAKE | 0x01 | 握手/鉴权（预留，第一版可不用） |
| HEARTBEAT | 0x02 | 心跳 ping（payload 空） |
| HEARTBEAT_ACK | 0x03 | 心跳 pong（payload 空） |
| DATA | 0x04 | 业务数据 |
| DATA_ACK | 0x05 | 业务数据确认（seq = 被确认 DATA 的 seq，payload 空） |

类型编码为**显式稳定字节值**，不依赖枚举 ordinal；新增类型只能追加新值，不得改动已有值，以保证协议兼容。收到未知 type 字节，按坏帧处理。

## 4. 编码规则（encode）

```text
magic：写 2 字节大端（取 0xA55A 低 16 位）
version：写 1 字节
type：写 type.code
flags：写 0
seq：写 4 字节大端
length：= payload.size，写 4 字节大端
payload：原样写入
crc32：对 [magic..payload] 计算，写 4 字节大端追加在末尾
```

## 5. 切帧规则（decode / tryParseOne）

按顺序判断：

```text
1. buffer 可用字节 < HEADER_SIZE(13)        → 半包，等待更多数据
2. 校验 magic；不匹配                        → 坏帧
3. 读 length；length < 0 或 > maxPayloadBytes → 坏帧（防超大分配/攻击）
4. buffer < HEADER_SIZE + length + CRC_SIZE  → 半包，等待更多数据
5. 重算 crc32 并与帧内 crc 比对；不匹配        → 坏帧
6. 全部通过 → 切出一帧，消费 (13 + length + 4) 字节
```

- 半包：保留在累积缓冲，等待下次数据后重试。
- 粘包：一次可切出多帧，循环切帧直到不足一帧。
- `length` 的上限使用 `LcConfig.maxPayloadBytes`（见第 7 节），解析出 length 后**先校验再分配内存**。

## 6. 坏帧策略（第一版）

```text
坏帧（magic 不符 / length 非法 / crc 不匹配 / 未知 type）
  → 直接断开连接并触发重连
```

理由：原始字节流一旦错位，难以可靠地跳过并重新对齐；断开重连最简单可靠。按 magic 向后逐字节重同步的进阶方案留作后续，第一版不做。

## 7. 职责分层

- **无状态编解码**：`FrameCodec` 负责 `encode` 与 `tryParseOne`（从给定缓冲尝试切一帧），不持有任何跨调用状态。
- **有状态切帧**：`FrameDecoder` 每个连接一个实例，持有累积缓冲，`feed(bytes)` 追加数据并循环调用 `FrameCodec.tryParseOne`，吐出 0~N 个完整帧，半包保留待下次。

协议常量集中在 `FrameProtocol`（internal）：

```text
MAGIC = 0xA55A
VERSION = 1
HEADER_SIZE = 13
CRC_SIZE = 4
```

帧类型集中在 `LcFrameType`（带 code + 可空 fromCode 反查）。

## 8. 策略参数 LcConfig（B 类，接入方可配）

协议格式（A 类）固定；下列策略参数依赖业务/部署，提供默认值，接入方按需覆盖：

| 参数 | 默认值 | 说明 |
|---|---|---|
| maxPayloadBytes | 1MB | 单帧 payload 上限（安全防护，切帧用它校验 length） |
| heartbeatIntervalMs | 15000 | 心跳发送间隔 |
| heartbeatTimeoutMs | 45000 | 假死判定：多久无响应视为断开 |
| connectTimeoutMs | 10000 | 连接超时 |
| backoffBaseMs | 1000 | 重连退避基数 |
| backoffMaxMs | 30000 | 重连退避上限 |
| maxReconnectAttempts | -1 | 最大重连次数，-1 表示不限 |
| sendQueueCapacity | 256 | 发送队列容量（背压） |
| ackTimeoutMs | 10000 | 等待 DATA_ACK 超时（重传用，可靠层启用时） |
| maxResendAttempts | 3 | 单条消息最大重传次数（可靠层启用时） |

SDK 仍强制单帧上限（使用 `maxPayloadBytes`）以保证自身安全，但该上限由接入方设定，SDK 不替业务猜测数据大小。单帧不承载任意大数据，超大数据的分片责任由上层承担。

## 9. 核心不变量

1. 类型编码显式稳定，不用 ordinal；已有值不可变，仅可追加。
2. `length` 表示 payload 长度；切帧前先按 `maxPayloadBytes` 校验再分配内存。
3. 半包必须等待，不得当作完整帧；一次可切出多帧（粘包）。
4. `FrameCodec` 无状态；累积缓冲只存在于 `FrameDecoder`。
5. crc 覆盖 `[magic..payload]`，校验失败按坏帧处理。
6. 坏帧第一版一律断开重连，不做字节流重同步。
