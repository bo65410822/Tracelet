package com.lzb.tracelet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lzb.tracelet.ble.BTTestScreen
import com.lzb.tracelet.ui.theme.TraceletTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TraceletTheme {
                MainScreen()
            }
        }
    }
}

/** 底部两个 tab：Auto / BT。 */
private enum class MainTab(val label: String) {
    AUTO("Auto"),
    BT("BT")
}

@Composable
private fun MainScreen() {
    // 记住当前选中的 tab；旋转屏幕后重建会回到默认，简单场景足够。
    var selectedTab by remember { mutableStateOf(MainTab.AUTO) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        // 用文字当图标槽，避免额外引入 material-icons 依赖
                        icon = { Text(tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        // 根据选中的 tab 显示对应页面
        when (selectedTab) {
            MainTab.AUTO -> Modifier.padding(innerPadding).let { AutoTabContent(it) }
            MainTab.BT -> Modifier.padding(innerPadding).let { BtTabContent(it) }
        }
    }
}

@Composable
private fun AutoTabContent(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize()) {
        AutoTestScreen()
    }
}

@Composable
private fun BtTabContent(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize()) {
        BTTestScreen()
    }
}
