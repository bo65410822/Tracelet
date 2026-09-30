# 长连接模块（tracelet-lc）设计文档

本文档汇总长连接模块的设计定位、分层架构、已冻结的设计决策、核心不变量、待定项与分阶段进度。所有"设计确认项"以此文档为准；对话中新确认的决策应追加到本文，不再只留在讨论里。

协议帧格式的完整定义见 `docs/lc-frame-protocol.md`，本文引用不重复。

## 1. 模块定位与边界

在原始 TCP 之上，自研一个通用长连接内核。目标是通过亲手实现连接管理、心跳、分帧编解码、断链补发、退避重连、网络层处理来沉淀工程能力，不追求业务功能广度，也不替代成熟 SDK。

- 做：分帧与编解码、粘包/半包处理、连接生命周期、心跳与假死检测、断线重连（退避+抖动）、可靠投递（后续）、发送队列与背压（后续）。
- 不做：绑定具体业务协议；替上层假设业务数据大小；把加密塞进帧协议（安全由 TLS 层负责）。

无可控真实服务端，使用本地回环 mock 服务端驱动与测试。

## 2. 分层架构

```text
应用/上层
  ↓
ConnectionCoordinator（状态机 + generation，待实现）
  ├─ HeartbeatController（心跳/假死，待实现）
  ├─ ReconnectController（退避+抖动，待实现）
  ├─ ReliableMessaging（seq/ACK/去重/补发，待实现）
  └─ SendQueue（有界队列/背压，待实现）
  ↓
TcpTransport（一条 socket 的连接/读线程/写/关闭，实现中）
  ↓
FrameEncoder（编码一帧） / FrameDecoder（解码+切帧）
  ↓
FrameProtocol（常量 + crc32） / LcFrameType（帧类型） / ParsedFrame / ParseResult
```

包结构：

- `com.lzb.lc.frame`：`FrameProtocol`、`LcFrameType`、`FrameEncoder`、`FrameDecoder`、`ParsedFrame`、`ParseResult`
- `com.lzb.lc`：`LcConfig`、`TcpTransport`、`TransportListener`

## 3. 设计决策清单（确认项）

格式：状态 / 决定 / 理由 / 约束影响 / 日期。

### D1 协议分层按"方向"拆分（A 轴）
- 状态：已冻结（已实现）
- 决定：`FrameEncoder` 只负责编码，`FrameDecoder` 负责全部解码（解一帧 + 缓冲切帧）。
- 理由：概念直白（一编一解），便于理解与维护。
- 约束影响：`tryParseOne` 作为 `FrameDecoder` 的私有方法；内部仍把"缓冲管理"和"解一帧"分成两个方法，保持可测。
- 日期：2026-09-22

### D2 帧协议冻结
- 状态：已冻结（已实现）
- 决定：帧 = `magic(2,0xA55A) | version(1,=1) | type(1) | flags(1) | seq(4) | length(4) | payload | crc32(4)`，统一大端；固定头 13 字节；`length` = payload 长度。类型 0x01 HANDSHAKE / 0x02 HEARTBEAT / 0x03 HEARTBEAT_ACK / 0x04 DATA / 0x05 DATA_ACK。
- 理由：长度前缀分帧解决 TCP 无边界；类型显式字节值保证兼容。
- 约束影响：类型编码不得用 ordinal，只能追加不能改；详见 `lc-frame-protocol.md`。
- 日期：2026-09-22

### D3 粘包/半包处理
- 状态：已冻结（已实现）
- 决定：`FrameDecoder` 持有累积缓冲 + `writePos`；`feed` 追加数据 → 循环 `tryParseOne` 切帧 → 半帧 `compact` 搬到缓冲开头；`offset` 为 feed 内局部变量，维护"offset 恒为帧头"不变量。
- 理由：TCP 是字节流不记边界，边界维护是应用层职责。
- 约束影响：只按整帧长度前移、半包保留、坏帧上报；扩容 `ensureCapacity` 翻倍并将结果赋回缓冲。
- 日期：2026-09-22

### D4 坏帧策略
- 状态：已冻结（已实现）
- 决定：坏帧（magic/length 超限/crc 不符/未知 type）一律断开重连，不做字节流重同步。
- 理由：magic 在字节流中不唯一，扫描重同步不可靠；断开重连后新流从 offset=0 重新对齐。
- 约束影响：`FrameDecoder.feed` 返回 `DecodeOutcome.Corrupt`，上层断开并 `reset()`。
- 日期：2026-09-22

### D5 CRC 用标准库
- 状态：已冻结（已实现）
- 决定：用 `java.util.zip.CRC32`（标准反射版 CRC-32），不手写。
- 理由：手写易错且与对端标准实现不互通；校验和有标准定义应直接用平台实现。
- 约束影响：crc 覆盖 `[magic..payload]`，比较时读回值 `and 0xFFFFFFFFL` 做无符号对齐。
- 日期：2026-09-22

### D6 配置收敛到 LcConfig
- 状态：已冻结（部分实现）
- 决定：策略参数（上限、心跳、超时、退避、队列容量等）收进 `LcConfig`，给默认值可覆盖；SDK 不替业务猜数据大小。
- 理由：协议是 SDK 契约（固定），策略依赖业务（可配）。
- 约束影响：`maxPayloadBytes` 既是背压上限也用于切帧校验 `payloadLen`。当前已实现：`maxPayloadBytes/heartbeatIntervalMs/heartbeatTimeoutMs/connectTimeoutMs/backoffBaseMs/backoffMaxMs/maxReconnectAttempts/sendQueueCapacity`；`ackTimeoutMs/maxResendAttempts` 待可靠层实现时再加。
- 日期：2026-09-22

### D7 TcpTransport 约定
- 状态：已冻结（实现中，当前实现待修正）
- 决定：
  1. 一次性实例：一个 `TcpTransport` = 一次连接，重连由上层新建。
  2. `send` 用同步阻塞写（`synchronized` + flush），发送队列/背压留给上层。
  3. socket 可注入（`SocketFactory`），默认普通 Socket，为 TLS 与测试留口。
  4. 不设 SO_TIMEOUT，假死交给上层心跳判定。
  5. 用本地回环 mock `ServerSocket` 驱动与测试。
- 理由：职责单一（只管一条 socket 的字节收发），连接治理归上层。
- 约束影响：需持有单个 socket（connect 建一次，send/close 复用）；connect 用 `connectTimeoutMs`；启动读线程 `read → FrameDecoder.feed → onFrames`；`onDisconnected` 用 AtomicBoolean 保证只回调一次；`tcpNoDelay=true`；listener 异常隔离。
- 日期：2026-09-22

### D8 传输安全（TLS）后补
- 状态：待定 → 计划后补
- 决定：TLS 作为独立传输层，套在 TCP 之上，不进帧协议；实现时把普通 socket 换成 SSLSocket。
- 理由：先保证协议正确性，安全单独一层，不返工帧格式。
- 约束影响：`TcpTransport` 的 socket 通过可注入 factory 创建，便于替换为 TLS socket。
- 日期：2026-09-22

## 4. 核心不变量

1. 类型编码显式稳定，不用 ordinal；已有值不可变，仅可追加。
2. `length` 表示 payload 长度；切帧前先按 `maxPayloadBytes` 校验再分配内存。
3. 半包必须等待，不得当完整帧；一次 feed 可切出多帧（粘包）。
4. `offset` 恒为帧头：只按整帧长度前移，半包保留（compact 到开头）。
5. crc 覆盖 `[magic..payload]`，无符号比较；校验失败按坏帧处理。
6. 坏帧断开重连，不做重同步；重连后 `reset()`，新流从头对齐。
7. `TcpTransport` 一次性实例，持有单 socket；`onDisconnected` 只触发一次。
8. 阻塞 read 的取消只能靠 close socket。
9. `write` 成功 = 交给 OS 发送缓冲，≠ 对端收到（对端确认属可靠层）。
10. 心跳正常 = 链路活着，≠ 业务已处理。

## 5. 待定 / 未决项

- `send` 出错统一走 `onDisconnected`（单一终态入口），`send` 本身不抛异常。待实现确认。
- 可靠层（seq/ACK/去重/补发）与 `ackTimeoutMs/maxResendAttempts` 配置，第 7 阶段再定。
- 单元测试统一在协议层与传输层成型后集中补。
- TLS 具体接入方式（SSLSocket vs SSLEngine）待安全阶段评估。

## 6. 分阶段计划与进度

```text
1. FrameEncoder + FrameDecoder（分帧/粘包半包）        已完成
2. TcpTransport（阻塞 socket + 读线程 + 写 + 关闭）    实现中（待修正）
3. 本地 mock ServerSocket（驱动/测试）                 待做
4. ConnectionCoordinator（状态机 + generation）        待做
5. HeartbeatController（心跳/假死）                    待做
6. ReconnectController（退避 + 抖动）                  待做
7. ReliableMessaging（seq/ACK/去重/补发）              待做
8. SendQueue（有界队列/背压）                          待做
9. TLS（传输安全，独立层）                             待做
10. 统一补测试                                         待做
```

## 7. 文档维护约定

- 每确认一个新决策，追加到第 3 节"设计决策清单"，注明状态与日期。
- 区分"已实现 / 实现中 / 已冻结未实现 / 待定"，不把计划当已实现。
- 架构级、跨模块的重大决策另建 `docs/decisions/NNN-*.md`（ADR），本文引用。
- 协议细节维护在 `docs/lc-frame-protocol.md`，本文只引用不重复。
