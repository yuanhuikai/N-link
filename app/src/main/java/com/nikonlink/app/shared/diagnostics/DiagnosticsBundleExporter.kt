package com.nikonlink.app.shared.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.nikonlink.app.BuildConfig
import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.device.RomDetector
import com.nikonlink.app.shared.metrics.BaselineMetrics
import com.nikonlink.app.shared.privacy.DiagnosticsDisclosure
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 诊断包导出器 —— 一键导出完整诊断信息。
 *
 * 导出内容：
 * - 设备信息（型号、ROM、系统版本、App 版本）
 * - 相机信息（最近连接的相机型号）
 * - 诊断事件时间线（结构化日志）
 * - 连接/传输/监看基线指标
 * - 原始事件日志
 *
 * 隐私保护：
 * - 不导出 WiFi 密码、GPS、相机序列号
 * - 文件开头附带字段声明，用户知道导出了什么
 */
@Singleton
class DiagnosticsBundleExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val eventLogger: AppEventLogger,
    private val baselineMetrics: BaselineMetrics,
    private val settings: AppSettings
) {
    companion object {
        private const val TAG = "DiagExport"
        private const val EXPORT_DIR = "diagnostics"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * 生成诊断包并通过系统分享。
     */
    suspend fun exportAndShare(context: Context) {
        val file = export()
        if (file == null) {
            Timber.tag(TAG).w("Export failed: file is null")
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "N-Link 诊断报告 ${dateFormat.format(Date())}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "分享诊断报告"))
    }

    /**
     * 生成诊断包文件。
     */
    suspend fun export(): File? = withContext(Dispatchers.IO) {
        runCatching {
            val exportDir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
            val fileName = "n-link_diag_${dateFormat.format(Date())}.txt"
            val file = File(exportDir, fileName)

            file.bufferedWriter().use { writer ->
                // 1. 字段声明
                writer.write(DiagnosticsDisclosure.text(settings.dataHandoffs()))
                writer.write("\n")

                // 2. 设备与应用信息
                writer.write(renderDeviceInfo())
                writer.write("\n")

                // 3. 相机信息
                writer.write(renderCameraInfo())
                writer.write("\n")

                // 4. 诊断事件时间线
                writer.write(renderDiagnosticTimeline())
                writer.write("\n")

                // 5. 基线指标
                writer.write(baselineMetrics.render())
                writer.write("\n")

                // 6. 原始事件日志
                eventLogger.packLogsForExport()?.let { logFile ->
                    writer.write("===== 原始事件日志 =====\n")
                    logFile.inputStream().bufferedReader().use { reader ->
                        reader.copyTo(writer)
                    }
                }
            }

            Timber.tag(TAG).i("Diagnostic bundle exported: ${file.absolutePath}")
            file
        }.onFailure { e ->
            Timber.tag(TAG).e(e, "Export failed")
        }.getOrNull()
    }

    private fun renderDeviceInfo(): String = buildString {
        appendLine("===== 设备与应用信息 =====")
        appendLine("导出时间：${dateTimeFormat.format(Date())}")
        appendLine()
        appendLine("【设备】")
        appendLine("  型号：${Build.MODEL}")
        appendLine("  厂商：${Build.MANUFACTURER}")
        appendLine("  ROM：${RomDetector.family}")
        appendLine("  系统版本：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("  设备名称：${Build.DEVICE}")
        appendLine()
        appendLine("【应用】")
        appendLine("  版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("  包名：${BuildConfig.APPLICATION_ID}")
        appendLine("  构建类型：${if (BuildConfig.DEBUG) "调试版" else "正式版"}")
        appendLine()
    }

    private suspend fun renderCameraInfo(): String = buildString {
        appendLine("===== 相机信息 =====")

        val lastConnection = diagnosticsRepository.getLastConnectionSuccess()
        val cameraModel = lastConnection?.cameraModel
            ?: diagnosticsRepository.getCameraModel()

        if (cameraModel != null) {
            appendLine("最近连接的相机：$cameraModel")
            lastConnection?.let {
                appendLine("连接时间：${it.formatDateTime()}")
                appendLine("连接通道：${it.channelEnum().label}")
            }
        } else {
            appendLine("（本次启动未连接相机）")
        }
        appendLine()
    }

    private suspend fun renderDiagnosticTimeline(): String = buildString {
        appendLine("===== 诊断事件时间线 =====")
        appendLine()

        val events = diagnosticsRepository.exportAll()
        if (events.isEmpty()) {
            appendLine("（无诊断事件记录）")
            appendLine()
            return@buildString
        }

        appendLine("共 ${events.size} 条事件")
        appendLine()

        // 统计概览
        val byCategory = events.groupBy { it.category }
        val byLevel = events.groupBy { it.level }
        val errorCount = byLevel[DiagLevel.ERROR.name]?.size ?: 0

        appendLine("【分类分布】")
        byCategory.forEach { (cat, list) ->
            val catEnum = DiagCategory.entries.find { it.name == cat }
            appendLine("  ${catEnum?.label ?: cat}：${list.size} 条")
        }
        appendLine()

        appendLine("【级别分布】")
        byLevel.forEach { (lvl, list) ->
            val lvlEnum = DiagLevel.entries.find { it.name == lvl }
            appendLine("  ${lvlEnum?.label ?: lvl}：${list.size} 条")
        }
        appendLine()

        if (errorCount > 0) {
            appendLine("【错误事件摘要】")
            events.filter { it.level == DiagLevel.ERROR.name }.take(10).forEach { event ->
                appendLine("  ${event.formatDateTime()} | ${event.channelEnum().label} | ${event.summary}")
                event.detail?.let { appendLine("    └─ $it") }
            }
            if (errorCount > 10) {
                appendLine("  … 还有 ${errorCount - 10} 个错误")
            }
            appendLine()
        }

        appendLine("【完整时间线】（按时间正序）")
        var lastDate = ""
        for (event in events) {
            val date = formatDate(event.timestamp)
            if (date != lastDate) {
                appendLine()
                appendLine("── $date ──")
                lastDate = date
            }
            appendLine("${event.formatTime()} ${event.levelEnum().emoji} [${event.channelEnum().label}] ${event.summary}")
            event.detail?.let { appendLine("  └─ $it") }
        }
        appendLine()
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
