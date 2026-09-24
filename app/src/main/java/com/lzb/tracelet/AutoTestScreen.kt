package com.lzb.tracelet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Auto 页面占位实现。
 * 真正的自动化测试页面还没做，这里先放一个假页面，保证 tab 能正常切换。
 */
@Composable
fun AutoTestScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "Auto 页面（待实现）")
    }
}
