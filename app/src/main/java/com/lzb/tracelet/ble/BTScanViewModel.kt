package com.lzb.tracelet.ble

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lzb.ble.BleManager
import com.lzb.ble.data.BluetoothDeviceInfo
import com.lzb.ble.scan.ScanListener
import com.lzb.ble.scan.ScanMode
import com.lzb.ble.scan.ScanState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============ MVI 契约（页面的输入 / 输出） ============
 * 放在同一个文件，方便一眼看清这个页面有哪些状态、意图和一次性事件。
 */

/** UI 展示用的设备模型。故意不直接用 SDK 的 BluetoothDeviceInfo，避免 SDK 类型泄漏到 UI 层。 */
data class DeviceUi(
    val address: String,
    val name: String?,
    val rssi: Int,
    val transport: String
)

/** 页面的“唯一状态源”。UI 只读这一个对象来渲染。 */
data class BTScanUiState(
    val scanning: Boolean = false,
    val mode: ScanMode = ScanMode.BLE_ONLY,
    val devices: List<DeviceUi> = emptyList(),
    val statusText: String = "未开始"
)

/** 用户意图：UI 只能通过它向 ViewModel 上报动作（单向数据流的“上行”）。 */
sealed interface BTScanIntent {
    data class StartScan(val mode: ScanMode) : BTScanIntent
    data object StopScan : BTScanIntent
    data object ClearDevices : BTScanIntent
}

/** 一次性事件：申请权限 / 开蓝牙 / Toast。不能放进 UiState，否则重组会重复触发。 */
sealed interface BTScanEvent {
    data object RequestPermission : BTScanEvent
    data object RequestEnableBluetooth : BTScanEvent
    data class ShowMessage(val text: String) : BTScanEvent
}

/**
 * 蓝牙扫描页面 ViewModel（MVI 单向数据流）。
 *
 * 职责边界：
 * - 只做“状态管理 + 调用 SDK + 把 SDK 回调归约成 UI 状态”。
 * - 不持有 Activity / View；用 Application context 调 SDK（Application 与进程同生命周期，可安全持有）。
 * - 对外只暴露只读 StateFlow / Flow，UI 改不了状态，只能通过 onIntent 上报意图。
 *
 * 为什么用 AndroidViewModel：BleManager.startScan 需要 Context，
 * AndroidViewModel 自带 Application，省去手动传 Context 并避免泄漏 Activity。
 */
class BTScanViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(BTScanUiState())
    val uiState: StateFlow<BTScanUiState> = _uiState.asStateFlow()

    private val _events = Channel<BTScanEvent>(Channel.BUFFERED)
    val events: Flow<BTScanEvent> = _events.receiveAsFlow()

    // 按 address 去重（SDK 明确不去重）。LinkedHashMap 保留发现顺序，同一设备再次出现时覆盖更新 RSSI。
    private val deviceMap = linkedMapOf<String, DeviceUi>()

    // SDK 回调不保证在主线程，统一切回 viewModelScope 再更新状态。
    // ScanListener 是普通 interface（非 fun interface），这里用匿名对象实现。
    private val scanListener = object : ScanListener {
        override fun onStateChanged(state: ScanState) {
            viewModelScope.launch { reduce(state) }
        }
    }

    fun onIntent(intent: BTScanIntent) {
        when (intent) {
            is BTScanIntent.StartScan -> startScan(intent.mode)
            BTScanIntent.StopScan -> BleManager.stopScan()
            BTScanIntent.ClearDevices -> clearDevices()
        }
    }

    private fun startScan(mode: ScanMode) {
        deviceMap.clear()
        _uiState.update { it.copy(mode = mode, devices = emptyList(), statusText = "准备扫描…") }
        BleManager.startScan(getApplication(), mode, scanListener)
    }

    private fun clearDevices() {
        deviceMap.clear()
        _uiState.update { it.copy(devices = emptyList()) }
    }

    /** 单向数据流核心：SDK 事件 -> 归约 -> UiState / 一次性事件。 */
    private fun reduce(state: ScanState) {
        when (state) {
            ScanState.Scanning ->
                _uiState.update { it.copy(scanning = true, statusText = "扫描中") }

            is ScanState.DeviceFound ->
                state.result?.let(::upsertDevice)

            ScanState.Stopped ->
                _uiState.update { it.copy(scanning = false, statusText = "已停止") }

            ScanState.Timeout ->
                _uiState.update { it.copy(scanning = false, statusText = "扫描超时") }

            ScanState.PermissionDenied -> {
                _uiState.update { it.copy(scanning = false, statusText = "缺少权限") }
                _events.trySend(BTScanEvent.RequestPermission)
            }

            ScanState.BluetoothDisabled -> {
                _uiState.update { it.copy(scanning = false, statusText = "蓝牙未开启") }
                _events.trySend(BTScanEvent.RequestEnableBluetooth)
            }

            ScanState.BluetoothNotSupported -> {
                _uiState.update { it.copy(scanning = false, statusText = "设备不支持蓝牙") }
                _events.trySend(BTScanEvent.ShowMessage("设备不支持蓝牙"))
            }

            // 单路失败：BOTH 下另一路可能仍在扫，这里只提示，不结束页面状态。
            is ScanState.DriverFailed ->
                _events.trySend(BTScanEvent.ShowMessage("${state.transport} 扫描失败"))

            is ScanState.Failed -> {
                _uiState.update { it.copy(scanning = false, statusText = "扫描失败") }
                _events.trySend(BTScanEvent.ShowMessage("扫描启动失败"))
            }

            ScanState.AlreadyScanning ->
                _events.trySend(BTScanEvent.ShowMessage("正在扫描中"))

            is ScanState.Unknown ->
                _events.trySend(BTScanEvent.ShowMessage(state.message ?: "未知错误"))
        }
    }

    private fun upsertDevice(info: BluetoothDeviceInfo) {
        deviceMap[info.address] = DeviceUi(
            address = info.address,
            name = info.name,
            rssi = info.rssi,
            transport = info.type.name
        )
        _uiState.update { it.copy(devices = deviceMap.values.toList()) }
    }

    override fun onCleared() {
        // 页面销毁时收口，避免 SDK 仍在扫描。
        BleManager.stopScan()
    }
}
