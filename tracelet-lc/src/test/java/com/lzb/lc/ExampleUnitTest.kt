package com.lzb.lc

import com.lzb.lc.frame.FrameProtocol
import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun crc32_isCorrect() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val crc = FrameProtocol.crc32(data, 0, data.size)
        assertEquals(0x0F0B4C37, crc)
    }
}