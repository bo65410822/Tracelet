package com.lzb.sdk

import android.annotation.SuppressLint
import android.os.Build
import java.io.File
import android.os.Process

object TraceletUtils {

    @SuppressLint("NewApi")
    fun getProcessName(): String {
        // API 28+ 可以直接拿进程名
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Process.myProcessName() ?: ""
        } else {
            // 低版本回退：从 /proc/self/cmdline 读
            try {
                File("/proc/self/cmdline").readText().trimEnd('\u0000')
            } catch (e: Exception) {
                ""
            }
        }
    }
}