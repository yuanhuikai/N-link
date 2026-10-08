package com.nikonlink.app.shared.diagnostics

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 诊断事件分类（跨 BLE / WiFi / USB 统一）。
 *
 * 设计原则：
 * - 用户可读：不用协议术语，用户导出日志能看懂
 * - 固定枚举：新增可以，改删值会破坏日志兼容
 */
enum class DiagCategory(val label: String) {
    CONNECTION("连接"),
    TRANSFER("传输"),
    LIVE_VIEW("实时取景"),
    REMOTE("遥控拍摄"),
    SYSTEM("系统"),
    ERROR("错误")
}

/**
 * 事件级别（日志严重程度）。
 */
enum class DiagLevel(val label: String, val emoji: String) {
    INFO("信息", "ℹ️"),
    SUCCESS("成功", "✓"),
    WARNING("警告", "⚠"),
    ERROR("失败", "✗")
}

/**
 * 通道类型（连接/传输所用的物理通道）。
 */
enum class DiagChannel(val label: String) {
    BLE("蓝牙"),
    WIFI_AP("WiFi 热点"),
    WIFI_STA("WiFi STA"),
    USB("USB"),
    SYSTEM("系统"),
    UNKNOWN("未知")
}

/**
 * 诊断事件实体 —— 持久化结构化日志。
 *
 * 设计目标：
 * - 用户可读：timestamp + 一句话摘要 + 可选详情
 * - 开发者可查：category/channel/level 可筛选、extras 可扩展
 * - 隐私安全：不存 WiFi 密码、GPS、序列号
 * - 空间有限：最多保留 2000 条，超出自动清理旧记录
 */
@Entity(
    tableName = "diagnostic_events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["category"]),
        Index(value = ["session_id"])
    ]
)
data class DiagnosticEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 事件时间戳（epoch millis） */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    /** 事件分类 */
    @ColumnInfo(name = "category")
    val category: String,

    /** 事件级别 */
    @ColumnInfo(name = "level")
    val level: String,

    /** 通道类型 */
    @ColumnInfo(name = "channel")
    val channel: String,

    /** 一句话摘要（用户可读，如"连接成功"、"下载 IMG_001.NEF"） */
    @ColumnInfo(name = "summary")
    val summary: String,

    /** 详细信息（可选，如失败原因、PTP 响应码、耗时） */
    @ColumnInfo(name = "detail")
    val detail: String? = null,

    /** 相机型号（连接成功后填入，用于关联） */
    @ColumnInfo(name = "camera_model")
    val cameraModel: String? = null,

    /** 会话 ID（同一次连接/传输的事件共享同一个 ID，便于追踪） */
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,

    /** 扩展字段（JSON 格式，如 {"ptp_code": "0x2019", "retry": 3}） */
    @ColumnInfo(name = "extras")
    val extras: String? = null
) {
    companion object {
        /** 内存中最多保留多少条（超出时清理旧记录） */
        const val MAX_EVENTS = 2000

        /** 创建连接事件 */
        fun connection(
            level: DiagLevel,
            channel: DiagChannel,
            summary: String,
            detail: String? = null,
            cameraModel: String? = null,
            sessionId: String? = null,
            extras: Map<String, Any?>? = null
        ) = DiagnosticEvent(
            category = DiagCategory.CONNECTION.name,
            level = level.name,
            channel = channel.name,
            summary = summary,
            detail = detail,
            cameraModel = cameraModel,
            sessionId = sessionId,
            extras = extras?.toJsonString()
        )

        /** 创建传输事件 */
        fun transfer(
            level: DiagLevel,
            channel: DiagChannel,
            summary: String,
            detail: String? = null,
            cameraModel: String? = null,
            sessionId: String? = null,
            extras: Map<String, Any?>? = null
        ) = DiagnosticEvent(
            category = DiagCategory.TRANSFER.name,
            level = level.name,
            channel = channel.name,
            summary = summary,
            detail = detail,
            cameraModel = cameraModel,
            sessionId = sessionId,
            extras = extras?.toJsonString()
        )

        /** 创建实时取景事件 */
        fun liveView(
            level: DiagLevel,
            channel: DiagChannel,
            summary: String,
            detail: String? = null,
            cameraModel: String? = null,
            sessionId: String? = null,
            extras: Map<String, Any?>? = null
        ) = DiagnosticEvent(
            category = DiagCategory.LIVE_VIEW.name,
            level = level.name,
            channel = channel.name,
            summary = summary,
            detail = detail,
            cameraModel = cameraModel,
            sessionId = sessionId,
            extras = extras?.toJsonString()
        )

        /** 创建遥控拍摄事件 */
        fun remote(
            level: DiagLevel,
            channel: DiagChannel,
            summary: String,
            detail: String? = null,
            cameraModel: String? = null,
            sessionId: String? = null,
            extras: Map<String, Any?>? = null
        ) = DiagnosticEvent(
            category = DiagCategory.REMOTE.name,
            level = level.name,
            channel = channel.name,
            summary = summary,
            detail = detail,
            cameraModel = cameraModel,
            sessionId = sessionId,
            extras = extras?.toJsonString()
        )

        /** 创建系统/错误事件 */
        fun error(
            channel: DiagChannel,
            summary: String,
            detail: String? = null,
            cameraModel: String? = null,
            sessionId: String? = null,
            extras: Map<String, Any?>? = null
        ) = DiagnosticEvent(
            category = DiagCategory.ERROR.name,
            level = DiagLevel.ERROR.name,
            channel = channel.name,
            summary = summary,
            detail = detail,
            cameraModel = cameraModel,
            sessionId = sessionId,
            extras = extras?.toJsonString()
        )

        private fun Map<String, Any?>.toJsonString(): String {
            val sb = StringBuilder("{")
            var first = true
            for ((k, v) in this) {
                if (v == null) continue
                if (!first) sb.append(",")
                first = false
                sb.append("\"$k\":")
                when (v) {
                    is String -> sb.append("\"${v.replace("\"", "\\\"")}\"")
                    is Number, is Boolean -> sb.append(v)
                    else -> sb.append("\"$v\"")
                }
            }
            sb.append("}")
            return sb.toString()
        }
    }

    /** 获取分类枚举 */
    fun categoryEnum(): DiagCategory = DiagCategory.entries.find { it.name == category } ?: DiagCategory.SYSTEM

    /** 获取级别枚举 */
    fun levelEnum(): DiagLevel = DiagLevel.entries.find { it.name == level } ?: DiagLevel.INFO

    /** 获取通道枚举 */
    fun channelEnum(): DiagChannel = DiagChannel.entries.find { it.name == channel } ?: DiagChannel.UNKNOWN

    /** 格式化时间（HH:mm:ss.SSS） */
    fun formatTime(): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        return String.format(
            "%02d:%02d:%02d.%03d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
            cal.get(java.util.Calendar.MILLISECOND)
        )
    }

    /** 格式化日期时间（MM-dd HH:mm:ss） */
    fun formatDateTime(): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        return String.format(
            "%02d-%02d %02d:%02d:%02d",
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }

    /** 一行可读摘要（时间线显示用） */
    fun oneLine(): String {
        val levelEmoji = levelEnum().emoji
        return "$levelEmoji ${formatTime()} | ${channelEnum().label} | $summary"
    }
}
