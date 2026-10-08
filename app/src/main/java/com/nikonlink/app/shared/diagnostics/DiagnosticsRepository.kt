package com.nikonlink.app.shared.diagnostics

import com.nikonlink.app.shared.common.AppEventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 诊断仓库 —— 统一管理诊断事件的记录、查询、导出。
 *
 * 设计目标：
 * - 非阻塞记录：`record*` 方法立即返回，后台写入数据库
 * - 会话追踪：同一次连接/传输的事件共享 sessionId，便于关联分析
 * - 空间可控：自动清理超出上限的旧记录
 * - 双写日志：同时写入 Room 与既有的 AppEventLogger（兼容现有导出）
 */
@Singleton
class DiagnosticsRepository @Inject constructor(
    private val dao: DiagnosticEventDao,
    private val eventLogger: AppEventLogger
) {
    companion object {
        private const val TAG = "Diagnostics"
        private const val CLEANUP_THRESHOLD = DiagnosticEvent.MAX_EVENTS + 200
        private const val CLEANUP_TARGET = DiagnosticEvent.MAX_EVENTS
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    private var currentCameraModel: String? = null

    /** 开始新会话（连接开始时调用），返回会话 ID */
    fun startSession(channel: DiagChannel): String {
        val sid = UUID.randomUUID().toString().take(8)
        currentSessionId = sid
        currentCameraModel = null
        Timber.tag(TAG).d("Session started: $sid ($channel)")
        return sid
    }

    /** 结束当前会话（连接断开时调用） */
    fun endSession() {
        val sid = currentSessionId
        if (sid != null) {
            Timber.tag(TAG).d("Session ended: $sid")
        }
        currentSessionId = null
        currentCameraModel = null
    }

    /** 设置当前相机型号（连接成功后调用） */
    fun setCameraModel(model: String?) {
        currentCameraModel = model?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 获取当前会话 ID */
    fun getSessionId(): String? = currentSessionId

    /** 获取当前相机型号 */
    fun getCameraModel(): String? = currentCameraModel

    // ──────────────────────────────────────────────────────────────────
    // 记录方法（非阻塞，后台写入）
    // ──────────────────────────────────────────────────────────────────

    /** 通用记录方法 */
    fun record(event: DiagnosticEvent) {
        val e = event.copy(
            sessionId = event.sessionId ?: currentSessionId,
            cameraModel = event.cameraModel ?: currentCameraModel
        )
        scope.launch {
            insertAndCleanup(e)
            writeToEventLogger(e)
        }
    }

    /** 记录连接事件 */
    fun recordConnection(
        level: DiagLevel,
        channel: DiagChannel,
        summary: String,
        detail: String? = null,
        extras: Map<String, Any?>? = null
    ) {
        record(
            DiagnosticEvent.connection(
                level = level,
                channel = channel,
                summary = summary,
                detail = detail,
                cameraModel = currentCameraModel,
                sessionId = currentSessionId,
                extras = extras
            )
        )
    }

    /** 记录传输事件 */
    fun recordTransfer(
        level: DiagLevel,
        channel: DiagChannel,
        summary: String,
        detail: String? = null,
        extras: Map<String, Any?>? = null
    ) {
        record(
            DiagnosticEvent.transfer(
                level = level,
                channel = channel,
                summary = summary,
                detail = detail,
                cameraModel = currentCameraModel,
                sessionId = currentSessionId,
                extras = extras
            )
        )
    }

    /** 记录实时取景事件 */
    fun recordLiveView(
        level: DiagLevel,
        channel: DiagChannel,
        summary: String,
        detail: String? = null,
        extras: Map<String, Any?>? = null
    ) {
        record(
            DiagnosticEvent.liveView(
                level = level,
                channel = channel,
                summary = summary,
                detail = detail,
                cameraModel = currentCameraModel,
                sessionId = currentSessionId,
                extras = extras
            )
        )
    }

    /** 记录遥控拍摄事件 */
    fun recordRemote(
        level: DiagLevel,
        channel: DiagChannel,
        summary: String,
        detail: String? = null,
        extras: Map<String, Any?>? = null
    ) {
        record(
            DiagnosticEvent.remote(
                level = level,
                channel = channel,
                summary = summary,
                detail = detail,
                cameraModel = currentCameraModel,
                sessionId = currentSessionId,
                extras = extras
            )
        )
    }

    /** 记录错误事件 */
    fun recordError(
        channel: DiagChannel,
        summary: String,
        detail: String? = null,
        extras: Map<String, Any?>? = null
    ) {
        record(
            DiagnosticEvent.error(
                channel = channel,
                summary = summary,
                detail = detail,
                cameraModel = currentCameraModel,
                sessionId = currentSessionId,
                extras = extras
            )
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // 查询方法
    // ──────────────────────────────────────────────────────────────────

    /** 获取最近 N 条事件 */
    suspend fun getRecent(limit: Int = 200): List<DiagnosticEvent> = dao.getRecent(limit)

    /** 观察最近事件（实时更新） */
    fun observeRecent(limit: Int = 200): Flow<List<DiagnosticEvent>> = dao.observeRecent(limit)

    /** 按分类筛选 */
    suspend fun getByCategory(category: DiagCategory, limit: Int = 200): List<DiagnosticEvent> =
        dao.getByCategory(category.name, limit)

    /** 按级别筛选 */
    suspend fun getByLevel(level: DiagLevel, limit: Int = 200): List<DiagnosticEvent> =
        dao.getByLevel(level.name, limit)

    /** 按通道筛选 */
    suspend fun getByChannel(channel: DiagChannel, limit: Int = 200): List<DiagnosticEvent> =
        dao.getByChannel(channel.name, limit)

    /** 获取最近的错误事件 */
    suspend fun getRecentErrors(limit: Int = 50): List<DiagnosticEvent> = dao.getRecentErrors(limit)

    /** 获取会话的全部事件 */
    suspend fun getSession(sessionId: String): List<DiagnosticEvent> = dao.getBySession(sessionId)

    /** 获取最近一次连接成功的事件（用于显示当前相机） */
    suspend fun getLastConnectionSuccess(): DiagnosticEvent? = dao.getLastConnectionSuccess()

    /** 获取总记录数 */
    suspend fun count(): Int = dao.count()

    // ──────────────────────────────────────────────────────────────────
    // 导出方法
    // ──────────────────────────────────────────────────────────────────

    /** 导出全部事件（按时间正序） */
    suspend fun exportAll(): List<DiagnosticEvent> = dao.exportAll()

    /** 导出指定时间之后的事件 */
    suspend fun exportSince(sinceMs: Long): List<DiagnosticEvent> = dao.exportSince(sinceMs)

    /** 渲染为可读文本（用于导出/显示） */
    suspend fun renderTimeline(limit: Int = 200): String = buildString {
        val events = dao.getRecent(limit)
        if (events.isEmpty()) {
            appendLine("暂无诊断事件记录")
            return@buildString
        }

        appendLine("===== 诊断事件时间线（最近 ${events.size} 条）=====")
        appendLine()

        var lastDate = ""
        for (event in events) {
            val date = formatDate(event.timestamp)
            if (date != lastDate) {
                appendLine("── $date ──")
                lastDate = date
            }
            appendLine(event.oneLine())
            event.detail?.let { appendLine("   └─ $it") }
        }
    }

    /** 清空全部记录 */
    suspend fun clearAll() {
        dao.clearAll()
        Timber.tag(TAG).i("All diagnostic events cleared")
    }

    // ──────────────────────────────────────────────────────────────────
    // 内部方法
    // ──────────────────────────────────────────────────────────────────

    private suspend fun insertAndCleanup(event: DiagnosticEvent) {
        runCatching {
            dao.insert(event)
            val count = dao.count()
            if (count > CLEANUP_THRESHOLD) {
                val toDelete = count - CLEANUP_TARGET
                dao.deleteOldest(toDelete)
                Timber.tag(TAG).d("Cleaned up $toDelete old events")
            }
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "Failed to insert diagnostic event")
        }
    }

    private fun writeToEventLogger(event: DiagnosticEvent) {
        eventLogger.event(
            "diag",
            "cat" to event.category,
            "lvl" to event.level,
            "ch" to event.channel,
            "sum" to event.summary,
            "sid" to event.sessionId,
            "cam" to event.cameraModel
        )
    }

    private fun formatDate(timestamp: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        return String.format(
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }
}
