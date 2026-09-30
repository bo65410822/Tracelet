# 003：连接能力当前仅支持 BLE，Classic 待真实需求触发

## 背景

`tracelet-ble` 的统一设备设计（见 `docs/ble-sdk-design.md`）在长期方向上覆盖 BLE GATT、Classic RFCOMM/SPP 和音频 Profile 观察。但连接能力的第一阶段实现（`BluetoothConnectionCoordinator`）目前只使用 `BluetoothDevice.connectGatt`，属于纯 BLE/GATT 连接。

经典蓝牙连接是另一套模型：SPP/RFCOMM 需要 `createRfcommSocketToServiceRecord` + 阻塞式 `socket.connect()` 与输入输出流，是同步阻塞而非回调驱动；A2DP/HFP/HID 等系统 Profile 的主动连接大多不在公开 API 上，只能通过广播观察状态。两者的 API、线程模型、配对流程和资源类型都与 GATT 不同，无法直接复用当前基于 `BluetoothGattCallback` 的连接状态机。

本 SDK 当前没有业务方输入，无法确认是否存在需要连接经典蓝牙设备的真实场景。

## 决策

连接能力当前仅实现 BLE GATT 单设备单连接。不实现经典蓝牙连接，也不为其预先引入传输抽象层（`ConnectionDriver` 分层）。

`BluetoothConnectionCoordinator` 中与传输方式无关的通用逻辑保持独立：会话分配与失效（`activeSessionId` / `sessionCounter`）、单串行 scope 派发、终态纪律（一次连接只产生一个终态）、连接超时、迟到回调过滤、listener 异常隔离、连接前置检查（蓝牙支持/开关/权限）。这些逻辑在将来接入 Classic 时可复用。

只有出现以下任一真实信号时，才重新评估经典蓝牙连接，并届时再从 Coordinator 抽出 `ConnectionDriver` 抽象、新增 SPP 实现：

- 明确的目标设备是 SPP 串口模块（如 HC-05/06 或同类）且必须支持；
- 接入方或业务明确要求连接经典蓝牙设备；
- 出现"设备只有 Classic、没有 BLE"的真实对接需求。

在上述信号出现前，经典蓝牙连接属于"目前没有证据确认需要"。

## 原因

现代智能硬件绝大多数是 BLE，Classic 数据连接主要是遗留/特定领域场景（串口透传模块、部分工业/POS 设备），属于长尾。为未确认的需求实现一整套阻塞式 Socket 连接及其配对、超时、流管理，投入产出不成比例，且"经典蓝牙连接"具体指 SPP 还是 Profile 本身尚不明确，需求不清晰时提前实现容易做错方向。

BLE 优先可先验证统一会话、连接状态机和资源治理这套核心抽象；由于通用会话逻辑已与 GATT 细节自然分离，将来若需 Classic，演进成本低，且有 BLE 实现作参照，抽象会更准确。这与 `ble-sdk-design.md` 第 8 章分阶段计划（第二阶段先做 BLE GATT、第四阶段按真实设备能力再做 Classic/音频）一致。

## 影响

- 连接相关的公共状态、错误码和 API 当前只需覆盖 BLE 语义；文档在描述连接能力时须标注"仅 BLE"。
- 空的 `ConnectionDriver` 接口当前无实现者，属预留的空抽象，可先删除，待真实需要第二种连接时再引入。
- 若未来触发 Classic 需求，须以独立传输适配器实现，复用统一的业务消息与确认语义，但保留 Socket 字节流与 GATT 的实现差异，不得把 Classic 强行套进 GATT 回调模型。
- 本决策仅限定连接实现范围，不改变扫描已支持 BLE/Classic/BOTH 的现状（扫描的"发现"对两种传输都有意义，连接则针对明确的单一目标设备）。
