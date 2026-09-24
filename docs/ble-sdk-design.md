# Tracelet 统一蓝牙设备 SDK 设计方案

## 1. 定位

`tracelet-ble` 是 Tracelet 工程中的独立蓝牙垂直业务 SDK。它面向统一的“蓝牙设备”模型，为业务层提供统一的设备发现、身份确认、连接、双向通信和恢复能力。

业务层不需要关心目标设备使用 BLE GATT、Classic RFCOMM/SPP，还是同时提供 Classic 音频和 BLE 控制通道。SDK 在底层根据设备能力选择对应的传输适配器；底层协议差异不得泄漏为业务层必须处理的平台回调。

它不只是对 Android Bluetooth API 的简单转发，而是为上层业务封装一套可恢复、可验证、可维护的设备连接和数据传输能力。

目标是在设备密集、扫描结果不稳定、射频干扰、连接中断和数据传输失败等环境中，尽可能保证：

- 连接的是正确设备；
- 连接状态可观察、可恢复；
- 数据有边界、有顺序、有确认；
- 失败原因可区分；
- SDK 内部异步时序不会暴露成业务层回调地狱。

不能承诺任何公共场所都没有射频干扰，也不能承诺蓝牙链路永不丢包。SDK 的目标是识别、恢复和暴露这些问题，而不是掩盖无线环境的不确定性。

## 2. 问题边界

公共场所中的主要问题不是其他手机可以随意“拦截”已经建立的蓝牙连接，而是：

- 2.4GHz 频段设备密集造成射频干扰；
- 多个同型号设备同时出现在扫描结果中；
- 设备允许多个中心设备竞争连接；
- Android 厂商蓝牙栈在扫描、GATT、MTU 和后台行为上存在差异；
- 连接回调可能延迟、重复或乱序；
- 底层写入成功不等于设备业务已经处理成功。

因此设计重点是设备身份、连接状态机和应用层可靠传输，而不是试图消除所有物理层问题。

## 3. 统一设备模型与模块边界

### 3.1 分层模型

```text
业务层
   ↓ 统一设备 API
统一设备会话 DeviceSession
   ↓ 传输适配器
+----------------------+-------------------------+
|                      |                         |
v                      v                         v
BLE GATT          Classic RFCOMM/SPP      Audio Profile 观察
```

上层统一设备语义，下层保留传输差异。统一不等于把不同蓝牙协议强行实现成同一种底层机制。

### 3.2 能力模型

每个设备通过能力集合描述实际支持的通道，例如：

```text
BLE_GATT
CLASSIC_RFCOMM
AUDIO_A2DP
AUDIO_HFP
```

设备可以是单模设备，也可以是双模设备：

```text
设备 A：BLE GATT + A2DP
设备 B：Classic RFCOMM
设备 C：只有 A2DP/HFP
```

只有 A2DP/HFP 的设备可以被识别和观察音频状态，但不能因此获得任意业务数据收发能力。业务数据能力必须由设备实际开放的 GATT 或 RFCOMM/SPP 通道提供。

### 3.3 双模设备

同一个物理设备可能对应多条底层通道：

```text
UnifiedDeviceSession
├── AudioTransport / AudioProfileObserver
└── DataTransport
    ├── BleGattTransport
    └── ClassicRfcommTransport
```

业务层只操作一个统一设备会话；SDK 内部维护音频状态和数据通道状态。音频连接不自动等价于业务数据连接。

### 3.4 依赖方向

```text
业务宿主
   ↓
tracelet-ble 统一设备 API
   ↓
Android Bluetooth API / 通用基础能力
```

约束：

- `tracelet-ble` 不依赖 `tracelet-performance`、`tracelet-report` 的实现细节。
- `tracelet-core` 不依赖蓝牙。
- 蓝牙采集、协议处理和连接状态机不写入性能模块的事件存储。
- 如果未来需要把蓝牙异常接入统一诊断，使用独立适配接口，不让蓝牙核心依赖报告实现。
- API 设计保持 Kotlin 优先，同时保证 Java 可接入。
- Android 最低版本沿用工程约束 API 24+；具体蓝牙权限和系统限制按 Android 版本单独处理。

## 4. 对外能力分层

### 4.1 设备发现

设备发现由底层适配器实现，但对业务层输出统一的 `BluetoothDeviceInfo`。发现方式按设备能力选择：

- BLE GATT：使用 Service UUID、Manufacturer Data 或厂商标识筛选；
- Classic RFCOMM/SPP：使用已配对设备、设备能力和协议标识筛选；
- Audio Profile：只能观察系统已知的音频设备状态，不能把音频设备自动当成业务数据设备。

通用约束：

- 不把设备名称作为唯一身份依据；
- 不把 MAC 地址单独作为业务身份或安全凭证；
- 限制扫描时长和扫描频率；
- 连接成功或候选设备确认后停止无关扫描；
- 扫描取消、超时、权限不足和蓝牙关闭必须有明确结果。

### 4.2 当前扫描能力实现说明

本节记录当前代码中已经实现的扫描能力。它描述实际行为，不代表后续连接、身份认证、可靠传输等规划已经完成。

#### 4.2.1 当前实现范围

当前扫描模块提供：

- BLE、经典蓝牙和两者同时扫描三种模式；
- 扫描前的蓝牙支持、蓝牙开关和权限检查；
- 单个逻辑扫描会话、重复启动拦截和五分钟超时；
- BLE 与经典蓝牙平台结果到统一 `BluetoothDeviceInfo` 的转换；
- Driver 失败、自然结束和晚到回调的会话过滤；
- 手动停止、超时停止和平台资源清理。

当前不提供：

- 设备过滤和扫描参数配置；
- 扫描结果去重或 BLE/Classic 双模设备合并；
- 设备身份认证；
- BLE GATT、Classic RFCOMM/SPP 连接和数据传输；
- 可配置的回调线程或扫描超时时长；
- Coordinator 的永久 `close/release` 生命周期接口。

#### 4.2.2 分层与调用关系

```text
宿主应用
   ↓
BleManager
   ↓
BluetoothScanCoordinator
   ↓ ScanDriver / DriverListener
+----------------------+----------------------+
|                                             |
v                                             v
BleScannerImpl                         ClassicScannerImpl
   ↓                                             ↓
BluetoothLeScanner               BluetoothAdapter + BroadcastReceiver
```

边界约定：

- `BleManager` 提供宿主入口并复用 Coordinator；
- `BluetoothScanCoordinator` 管理逻辑会话、扫描模式、前置条件、超时和公共状态；
- `ScanDriver` 定义平台扫描驱动的最小接口；
- `BleScannerImpl` 和 `ClassicScannerImpl` 只管理各自的平台资源和原始事件；
- Driver 不决定公共 `Scanning`、`Stopped` 或 `Timeout`；
- Coordinator 不直接持有 `ScanCallback` 或 `BroadcastReceiver`。

#### 4.2.3 `BleManager`

文件：`tracelet-ble/src/main/java/com/lzb/ble/BleManager.kt`

功能：

- 提供扫描的静态入口；
- 首次扫描时使用 `applicationContext` 创建 `BluetoothScanCoordinator`；
- 在进程内复用同一个 Coordinator；
- 将 `startScan`、`stopScan` 转交给 Coordinator。

当前入口：

```kotlin
fun startScan(
    context: Context,
    mode: ScanMode = ScanMode.BLE_ONLY,
    listener: ScanListener
)

fun stopScan()
```

范围限制：

- 不申请运行时权限；
- 不主动打开系统蓝牙；
- 不处理扫描状态和 Driver 事件；
- 当前没有释放 Coordinator 的 `close()` 接口。

#### 4.2.4 `BluetoothScanCoordinator`

文件：`tracelet-ble/src/main/java/com/lzb/ble/scan/BluetoothScanCoordinator.kt`

Coordinator 是扫描层的逻辑会话所有者，负责：

- 拦截重复 `startScan`；
- 检查蓝牙硬件、蓝牙开关和扫描权限；
- 根据 `ScanMode` 创建本次需要的 Driver；
- 为每次扫描递增 `currentSessionId`；
- 记录成功启动且仍活动的 `activeTransports`；
- 将 Driver 回调投递到单并发协程队列；
- 过滤旧会话和已停止会话的晚到事件；
- 启动固定五分钟的 timeout；
- 将 Driver 事件转换成公共 `ScanState`；
- 手动停止或超时时逐个尝试停止本次选择的 Driver；
- 清理 listener、timeout 和活动传输状态。

Coordinator 使用：

```kotlin
CoroutineScope(
    SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
)
```

该执行器保证 Coordinator 提交的启动、停止、超时和 Driver 事件按单并发顺序执行，但不保证使用固定线程。宿主的 `ScanListener` 也从该执行上下文调用，不保证位于主线程。

启动流程：

```text
startScan(mode, listener)
   ↓
检查当前是否已扫描
   ↓
检查 BluetoothAdapter、蓝牙开关、权限
   ↓
按 mode 选择 Driver
   ↓
逐个调用 Driver.start()
   ↓
记录返回 true 的 transport
   ↓
至少一个成功 ──→ Scanning + 五分钟 timeout
全部失败     ──→ Failed(ALL) + 清理会话
```

运行事件流程：

```text
Android 平台事件
   ↓
具体 Driver
   ↓ DriverListener
Coordinator 的单并发队列
   ↓ sessionId / isScanning 校验
ScanListener
```

停止流程：

```text
手动 stopScan       → 尝试停止 Driver → Stopped
五分钟 timeout      → 尝试停止 Driver → Timeout
最后一个活动传输退出 → 尝试停止 Driver → 对应终态
```

每个 Driver 的 `stop()` 使用独立 `try/catch`，一个 Driver 停止失败不会阻止另一个 Driver 获得清理机会。Coordinator 的终态表示 SDK 逻辑会话已经结束，不表示 Android 平台提供了“停止完成确认”；BLE 平台没有对应的确认回调。

范围限制：

- 当前没有设备去重、筛选和双模设备合并；
- `activeTransports` 表示仍参与当前逻辑会话的传输类型，不代表系统蓝牙栈提供了可查询的精确运行状态；
- 当前单个 Driver 失败或完成时会生成 `DriverFailed(transport)`；其是否需要继续暴露给宿主仍可根据真实业务调整；
- 当前 Classic 的正常 `ACTION_DISCOVERY_FINISHED` 与异常失败使用相同的 Coordinator 处理入口，尚未提供独立的公共 `Completed` 状态。

#### 4.2.5 `ScanMode`

文件：`tracelet-ble/src/main/java/com/lzb/ble/scan/BluetoothScanner.kt`

```kotlin
enum class ScanMode {
    BLE_ONLY,
    CLASSIC_ONLY,
    BOTH
}
```

模式语义：

- `BLE_ONLY`：只创建并启动 BLE Driver；
- `CLASSIC_ONLY`：只创建并启动 Classic Driver；
- `BOTH`：依次尝试启动 BLE 和 Classic Driver；只要至少一个 Driver 返回启动成功，整个逻辑会话就进入 `Scanning`；
- `BOTH` 中一个 Driver 失败或自然结束后，另一个仍活动时继续扫描；全部活动传输都退出后才结束整个会话。

`Driver.start() == true` 只表示平台启动调用已同步接受或正常返回，不表示已经发现设备，也不保证后续不会通过异步回调失败。

#### 4.2.6 `ScanListener` 与 `ScanState`

文件：`tracelet-ble/src/main/java/com/lzb/ble/scan/BluetoothScanner.kt`

`ScanListener` 是公共状态出口：

```kotlin
interface ScanListener {
    fun onStateChanged(state: ScanState)
}
```

当前状态语义：

- `Scanning`：至少一个本次选择的 Driver 已同步启动成功；
- `AlreadyScanning`：已有逻辑会话时再次请求启动；
- `DeviceFound`：某个 Driver 发现了一个设备；
- `BluetoothDisabled`：启动前发现蓝牙未打开；
- `BluetoothNotSupported`：设备不存在可用的 `BluetoothAdapter`；
- `PermissionDenied`：启动前权限不足；
- `Timeout`：逻辑扫描会话达到固定时间上限；
- `Stopped`：宿主主动停止逻辑扫描会话；
- `DriverFailed`：某一种传输方式失败或结束；
- `Failed`：选中的 Driver 均未成功启动；
- `Unknown`：Coordinator 捕获到未映射的启动异常。

`safeDispatch` 会捕获宿主 listener 抛出的 `Exception`，避免其破坏 Coordinator 的状态队列。当前回调线程不是主线程；UI 调用方需要自行切换到主线程。

#### 4.2.7 `BluetoothDeviceInfo` 与 `BluetoothTransport`

文件：`tracelet-ble/src/main/java/com/lzb/ble/scan/BluetoothScanner.kt`

`BluetoothDeviceInfo` 是 BLE 和 Classic 共享的原始发现结果：

```kotlin
data class BluetoothDeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val type: BluetoothTransport,
    val transports: Set<BluetoothTransport>
)
```

字段含义：

- `address`：Android 平台返回的设备地址；
- `name`：设备名称，允许为空；
- `rssi`：本次发现结果携带的信号强度；
- `type`：产生本次结果的 Driver 类型；
- `transports`：当前结果已识别的传输集合。

当前每条扫描结果只包含一个 transport，尚未将 BLE 和 Classic 发现合并成统一物理设备。地址和名称都不能直接作为安全身份凭证。

`BluetoothTransport`：

- `LE`：BLE 扫描；
- `CLASSIC`：经典蓝牙扫描；
- `ALL`：用于表达整个模式或聚合启动失败，不代表实际设备传输类型。

#### 4.2.8 `ScanDriver` 与 `DriverListener`

文件：`tracelet-ble/src/main/java/com/lzb/ble/scan/driver/ScanDriver.kt`

```kotlin
internal interface ScanDriver {
    var transport: BluetoothTransport
    fun start(listener: DriverListener): Boolean
    fun stop()
}
```

职责：

- `transport` 标记 Driver 对应的传输方式；
- `start()` 同步提交平台扫描请求，并返回是否成功提交；
- `stop()` 尽力停止平台扫描并清理 Driver 自己持有的资源。

`DriverListener` 是 Driver 到 Coordinator 的内部事件通道：

- `onDeviceFound`：发现设备；
- `onFailed`：运行期间平台扫描失败；
- `onFinished`：平台扫描自然结束。

DriverListener 不直接向宿主暴露。Coordinator 负责 session 校验和公共状态转换。

#### 4.2.9 `BleScannerImpl`

文件：`tracelet-ble/src/main/java/com/lzb/ble/scan/driver/BleScannerImpl.kt`

功能：

- 创建并持有本轮 `ScanCallback`；
- 使用 `SCAN_MODE_LOW_LATENCY` 和空过滤条件启动 BLE 扫描；
- 将单条和批量 `ScanResult` 转换成 `BluetoothDeviceInfo`；
- 将 `onScanFailed(errorCode)` 转换成 Driver 失败事件；
- 使用启动时的同一个 callback 调用 `BluetoothLeScanner.stopScan()`；
- 无论停止成功或抛异常，都在 `finally` 中释放本地 callback 引用。

资源所有权：

```text
创建 ScanCallback → BleScannerImpl 持有
启动平台扫描      → 使用该 callback
停止平台扫描      → 必须使用同一个 callback
结束/失败         → 清除本地 callback 引用
```

同步启动异常会先清理 callback，再重新抛给 Coordinator。异步 `onScanFailed` 通过 `DriverListener.onFailed` 上报。

范围限制：

- 不负责超时、重复启动和公共终态；
- 不解析 Service UUID、Manufacturer Data 或广播 payload；
- 不去重；
- 不负责 GATT 连接；
- 正常 BLE 扫描没有自然完成事件，只能由宿主停止、Coordinator 超时或平台失败结束。

#### 4.2.10 `ClassicScannerImpl`

文件：`tracelet-ble/src/main/java/com/lzb/ble/scan/driver/ClassicScannerImpl.kt`

功能：

- 动态注册 `ACTION_FOUND` 和 `ACTION_DISCOVERY_FINISHED`；
- 调用 `BluetoothAdapter.startDiscovery()`；
- 从 `ACTION_FOUND` 提取 `BluetoothDevice` 与 `EXTRA_RSSI`；
- 将结果转换成 `BluetoothDeviceInfo`；
- 将系统 discovery 自然结束转换成 `DriverListener.onFinished(CLASSIC)`；
- 将设备字段访问中的 `SecurityException` 转换成 `DriverListener.onFailed(CLASSIC, error)`；
- 调用 `cancelDiscovery()` 并注销动态 receiver。

资源所有权：

- `registered: AtomicBoolean` 记录动态 receiver 是否由该实例成功注册；
- `compareAndSet(true, false)` 确保重复清理不会重复注销 receiver；
- `startDiscovery() == false` 时立即清理 receiver、listener 和 discovery；
- `stop()`、自然结束和权限异常复用同一清理逻辑。

范围限制：

- 不负责超时和公共扫描终态；
- 不读取系统已配对设备列表；
- 不建立 RFCOMM/SPP 连接；
- 不筛选设备能力；
- `ACTION_DISCOVERY_FINISHED` 表示 Android 本轮 Classic discovery 已结束，不等同于 BOTH 模式的整个逻辑会话结束。

#### 4.2.11 权限和 Android 版本

模块最低支持 API 24，编译版本为 API 34。

Manifest 声明：

- API 30 及以下：`BLUETOOTH`、`BLUETOOTH_ADMIN`、`ACCESS_FINE_LOCATION`；
- API 31 及以上：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`。

Coordinator 当前检查规则：

- Android 12（API 31）及以上同时要求 `BLUETOOTH_SCAN` 与 `BLUETOOTH_CONNECT`；
- Android 11（API 30）及以下要求 `ACCESS_FINE_LOCATION`。

SDK 只检查权限，不主动向用户申请权限。权限可能在扫描期间变化，因此具体 Driver 仍需处理平台调用产生的 `SecurityException`。

#### 4.2.12 当前保证与非保证

当前保证：

- Coordinator 同时只维护一个逻辑扫描会话；
- 重复启动返回 `AlreadyScanning`；
- 旧 session 的 Driver 事件不会被投递给新 session；
- BOTH 模式至少一个 Driver 启动成功即可进入 `Scanning`；
- 手动停止和 timeout 会尝试停止本次选择的全部 Driver；
- 单个 Driver 停止异常不会阻止其他 Driver 的停止尝试；
- 宿主 listener 异常不会从 Coordinator 的公共派发出口逃逸。

当前不保证：

- 平台 stop 正常返回等于系统已经确认停止；
- `Scanning` 表示已经发现设备；
- 扫描结果唯一、有序或已经完成身份确认；
- BLE 和 Classic 发现结果已合并；
- 所有回调都位于主线程；
- Classic 自然结束表示整个 BOTH 会话结束；
- Driver 局部失败一定需要宿主采取动作；
- 进程长期运行时 Coordinator 有独立的永久释放入口。

### 4.3 设备身份确认


扫描到设备后不能直接认为是目标设备。推荐流程：

```text
扫描候选设备
    ↓
校验 Service UUID / 厂商标识
    ↓
建立连接
    ↓
发现服务
    ↓
读取设备身份特征
    ↓
应用层认证
    ↓
进入 Ready
```

不建议只依赖蓝牙名称或 MAC 地址：名称可能重复，MAC 可能随机化或发生变化。身份模型至少应包含设备唯一 ID、设备类型、协议版本和认证状态。

如果业务存在安全要求，应使用挑战响应或签名认证。认证失败必须进入明确的失败状态，不应自动无限重试。

### 4.4 连接管理

连接管理对业务暴露统一、稳定的状态，而不是直接暴露原始 `BluetoothGatt`、`BluetoothSocket` 或音频 Profile 回调。建议状态：

```text
Idle
  ↓
Scanning
  ↓
Connecting
  ↓
DiscoveringServices
  ↓
Negotiating
  ↓
Authenticating
  ↓
Ready
  ↓
Sending / Receiving
  ↓
Reconnecting
  ↓
Failed
```

每次连接建立一个连接代次或 session token。旧连接迟到的回调不能改变新连接的状态，也不能把旧连接的数据投递到新连接。

统一状态映射不能隐藏底层必要阶段：

```text
BLE GATT:
Connecting → DiscoveringServices → Negotiating → Subscribing → Authenticating → Ready

Classic RFCOMM/SPP:
Connecting → OpeningSocket → Authenticating → Ready

Audio Profile:
由系统管理连接，仅映射音频连接和路由状态，不进入业务数据 Ready
```

同一设备、同一传输通道的连接操作必须串行化。BLE 的服务发现、MTU 协商、Characteristic 读写和通知订阅不能并发执行；RFCOMM 的 Socket 创建、输入读取、输出写入和关闭也必须遵守各自的串行和并发边界。

### 4.5 数据传输

业务消息模型统一，但底层承载方式不同：

- BLE GATT 按 MTU 和 Characteristic 能力分包，通过 Write/Notify/Indicate 传输；
- Classic RFCOMM/SPP 是字节流，必须自行定义消息边界和拆包；
- A2DP/HFP 是音频 Profile，不能作为任意业务消息通道。

不能假设一次 Characteristic write 或一次 Socket write 就等于一条完整业务消息。SDK 应在统一协议层提供：

- 消息 ID；
- 包序号和总包数；
- 分包与组包；
- 最大消息长度；
- 接收缓存上限；
- ACK 或业务确认；
- 超时和有限重试；
- 重复包去重；
- 顺序保证；
- 错误码和取消能力。

建议的数据模型：

```text
messageId
packetIndex
packetCount
payload
```

链路层校验和重传不能替代应用层确认。SDK 需要区分“系统写入成功”和“设备业务确认成功”。

### 4.6 断线与重连

重连策略必须由状态机统一管理，不能在回调中递归调用 connect。建议：

- 使用指数退避；
- 设置最大重试次数或最大重试时间；
- 蓝牙关闭、权限不足、认证失败等不可恢复错误立即停止重试；
- 设备超出范围时允许延迟重试；
- 用户主动取消后不自动重连；
- 前后台切换遵循明确的生命周期策略；
- 重连过程中保留或丢弃发送队列必须由协议策略明确规定。

示例退避：1 秒、2 秒、4 秒、8 秒，并设置上限。具体参数应可配置但必须有最大边界。

## 5. 推荐公共 API 方向

第一版 API 只表达统一设备语义，不把 `BluetoothGatt`、`BluetoothSocket`、平台状态码和内部 Handler 暴露给宿主：

```kotlin
interface BluetoothDeviceClient {
    fun startDiscovery(filter: DeviceFilter, listener: DiscoveryListener)
    fun stopDiscovery()
    fun connect(deviceId: DeviceId)
    fun disconnect()
    fun send(message: ByteArray, callback: SendCallback)
    fun observeConnectionState(listener: ConnectionStateListener)
    fun close()
}
```

统一设备信息至少包含：

```kotlin
data class BluetoothDeviceInfo(
    val deviceId: DeviceId,
    val name: String?,
    val capabilities: Set<DeviceCapability>
)
```

其中 `deviceId` 表示 SDK 的统一设备身份，不应直接等同于广播名称或 MAC 地址。

API 还需要明确：

- 所有回调在哪个线程执行；
- `close` 后回调是否还会到达；
- 重复调用的幂等性；
- connect 与 send 的非法状态行为；
- ByteArray 是否复制，避免业务修改导致数据变化；
- 发送队列满时返回什么错误；
- 设备身份是扫描层 ID 还是认证后的业务 ID。

公共 API 的具体命名等协议和状态模型确定后再定，避免先暴露平台对象导致后续无法替换实现。

## 6. 错误模型

错误必须可分类，至少区分：

- BluetoothUnavailable：设备蓝牙不可用；
- PermissionDenied：权限不足；
- ScanTimeout：扫描超时；
- DeviceNotFound：未找到目标设备；
- ConnectionTimeout：连接超时；
- ServiceDiscoveryFailed：服务发现失败；
- NegotiationFailed：MTU 或参数协商失败；
- AuthenticationFailed：设备身份认证失败；
- WriteFailed：底层写入失败；
- DeviceRejected：设备拒绝业务消息；
- AckTimeout：业务确认超时；
- Disconnected：连接中断；
- QueueFull：发送队列超过上限；
- Cancelled：业务主动取消；
- Closed：SDK 已关闭。

错误类型需要携带有限、可序列化的上下文，例如重试次数、连接阶段和平台错误码，但不能持有 `Context`、`Activity` 或 `BluetoothGatt`。

## 7. 线程与生命周期

推荐所有蓝牙平台操作在一个专用串行执行器上执行，回调通过约定的 dispatcher 投递给业务。SDK 不应让业务回调阻塞蓝牙操作线程。

生命周期策略需要明确：

- `close` 必须停止扫描、取消连接、清空回调并释放 GATT；
- 取消操作必须可重复调用；
- SDK 不默认持有 Activity 或 View；
- 应用进入后台时不默认无限重连；
- 蓝牙开关变化需要转化为状态事件；
- 所有内部异常必须被捕获，不能传播到宿主主流程。

## 8. 分阶段开发计划

### 第一阶段：协议和状态模型

先确定：

- 设备身份模型；
- 服务和特征 UUID 配置；
- 连接状态；
- 错误类型；
- 消息和发送结果；
- 回调线程；
- 取消和关闭语义。

### 第二阶段：BLE GATT 单设备单连接

统一 API 下先落地 BLE GATT 适配器，只支持一台设备：

```text
扫描 → 连接 → 服务发现 → 可选协商 → 订阅 → 读写 → 断开
```

选择 BLE GATT 作为第一个适配器，是为了先验证统一设备会话、状态机和可靠传输抽象，不代表统一 API 仅支持 BLE。

### 第三阶段：可靠传输

在统一协议层增加分包、组包、ACK、超时、重试、去重、顺序保证和发送队列，并给每一项设置明确上限。BLE GATT 先完成适配，后续新增 RFCOMM 时复用业务消息和确认语义，但保留字节流实现差异。

### 第四阶段：Classic 与音频能力适配

根据真实设备能力选择：

- 设备开放 RFCOMM/SPP 时，增加 `ClassicRfcommTransport`；
- 设备只提供 A2DP/HFP 时，仅增加音频连接和路由观察，不提供任意业务数据发送；
- 双模设备由一个统一设备会话组合音频状态与数据通道。

### 第五阶段：真实环境验证

验证以下场景：

- 周围大量手机同时开启蓝牙；
- 周围存在多个同型号设备；
- Wi-Fi 高流量环境；
- 设备断电、超出范围和重新靠近；
- 手机蓝牙开关切换；
- App 前后台切换和屏幕锁定；
- 连接过程中重复调用 connect；
- 大数据连续发送；
- 设备只允许一个手机连接；
- 旧连接回调晚于新连接建立。

## 9. 验收标准

- 能从多个候选设备中确认目标设备身份；
- 连接流程状态变化可观察且不会跳过关键阶段；
- 旧连接回调不会污染新连接；
- 单设备连接操作串行执行；
- 大消息不会因为单次写入限制而静默截断；
- 设备未确认消息时，SDK 能区分写入成功和业务成功；
- 断线后按策略有限重连，不无限快速重试；
- 蓝牙关闭或权限不足时返回明确错误；
- `close` 后不再保留平台连接和业务回调；
- 所有核心状态机和协议逻辑都可以脱离真实蓝牙设备进行单元测试；
- 真机测试覆盖高密度设备和连接异常场景。

## 10. 暂不支持

第一版明确不做：

- 多设备并发连接；
- 后台无限常驻扫描；
- 自动绕过系统权限或厂商限制；
- 仅依赖 MAC 地址的设备安全认证；
- 对射频干扰作绝对保证；
- 在设备未开放 RFCOMM/SPP 时尝试通过 A2DP/HFP 发送任意业务数据；
- 在首个 BLE GATT 适配器尚未稳定前同时实现全部传输方式；
- 把蓝牙数据直接写入 Tracelet 性能报告；
- 在未确定业务协议前固定具体 payload 格式。
