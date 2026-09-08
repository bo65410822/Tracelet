package com.lzb.tracelet

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lzb.core.TraceletConfig
import com.lzb.core.TraceletEvent
import com.lzb.sdk.Tracelet
import com.lzb.sdk.TraceletEventListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CHECK_THRESHOLD_MS = 700L
private const val BLOCK_DURATION_MS = 1200L
private const val WAIT_EVENT_TIMEOUT_MS = 5_000L
private const val FREEZE_EVENT_TYPE = "freeze"

private enum class AutoStepState {
    PENDING,
    RUNNING,
    PASS,
    FAIL
}

private data class AutoStep(
    val title: String,
    val state: AutoStepState = AutoStepState.PENDING,
    val detail: String? = null
)

private enum class AutoTestState {
    PENDING,
    RUNNING,
    PASS,
    FAIL
}

private data class AutoTestUi(
    val id: String,
    val title: String,
    val description: String,
    val steps: List<AutoStep>,
    val state: AutoTestState = AutoTestState.PENDING,
    val durationMs: Long? = null
)

@Composable
fun AutoTestScreen(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var tests by remember { mutableStateOf(initialAutoTests()) }
    var running by remember { mutableStateOf(false) }

    fun updateStep(
        testIndex: Int,
        stepIndex: Int,
        state: AutoStepState,
        detail: String? = null
    ) {
        tests = tests.mapIndexed { index, test ->
            if (index != testIndex) {
                test
            } else {
                test.copy(
                    steps = test.steps.mapIndexed { step, item ->
                        if (step == stepIndex) {
                            item.copy(state = state, detail = detail)
                        } else {
                            item
                        }
                    }
                )
            }
        }
    }

    fun updateTestState(
        testIndex: Int,
        state: AutoTestState,
        durationMs: Long
    ) {
        tests = tests.mapIndexed { index, test ->
            if (index == testIndex) {
                test.copy(state = state, durationMs = durationMs)
            } else {
                test
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "自动测试",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "同一页面运行全部测试，实时显示每一步进度和最终结论。",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = {
                if (running) return@Button
                running = true
                tests = initialAutoTests()
                scope.launch {
                    runAutoTests(
                        appContext = appContext,
                        updateStep = { testIndex, stepIndex, state, detail ->
                            updateStep(testIndex, stepIndex, state, detail)
                        },
                        updateTest = { testIndex, state, duration ->
                            updateTestState(testIndex, state, duration)
                        }
                    )
                    running = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running
        ) {
            Text(if (running) "测试运行中…" else "运行全部测试")
        }

        tests.forEachIndexed { index, test ->
            AutoTestCard(
                test = test,
                runningThis = running && test.state == AutoTestState.RUNNING
            )
        }
    }
}

@Composable
private fun AutoTestCard(
    test: AutoTestUi,
    runningThis: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = test.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                if (runningThis) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = testStateLabel(test.state),
                    color = testStateColor(test.state),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text(
                text = test.description,
                style = MaterialTheme.typography.bodySmall
            )
            test.steps.forEachIndexed { index, step ->
                AutoStepRow(step = step)
            }
            test.durationMs?.let {
                Text(
                    text = "耗时：$it ms",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AutoStepRow(step: AutoStep) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val color = when (step.state) {
            AutoStepState.PASS -> Color(0xFF2E7D32)
            AutoStepState.FAIL -> Color(0xFFC62828)
            AutoStepState.RUNNING -> MaterialTheme.colorScheme.primary
            AutoStepState.PENDING -> Color(0xFF9E9E9E)
        }
        Text(
            text = "●",
            color = color,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = buildString {
                append(step.title)
                step.detail?.let {
                    append("（")
                    append(it)
                    append("）")
                }
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private suspend fun runAutoTests(
    appContext: Context,
    updateStep: (Int, Int, AutoStepState, String?) -> Unit,
    updateTest: (Int, AutoTestState, Long) -> Unit
) {
    val startAt = System.currentTimeMillis()

    val freezePassed = runFreezeAutoTest { stepIndex, state, detail ->
        updateStep(0, stepIndex, state, detail)
    }
    updateTest(0, if (freezePassed) AutoTestState.PASS else AutoTestState.FAIL, System.currentTimeMillis() - startAt)

    val sessionStartAt = System.currentTimeMillis()
    val sessionPassed = runSessionAutoTest(appContext) { stepIndex, state, detail ->
        updateStep(1, stepIndex, state, detail)
    }
    updateTest(
        1,
        if (sessionPassed) AutoTestState.PASS else AutoTestState.FAIL,
        System.currentTimeMillis() - sessionStartAt
    )
}

private suspend fun runFreezeAutoTest(
    onStep: (Int, AutoStepState, String?) -> Unit
): Boolean {
    onStep(0, AutoStepState.RUNNING, null)
    onStep(0, AutoStepState.PASS, "宿主已完成初始化并启动采集")

    onStep(1, AutoStepState.RUNNING, null)
    val eventDeferred = CompletableDeferred<TraceletEvent>()
    val listener = object : TraceletEventListener {
        override fun onEvent(event: TraceletEvent) {
            if (event.type == FREEZE_EVENT_TYPE) {
                eventDeferred.complete(event)
            }
        }
    }
    Tracelet.setEventListener(listener)
    onStep(1, AutoStepState.PASS, "监听器已注册")

    onStep(2, AutoStepState.RUNNING, null)
    Handler(Looper.getMainLooper()).post {
        Thread.sleep(BLOCK_DURATION_MS)
    }
    val event = try {
        withTimeoutOrNull(WAIT_EVENT_TIMEOUT_MS) {
            eventDeferred.await()
        }
    } finally {
        Tracelet.setEventListener(null)
    }

    if (event == null) {
        onStep(2, AutoStepState.FAIL, "未检测到本次卡顿")
        onStep(3, AutoStepState.FAIL, "监听器未收到事件")
        onStep(4, AutoStepState.PENDING, null)
        return false
    }

    onStep(2, AutoStepState.PASS, "主线程阻塞 ${BLOCK_DURATION_MS}ms")
    onStep(3, AutoStepState.PASS, "收到 type=${event.type}")

    val durationMs = event.attributes["durationMs"]?.toLongOrNull() ?: 0L
    val contentValid = durationMs >= CHECK_THRESHOLD_MS && event.samples.isNotEmpty()
    onStep(
        4,
        if (contentValid) AutoStepState.PASS else AutoStepState.FAIL,
        if (contentValid) {
            "时长 ${durationMs}ms，样本 ${event.samples.size} 行"
        } else {
            "事件内容不完整"
        }
    )
    return contentValid
}

private suspend fun runSessionAutoTest(
    appContext: Context,
    onStep: (Int, AutoStepState, String?) -> Unit
): Boolean {
    onStep(0, AutoStepState.RUNNING, null)
    val beforeList = Tracelet.readEvents()
    val original = beforeList
        .asSequence()
        .filter { it.type == FREEZE_EVENT_TYPE }
        .maxByOrNull { it.timestampMs }
    if (original == null) {
        onStep(0, AutoStepState.FAIL, "本地没有可校验的 freeze 事件")
        onStep(1, AutoStepState.PENDING, null)
        onStep(2, AutoStepState.PENDING, null)
        onStep(3, AutoStepState.PENDING, null)
        return false
    }
    onStep(0, AutoStepState.PASS, "session=${original.sessionId}")

    onStep(1, AutoStepState.RUNNING, null)
    Tracelet.close()
    Tracelet.initialize(
        appContext,
        TraceletConfig(thresholdMs = CHECK_THRESHOLD_MS)
    )
    Tracelet.start()
    onStep(1, AutoStepState.PASS, "已重新初始化新会话")

    onStep(2, AutoStepState.RUNNING, null)
    val afterList = Tracelet.readEvents()
    val restored = afterList.firstOrNull { it.id == original.id }
    onStep(
        2,
        if (restored != null) AutoStepState.PASS else AutoStepState.FAIL,
        if (restored != null) "重新读取到事件" else "未找到原事件"
    )

    val sameSession = restored?.sessionId == original.sessionId
    val sameDuration = restored?.attributes?.get("durationMs") == original.attributes["durationMs"]
    val valid = restored != null && sameSession && sameDuration
    onStep(
        3,
        if (valid) AutoStepState.PASS else AutoStepState.FAIL,
        if (valid) {
            "sessionId 保持 ${original.sessionId}"
        } else {
            "sessionId 或字段不一致"
        }
    )
    return valid
}

private fun initialAutoTests(): List<AutoTestUi> = listOf(
    AutoTestUi(
        id = "freeze",
        title = "卡顿检测链路",
        description = "初始化 → 注册监听 → 主线程阻塞 → 实时收到 FreezeEvent",
        steps = listOf(
            AutoStep("启动采集"),
            AutoStep("注册实时监听"),
            AutoStep("阻塞主线程超过阈值"),
            AutoStep("监听收到 FreezeEvent"),
            AutoStep("校验时长与堆栈样本")
        )
    ),
    AutoTestUi(
        id = "session",
        title = "跨会话读取一致",
        description = "写入事件 → 关闭并重新初始化 → readEvents 仍能还原原 session",
        steps = listOf(
            AutoStep("读取当前 freeze 事件"),
            AutoStep("关闭并重新初始化"),
            AutoStep("重启后 readEvents"),
            AutoStep("校验 sessionId 与字段一致")
        )
    )
)

private fun testStateLabel(state: AutoTestState): String = when (state) {
    AutoTestState.PENDING -> "待执行"
    AutoTestState.RUNNING -> "运行中"
    AutoTestState.PASS -> "通过"
    AutoTestState.FAIL -> "失败"
}

@Composable
private fun testStateColor(state: AutoTestState): Color = when (state) {
    AutoTestState.PENDING -> Color(0xFF9E9E9E)
    AutoTestState.RUNNING -> MaterialTheme.colorScheme.primary
    AutoTestState.PASS -> Color(0xFF2E7D32)
    AutoTestState.FAIL -> Color(0xFFC62828)
}
