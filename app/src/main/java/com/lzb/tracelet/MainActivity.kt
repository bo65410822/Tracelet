package com.lzb.tracelet

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lzb.core.TraceletConfig
import com.lzb.core.TraceletEvent
import com.lzb.sdk.Tracelet
import com.lzb.sdk.TraceletEventListener
import com.lzb.tracelet.ui.theme.TraceletTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Tracelet.initialize(
            applicationContext,
            TraceletConfig(thresholdMs = CHECK_THRESHOLD_MS)
        )
        Tracelet.start()

        setContent {
            TraceletTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var page by rememberSaveable { mutableStateOf(DemoPage.MANUAL) }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DemoPageButton(
                                text = "手动验证",
                                selected = page == DemoPage.MANUAL,
                                onClick = { page = DemoPage.MANUAL }
                            )
                            DemoPageButton(
                                text = "自动测试",
                                selected = page == DemoPage.AUTO,
                                onClick = { page = DemoPage.AUTO }
                            )
                        }
                        when (page) {
                            DemoPage.MANUAL -> FreezeCheckScreen(
                                modifier = Modifier.fillMaxSize()
                            )

                            DemoPage.AUTO -> AutoTestScreen(
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class DemoPage {
    MANUAL,
    AUTO
}

@Composable
private fun DemoPageButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick) {
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(text)
        }
    }
}

private const val CHECK_THRESHOLD_MS = 700L
private const val BLOCK_DURATION_MS = 1200L
private const val WAIT_EVENT_TIMEOUT_MS = 5_000L
private const val FREEZE_EVENT_TYPE = "freeze"

private enum class CheckStatus {
    IDLE,
    CHECKING,
    PASSED,
    FAILED
}

private enum class ItemResult {
    PENDING,
    PASS,
    FAIL
}

private data class CheckItem(
    val title: String,
    val result: ItemResult
)

private data class CheckUiState(
    val status: CheckStatus = CheckStatus.IDLE,
    val triggeredAtMs: Long? = null,
    val event: TraceletEvent? = null
)

@Composable
private fun FreezeCheckScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(CheckUiState()) }

    val event = uiState.event
    val durationMs = event?.attributes?.get("durationMs")?.toLongOrNull()
    val checkItems = remember(uiState) {
        buildCheckItems(uiState)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Tracelet 卡顿检测",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "点击下方按钮会真实阻塞主线程 $BLOCK_DURATION_MS ms，随后读取本地报告验证整条采集链路是否正常。",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "流程状态",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.status == CheckStatus.CHECKING) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = statusMessage(uiState),
                        color = statusColor(uiState.status),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Text(
                    text = "阈值 ${CHECK_THRESHOLD_MS}ms",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "检查项",
                    style = MaterialTheme.typography.titleMedium
                )
                checkItems.forEach { item ->
                    CheckItemRow(item)
                }
            }
        }

        Button(
            onClick = {
                val triggeredAt = System.currentTimeMillis()
                uiState = CheckUiState(
                    status = CheckStatus.CHECKING,
                    triggeredAtMs = triggeredAt
                )
                val eventDeferred = CompletableDeferred<TraceletEvent>()
                val listener = object : TraceletEventListener {
                    override fun onEvent(event: TraceletEvent) {
                        if (event.type == FREEZE_EVENT_TYPE &&
                            event.timestampMs >= triggeredAt
                        ) {
                            eventDeferred.complete(event)
                        }
                    }
                }
                Tracelet.setEventListener(listener)
                // 阻塞必须作为一条 Looper 消息执行，FreezeCollector
                // 才能通过消息开始/结束日志检测到这次卡顿。
                Handler(Looper.getMainLooper()).post {
                    Thread.sleep(BLOCK_DURATION_MS)
                    scope.launch {
                        val latest = try {
                            withTimeoutOrNull(WAIT_EVENT_TIMEOUT_MS) {
                                eventDeferred.await()
                            }
                        } finally {
                            Tracelet.setEventListener(null)
                        }
                        val eventValid = latest?.let { event ->
                            val actualDuration =
                                event.attributes["durationMs"]?.toLongOrNull() ?: 0L
                            actualDuration >= CHECK_THRESHOLD_MS &&
                                event.samples.isNotEmpty()
                        } ?: false
                        uiState = CheckUiState(
                            status = if (eventValid) {
                                CheckStatus.PASSED
                            } else {
                                CheckStatus.FAILED
                            },
                            triggeredAtMs = triggeredAt,
                            event = latest
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.status != CheckStatus.CHECKING
        ) {
            Text(
                text = when (uiState.status) {
                    CheckStatus.IDLE -> "开始流程检测"
                    CheckStatus.CHECKING -> "检测进行中…"
                    CheckStatus.PASSED, CheckStatus.FAILED -> "重新检测"
                }
            )
        }
        Text(
            text = "检测期间页面会短暂无响应，这是制造卡顿的正常现象。",
            style = MaterialTheme.typography.bodySmall
        )

        if (event != null) {
            EventDetailCard(
                event = event,
                durationMs = durationMs
            )
        }
    }
}

@Composable
private fun CheckItemRow(item: CheckItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val color = when (item.result) {
            ItemResult.PASS -> Color(0xFF2E7D32)
            ItemResult.FAIL -> Color(0xFFC62828)
            ItemResult.PENDING -> Color(0xFF9E9E9E)
        }
        val label = when (item.result) {
            ItemResult.PASS -> "通过"
            ItemResult.FAIL -> "未通过"
            ItemResult.PENDING -> "待执行"
        }
        Text(
            text = "●",
            color = color,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = item.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun EventDetailCard(
    event: TraceletEvent,
    durationMs: Long?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "本次事件详情",
                style = MaterialTheme.typography.titleMedium
            )
            KeyValueRow(
                key = "类型",
                value = event.type
            )
            KeyValueRow(
                key = "发生时间",
                value = timeText(event.timestampMs)
            )
            KeyValueRow(
                key = "卡顿时长",
                value = durationMs?.let { "$it ms" } ?: "未知"
            )
            KeyValueRow(
                key = "阈值",
                value = event.attributes["thresholdMs"]?.let { "$it ms" } ?: "未知"
            )
            KeyValueRow(
                key = "堆栈样本",
                value = "${event.samples.size} 行"
            )
            if (event.samples.isNotEmpty()) {
                Text(
                    text = "堆栈前 3 行：",
                    style = MaterialTheme.typography.labelMedium
                )
                event.samples.take(3).forEach { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = key,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun buildCheckItems(state: CheckUiState): List<CheckItem> {
    val runFinished = state.status == CheckStatus.PASSED ||
        state.status == CheckStatus.FAILED
    val event = state.event
    val durationOk = event
        ?.attributes
        ?.get("durationMs")
        ?.toLongOrNull()
        ?.let { it >= CHECK_THRESHOLD_MS } ?: false
    val eventValid = event != null && durationOk && event.samples.isNotEmpty()

    return listOf(
        CheckItem(
            title = "初始化并启动 Tracelet 采集",
            result = ItemResult.PASS
        ),
        CheckItem(
            title = "在主线程制造 $BLOCK_DURATION_MS ms 卡顿",
            result = if (state.triggeredAtMs != null) {
                ItemResult.PASS
            } else {
                ItemResult.PENDING
            }
        ),
        CheckItem(
            title = "生成 FreezeEvent 并写入本地报告",
            result = when {
                !runFinished -> ItemResult.PENDING
                event != null -> ItemResult.PASS
                else -> ItemResult.FAIL
            }
        ),
        CheckItem(
            title = "事件包含合格时长与主线程堆栈样本",
            result = when {
                !runFinished -> ItemResult.PENDING
                eventValid -> ItemResult.PASS
                else -> ItemResult.FAIL
            }
        )
    )
}

private fun statusMessage(state: CheckUiState): String = when (state.status) {
    CheckStatus.IDLE -> "尚未开始，等待执行一次检测"
    CheckStatus.CHECKING -> "阻塞已完成，正在等待事件写入并回读报告…"
    CheckStatus.PASSED -> "流程正常，卡顿事件已完整采集"
    CheckStatus.FAILED -> failureMessage(state)
}

private fun failureMessage(state: CheckUiState): String {
    val event = state.event ?: return "等待 ${WAIT_EVENT_TIMEOUT_MS}ms 后未读到本次 FreezeEvent"
    val durationMs = event.attributes["durationMs"]?.toLongOrNull()
    if (durationMs == null) {
        return "事件缺少 durationMs 字段"
    }
    if (durationMs < CHECK_THRESHOLD_MS) {
        return "事件时长 ${durationMs}ms 低于阈值 ${CHECK_THRESHOLD_MS}ms"
    }
    if (event.samples.isEmpty()) {
        return "事件没有采集到主线程堆栈样本"
    }
    return "事件内容不符合预期，请查看下方详情"
}

@Composable
private fun statusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    CheckStatus.CHECKING -> MaterialTheme.colorScheme.primary
    CheckStatus.PASSED -> Color(0xFF2E7D32)
    CheckStatus.FAILED -> Color(0xFFC62828)
}

private fun timeText(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestampMs))
