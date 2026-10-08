package com.nikonlink.app.shared.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断事件单元测试
 */
class DiagnosticEventTest {

    @Test
    fun `connection factory creates event with correct category`() {
        val event = DiagnosticEvent.connection(
            level = DiagLevel.SUCCESS,
            channel = DiagChannel.BLE,
            summary = "BLE 连接成功"
        )
        assertEquals(DiagCategory.CONNECTION.name, event.category)
        assertEquals(DiagLevel.SUCCESS.name, event.level)
        assertEquals(DiagChannel.BLE.name, event.channel)
        assertEquals("BLE 连接成功", event.summary)
        assertTrue(event.timestamp > 0)
    }

    @Test
    fun `transfer factory creates event with correct category`() {
        val event = DiagnosticEvent.transfer(
            level = DiagLevel.INFO,
            channel = DiagChannel.WIFI_STA,
            summary = "开始传输",
            detail = "文件：DSC_0001.JPG"
        )
        assertEquals(DiagCategory.TRANSFER.name, event.category)
        assertEquals("开始传输", event.summary)
        assertEquals("文件：DSC_0001.JPG", event.detail)
    }

    @Test
    fun `liveView factory creates event with correct category`() {
        val event = DiagnosticEvent.liveView(
            level = DiagLevel.WARNING,
            channel = DiagChannel.WIFI_AP,
            summary = "监看帧丢失"
        )
        assertEquals(DiagCategory.LIVE_VIEW.name, event.category)
        assertEquals(DiagLevel.WARNING.name, event.level)
    }

    @Test
    fun `remote factory creates event with correct category`() {
        val event = DiagnosticEvent.remote(
            level = DiagLevel.INFO,
            channel = DiagChannel.USB,
            summary = "执行遥控命令"
        )
        assertEquals(DiagCategory.REMOTE.name, event.category)
        assertEquals(DiagChannel.USB.name, event.channel)
    }

    @Test
    fun `error factory creates event with correct category and level`() {
        val event = DiagnosticEvent.error(
            channel = DiagChannel.SYSTEM,
            summary = "未知错误",
            detail = "堆栈信息..."
        )
        assertEquals(DiagCategory.ERROR.name, event.category)
        assertEquals(DiagLevel.ERROR.name, event.level)
        assertEquals(DiagChannel.SYSTEM.name, event.channel)
    }

    @Test
    fun `optional fields can be null`() {
        val event = DiagnosticEvent.connection(
            level = DiagLevel.INFO,
            channel = DiagChannel.BLE,
            summary = "扫描中"
        )
        assertNull(event.detail)
        assertNull(event.cameraModel)
        assertNull(event.sessionId)
        assertNull(event.extras)
    }

    @Test
    fun `extras are serialized to JSON string`() {
        val event = DiagnosticEvent.connection(
            level = DiagLevel.ERROR,
            channel = DiagChannel.WIFI_STA,
            summary = "连接失败",
            extras = mapOf("code" to 500, "msg" to "timeout")
        )
        assertNotNull(event.extras)
        assertTrue(event.extras!!.contains("code"))
        assertTrue(event.extras!!.contains("500"))
        assertTrue(event.extras!!.contains("timeout"))
    }

    @Test
    fun `levelEnum returns correct enum value`() {
        val event = DiagnosticEvent.connection(
            level = DiagLevel.WARNING,
            channel = DiagChannel.BLE,
            summary = "信号弱"
        )
        assertEquals(DiagLevel.WARNING, event.levelEnum())
    }

    @Test
    fun `channelEnum returns correct enum value`() {
        val event = DiagnosticEvent.connection(
            level = DiagLevel.INFO,
            channel = DiagChannel.USB,
            summary = "USB 检测"
        )
        assertEquals(DiagChannel.USB, event.channelEnum())
    }

    @Test
    fun `formatDateTime returns non-empty string`() {
        val event = DiagnosticEvent.connection(
            level = DiagLevel.INFO,
            channel = DiagChannel.BLE,
            summary = "测试"
        )
        val formatted = event.formatDateTime()
        assertTrue(formatted.isNotEmpty())
        assertTrue(formatted.contains(":"))
    }

    @Test
    fun `DiagLevel emoji returns appropriate symbol`() {
        assertEquals("ℹ️", DiagLevel.INFO.emoji)
        assertEquals("✓", DiagLevel.SUCCESS.emoji)
        assertEquals("⚠", DiagLevel.WARNING.emoji)
        assertEquals("✗", DiagLevel.ERROR.emoji)
    }

    @Test
    fun `DiagChannel label returns Chinese name`() {
        assertEquals("蓝牙", DiagChannel.BLE.label)
        assertEquals("WiFi 热点", DiagChannel.WIFI_AP.label)
        assertEquals("WiFi STA", DiagChannel.WIFI_STA.label)
        assertEquals("USB", DiagChannel.USB.label)
        assertEquals("系统", DiagChannel.SYSTEM.label)
        assertEquals("未知", DiagChannel.UNKNOWN.label)
    }

    @Test
    fun `DiagCategory label returns Chinese name`() {
        assertEquals("连接", DiagCategory.CONNECTION.label)
        assertEquals("传输", DiagCategory.TRANSFER.label)
        assertEquals("实时取景", DiagCategory.LIVE_VIEW.label)
        assertEquals("遥控拍摄", DiagCategory.REMOTE.label)
        assertEquals("系统", DiagCategory.SYSTEM.label)
        assertEquals("错误", DiagCategory.ERROR.label)
    }

    @Test
    fun `MAX_EVENTS constant is reasonable`() {
        assertEquals(2000, DiagnosticEvent.MAX_EVENTS)
    }
}
