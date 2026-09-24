package com.lzb.tracelet.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lzb.ble.scan.ScanMode
import kotlinx.coroutines.flow.collectLatest

/**
 * 蓝牙扫描页面（Compose）。
 *
 * 分层职责：
 * 1) 订阅 ViewModel 的 uiState 渲染界面；
 * 2) 把用户操作通过 onIntent 上报给 ViewModel；
 * 3) 权限属于“UI 能力门槛”，在用户点击扫描的那一刻按需申请（just-in-time）。
 *
 * 为什么权限放在“点击扫描”时申请：
 * - 官方推荐在真正需要能力的操作点申请，用户此刻最理解为什么要授权，通过率更高；
 * - 不在启动/进页面时打断用户；
 * - SDK 只检查权限不申请，申请天然落在 UI 层。
 */
@Composable
fun BTTestScreen(
    viewModel: BTScanViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 记住用户点的是哪种模式：授权回来后要用它发起对应的扫描，
    // 否则默认 mode 会导致“点了都扫，授权后却只扫 BLE”。
    var pendingMode by remember { mutableStateOf(ScanMode.BLE_ONLY) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            viewModel.onIntent(BTScanIntent.StartScan(pendingMode))
        }
    }

    // 点击扫描的统一入口：先查权限，有就直接扫，没有就当场申请。
    fun requestScan(mode: ScanMode) {
        pendingMode = mode
        if (hasScanPermissions(context)) {
            viewModel.onIntent(BTScanIntent.StartScan(mode))
        } else {
            permissionLauncher.launch(requiredScanPermissions())
        }
    }

    // 兜底：扫描过程中权限被撤销时，SDK 会回 PermissionDenied，这里再补一次申请。
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                BTScanEvent.RequestPermission ->
                    permissionLauncher.launch(requiredScanPermissions())

                BTScanEvent.RequestEnableBluetooth -> {
                    // 交给宿主处理：可跳转系统蓝牙开关，这里保持最小实现
                }

                is BTScanEvent.ShowMessage -> {
                    // 实际可用 SnackbarHost / Toast，这里保持最小实现
                }
            }
        }
    }

    BTScanContent(
        state = state,
        onStart = { mode -> requestScan(mode) },
        onStop = { viewModel.onIntent(BTScanIntent.StopScan) },
        onClear = { viewModel.onIntent(BTScanIntent.ClearDevices) }
    )
}

/**
 * 纯展示组件：只依赖入参，不碰 ViewModel。
 * 好处是可单独预览、可复用、易测试。
 */
@Composable
private fun BTScanContent(
    state: BTScanUiState,
    onStart: (ScanMode) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        Text(text = "状态：${state.statusText}（${if (state.scanning) "扫描中" else "空闲"}）")

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { onStart(ScanMode.BLE_ONLY) }) { Text("扫 BLE") }
            Button(onClick = { onStart(ScanMode.CLASSIC_ONLY) }) { Text("扫经典") }
            Button(onClick = { onStart(ScanMode.BOTH) }) { Text("都扫") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onStop) { Text("停止") }
            OutlinedButton(onClick = onClear) { Text("清空") }
        }

        Text(text = "设备（${state.devices.size}）")

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.devices, key = { it.address }) { device ->
                DeviceRow(device)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceUi) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = device.name ?: "(无名设备)")
        Text(text = "${device.address}  ·  ${device.transport}  ·  RSSI ${device.rssi}")
    }
}

/** 按系统版本返回本次要申请的权限。Android 12+ 用新蓝牙权限，否则用定位权限。 */
private fun requiredScanPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

/** 是否已经拿到本次扫描需要的全部权限。 */
private fun hasScanPermissions(context: Context): Boolean {
    return requiredScanPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
