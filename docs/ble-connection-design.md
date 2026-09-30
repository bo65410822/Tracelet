# BLE 连接与 GATT 操作设计文档（tracelet-ble 连接层）

本文档记录 `tracelet-ble` **连接与数据操作层**已实现的设计决策，与扫描层文档（`ble-sdk-design.md` 第 4.2 节）并列。格式与 `lc-design.md` 一致：按决策清单记录，区分"已实现 / 实现中 / 已冻结未实现 / 待定"。

连接的传输范围（仅 BLE GATT、Classic 待触发）见 `docs/decisions/003-ble-connection-transport-scope.md`，本文不重复。

## 1. 定位与边界

连接层在扫描得到 `BluetoothDeviceInfo` 之后，负责：BLE GATT 单设备连接、服务发现、原始 `ByteArray` 读/写/订阅、连接与操作生命周期治理。

- 做：Central 角色单连接、服务发现、原始数据读写与 Notify/Indicate 订阅、连接与 GATT 操作的 session/超时/资源治理。
- 不做：多设备并发连接、经典蓝牙连接（待触发）、业务协议解析/分包/ACK、把平台对象暴露给宿主。

## 2. 分层与职责

```text
BleManager（公共门面）
  ├─ BluetoothScanCoordinator（扫描，见 ble-sdk-design.md 4.2）
  └─ BluetoothConnectionCoordinator（连接生命周期）
       ├─ 连接 session / 服务发现 / GATT 资源持有与释放
       └─ GattOperationController（读/写/订阅，单在途操作）
数据模型：GattServiceInfo / GattCharacteristic（纯元数据）
```

- `BluetoothConnectionCoordinator`：连接状态机、session、连接超时、GATT 创建与关闭、状态派发。
- `GattOperationController`：读/写/订阅、单个在途操作、操作超时、结果派发、通知订阅登记。
- 平台对象（`BluetoothGatt` 等）不泄漏给宿主；宿主拿到的是 SDK 自有模型与原始 `ByteArray`。

## 3. 设计决策清单（确认项）

### C1 连接角色与范围
- 状态：已冻结（已实现）
- 决定：Central 角色、单设备单连接，基于 `connectGatt`；仅 BLE GATT。
- 理由：匹配一机一设备场景，简化 session 与资源管理。
- 约束影响：再次 connect 前先断开旧连接（不并发持有多个 GATT）；Classic 待真实需求触发（见 decisions/003）。
- 日期：2026-09-22

### C2 连接 session 隔离迟到回调
- 状态：已冻结（已实现）
- 决定：`activeSessionId: Long?` + `sessionCounter`；每次 connect 分配新 session，回调前校验 `sessionId == activeSessionId`；终态时把 `activeSessionId` 置 null 使 session 失效。
- 理由：GATT 回调可能延迟或跨连接到达，旧连接不能污染新连接状态。
- 约束影响：所有平台回调进入单串行协程上下文后先校验 session；"无活动 session"用 null 显式表达，不用 `++` 冒充。
- 日期：2026-09-22

### C3 串行状态归约
- 状态：已冻结（已实现）
- 决定：`CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))`，所有连接事件与回调串行归约。
- 理由：单一状态所有者，避免并发改连接状态。
- 约束影响：串行不等于固定线程；宿主 listener 回调不保证主线程。
- 日期：2026-09-22

### C4 status 与 newState 共同决定连接结果
- 状态：已冻结（已实现）
- 决定：`onConnectionStateChange` 先判 `status != GATT_SUCCESS → Failed`，再按 `newState` 分支。
- 理由：`newState==STATE_CONNECTED` 不代表 `status` 成功；同一状态可由成功或失败到达。
- 约束影响：避免把失败误报为 Connected；`Failed` 携带 status。
- 日期：2026-09-22

### C5 Connected 语义 = 服务就绪
- 状态：已冻结（已实现）
- 决定：`STATE_CONNECTED` 只发起 `discoverServices()`；`onServicesDiscovered` 成功后才派发 `Connected`（携带服务列表）。连接超时覆盖到服务发现完成。
- 理由：链路建立 ≠ 可通信；服务未就绪时读写会时序错误。
- 约束影响：宿主收到 Connected 即可定位 characteristic；`discoverServices()` 返回 false 需按失败处理。
- 日期：2026-09-22

### C6 资源释放顺序与 session 失效
- 状态：已冻结（已实现）
- 决定：`disconnectInternal()` 先 `close()` GATT（在置空引用之前），再于 finally 重置状态；同时 `activeSessionId = null` 使 session 失效、取消连接超时。
- 理由：置空引用不等于释放平台资源；`close()` 必须真正执行；终态后旧回调不得再改状态。
- 约束影响：连接失败/超时/断开/切换设备统一走该清理；`close()` 异常被隔离。
- 日期：2026-09-22

### C7 终态派发顺序：先派发再失效
- 状态：已冻结（已实现）
- 决定：终态通过 `close(sessionId, listener, state)` 辅助方法：先 `dispatchState`（同步、校验 session）再 `disconnectInternal()` 失效。`dispatchState` 不再嵌套 launch。
- 理由：若先失效 session，派发时 `checkSessionId` 会把终态自己拦掉。
- 约束影响：Timeout/Failed/Disconnected 都先派发后失效；派发与校验在同一串行执行流，无重排队窗口。
- 日期：2026-09-22

### C8 连接前错误无条件派发
- 状态：已冻结（已实现）
- 决定：`BluetoothNotSupported / BluetoothDisabled / PermissionDenied / AlreadyConnected` 用 `dispatchStateNoSession` 直接派发（带异常隔离），不经 session 校验。
- 理由：这些发生在还没有 session 时，套 session 校验会被吞掉。
- 约束影响：所有对宿主的回调均 try/catch 隔离宿主异常。
- 日期：2026-09-22

### C9 连接入口边界处理
- 状态：已冻结（已实现）
- 决定：`getDefaultAdapter()==null → BluetoothNotSupported`；`getRemoteDevice()`/`connectGatt()` 的异常与 null 返回归一到 `Failed`；仅在拿到有效 GATT 后才启动连接超时。
- 理由：连接是独立入口，输入与平台状态不可信；每次 connect 必须有明确终态。
- 约束影响：避免 NPE、避免"调了连接无回音"。
- 日期：2026-09-22

### C10 连接权限与扫描分离
- 状态：已冻结（已实现）
- 决定：`hasConnectPermission()` 独立于扫描；Android 12+ 仅校验 `BLUETOOTH_CONNECT`，旧版本不以定位作为连接前置。
- 理由：连接不需要 `BLUETOOTH_SCAN` 或定位权限。
- 约束影响：已具备连接权限的宿主不被误拒；Manifest 增 `BLUETOOTH_CONNECT`。
- 日期：2026-09-22

### C11 GATT 操作单在途 + 控制器拆分
- 状态：已冻结（已实现）
- 决定：新增 `GattOperationController`，读/写/订阅**共享一个 pendingOperation**；有在途操作时其他请求返回 `Busy`；每个操作有超时；回调校验 session 且匹配当前 characteristic；断开时结束 pending 操作、清订阅。
- 理由：BLE 同一连接同一时刻只能有一个在途 GATT 操作；且 Coordinator 职责过重需拆分。
- 约束影响：读/写/订阅互斥；`Coordinator` 持有并释放 GATT，`Controller` 只借用。
- 日期：2026-09-22

### C12 数据交付与兼容
- 状态：已冻结（已实现）
- 决定：所有交付宿主的 `ByteArray` 复制（`copyOf`）；写入兼容 Android 13 前后 API；写类型按 characteristic 属性选择；订阅走 CCCD + `onDescriptorWrite` 确认，数据经 `onCharacteristicChanged` 交付。
- 理由：防平台缓冲/宿主共享可变数据；兼容 API 版本差异。
- 约束影响：单帧写上限默认 20 字节（未做 MTU 协商/分包）。
- 日期：2026-09-22

### C13 GattCharacteristic 为纯数据模型
- 状态：已冻结（已实现）
- 决定：`GattCharacteristic` 是公开只读的 `serviceUuid/uuid/properties` 数据类，不带 read/write/subscribe 操作方法；操作由 `BleManager`→`Coordinator`→`Controller` 提供。
- 理由：元数据对象不应持有会话句柄；避免断开重连后旧对象误操作。
- 约束影响：宿主按 UUID 定位后经门面发起操作。
- 日期：2026-09-22

### C14 主动 disconnect 不回调
- 状态：已冻结（已实现，设计确认）
- 决定：宿主主动 `disconnect()` 只做资源清理，不派发 `Disconnected`。
- 理由：宿主自己发起、自己知晓；`close()` 后系统也不再回调。
- 约束影响：宿主主动断开后自行更新 UI 状态；建议在 API 注释注明。
- 日期：2026-09-22

## 4. 核心不变量

1. 同一时刻最多一个活动连接；只有当前 session 的回调能改状态。
2. 每次连接、每次操作最多一个终态；终态后旧回调被 session 失效挡住。
3. `BluetoothGatt` 在每条退出路径都会被 `close()`，清理可重复调用。
4. 同一连接同一时刻只有一个在途 GATT 操作。
5. `status==GATT_SUCCESS && 服务发现成功` 才算 Connected。
6. 交付宿主的 `ByteArray` 均为复制；宿主 listener 异常被隔离。
7. 连接权限独立于扫描权限。

## 5. 待定 / 未决项

- 服务发现失败与 `discoverServices()` 返回 false 的兜底细化。
- 连接前 4 处直接派发的异常隔离已用 `dispatchStateNoSession` 统一（已做）。
- 组件级 `release()`（关闭 GATT 后 cancel scope）：单例模型下暂缓。
- GATT 实例校验（回调再校验 `gatt === currConnectGatt`）：作为冗余保险，重连/并发场景再加。
- 数据发送的 MTU 协商、分包/组包、业务 ACK、去重、重试：属可靠传输层，待真实设备协议。
- 单元测试统一补（session、超时、单终态、单在途、断开清理等不变量）。

## 6. 进度

```text
扫描：BLE/Classic/BOTH                         已完成（见 ble-sdk-design.md 4.2）
连接：连接 + 服务发现 + Connected(服务就绪)      已完成
GATT 操作：read/write/subscribe（单在途）        已完成
BleManager 门面：scan + connect/disconnect + 读写订阅  已完成
服务发现后 discoverServices 返回值处理           待完善
可靠传输（分包/ACK/重试）、多设备、Classic         待触发/后续
```

## 7. 文档维护约定

- 新确认的连接层决策追加到第 3 节，注明状态与日期。
- 传输范围类决策记录在 `docs/decisions/`（如 003）。
- 与扫描层文档（`ble-sdk-design.md`）并列，不重复扫描细节。
