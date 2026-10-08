package com.nikonlink.app.shared.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nikonlink.app.R
import com.nikonlink.app.databinding.ActivityDiagnosticsBinding
import com.nikonlink.app.shared.ui.NlFeedback
import com.nikonlink.app.shared.ui.glass.NlGlass
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 诊断事件时间线页面。
 *
 * 功能：
 * - 显示最近的诊断事件列表，按时间倒序排列
 * - 支持按分类/级别筛选
 * - 点击事件可查看详情
 * - 支持一键导出诊断包
 * - 支持清空记录
 *
 * 设计原则：
 * - 简洁：列表为主，不做复杂交互
 * - 可读：用户看得懂，不用协议术语
 * - 实用：排查问题时能快速定位
 */
@AndroidEntryPoint
class DiagnosticsActivity : AppCompatActivity() {

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, DiagnosticsActivity::class.java))
        }
    }

    private lateinit var binding: ActivityDiagnosticsBinding

    @Inject
    lateinit var repository: DiagnosticsRepository

    @Inject
    lateinit var bundleExporter: DiagnosticsBundleExporter

    private val adapter = EventAdapter { event -> showEventDetail(event) }

    private var currentFilter: Filter = Filter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFilters()
        setupActions()
        observeEvents()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@DiagnosticsActivity)
            adapter = this@DiagnosticsActivity.adapter
        }
    }

    private fun setupFilters() {
        binding.chipAll.setOnClickListener { applyFilter(Filter.ALL) }
        binding.chipConnection.setOnClickListener { applyFilter(Filter.CONNECTION) }
        binding.chipTransfer.setOnClickListener { applyFilter(Filter.TRANSFER) }
        binding.chipErrors.setOnClickListener { applyFilter(Filter.ERRORS) }

        updateFilterChips()
    }

    private fun setupActions() {
        binding.btnExport.setOnClickListener { exportDiagnostics() }
        binding.btnClear.setOnClickListener { confirmClear() }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repository.observeRecent(500).collectLatest { events ->
                val filtered = filterEvents(events)
                adapter.submitList(filtered)
                updateEmptyState(filtered.isEmpty())
                updateStats(events)
            }
        }
    }

    private fun filterEvents(events: List<DiagnosticEvent>): List<DiagnosticEvent> {
        return when (currentFilter) {
            Filter.ALL -> events
            Filter.CONNECTION -> events.filter { it.category == DiagCategory.CONNECTION.name }
            Filter.TRANSFER -> events.filter { it.category == DiagCategory.TRANSFER.name }
            Filter.ERRORS -> events.filter { it.level == DiagLevel.ERROR.name }
        }
    }

    private fun applyFilter(filter: Filter) {
        currentFilter = filter
        updateFilterChips()
        lifecycleScope.launch {
            val events = repository.getRecent(500)
            val filtered = filterEvents(events)
            adapter.submitList(filtered)
            updateEmptyState(filtered.isEmpty())
        }
    }

    private fun updateFilterChips() {
        binding.chipAll.isChecked = currentFilter == Filter.ALL
        binding.chipConnection.isChecked = currentFilter == Filter.CONNECTION
        binding.chipTransfer.isChecked = currentFilter == Filter.TRANSFER
        binding.chipErrors.isChecked = currentFilter == Filter.ERRORS
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateStats(events: List<DiagnosticEvent>) {
        val errorCount = events.count { it.level == DiagLevel.ERROR.name }
        val lastSession = repository.getSessionId()
        binding.tvStats.text = buildString {
            append("共 ${events.size} 条")
            if (errorCount > 0) append(" · $errorCount 个错误")
            if (lastSession != null) append(" · 会话 $lastSession")
        }
    }

    private fun showEventDetail(event: DiagnosticEvent) {
        val detail = buildString {
            appendLine("时间：${event.formatDateTime()}")
            appendLine("分类：${event.categoryEnum().label}")
            appendLine("级别：${event.levelEnum().label}")
            appendLine("通道：${event.channelEnum().label}")
            appendLine()
            appendLine("摘要：${event.summary}")
            event.detail?.let {
                appendLine()
                appendLine("详情：$it")
            }
            event.cameraModel?.let {
                appendLine()
                appendLine("相机：$it")
            }
            event.sessionId?.let {
                appendLine()
                appendLine("会话：$it")
            }
            event.extras?.let {
                if (it.isNotBlank() && it != "{}") {
                    appendLine()
                    appendLine("扩展：$it")
                }
            }
        }

        NlGlass.dialog(this)
            .setTitle("事件详情")
            .setMessage(detail)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("诊断事件", detail))
                NlFeedback.show(this, "已复制到剪贴板")
            }
            .show()
    }

    private fun exportDiagnostics() {
        lifecycleScope.launch {
            binding.btnExport.isEnabled = false
            binding.btnExport.text = "导出中…"

            runCatching {
                bundleExporter.exportAndShare(this@DiagnosticsActivity)
            }.onFailure { e ->
                NlFeedback.show(this@DiagnosticsActivity, "导出失败：${e.message}")
            }

            binding.btnExport.isEnabled = true
            binding.btnExport.text = "导出诊断包"
        }
    }

    private fun confirmClear() {
        NlGlass.dialog(this)
            .setTitle("清空诊断记录")
            .setMessage("确定要清空全部诊断事件记录吗？此操作不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    repository.clearAll()
                    NlFeedback.show(this@DiagnosticsActivity, "已清空全部记录")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private enum class Filter {
        ALL, CONNECTION, TRANSFER, ERRORS
    }

    /**
     * 事件列表适配器
     */
    private class EventAdapter(
        private val onClick: (DiagnosticEvent) -> Unit
    ) : ListAdapter<DiagnosticEvent, EventAdapter.ViewHolder>(DIFF) {

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<DiagnosticEvent>() {
                override fun areItemsTheSame(old: DiagnosticEvent, new: DiagnosticEvent) = old.id == new.id
                override fun areContentsTheSame(old: DiagnosticEvent, new: DiagnosticEvent) = old == new
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_diagnostic_event, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvTime: TextView = view.findViewById(R.id.tvTime)
            private val tvLevel: TextView = view.findViewById(R.id.tvLevel)
            private val tvChannel: TextView = view.findViewById(R.id.tvChannel)
            private val tvSummary: TextView = view.findViewById(R.id.tvSummary)
            private val tvDetail: TextView = view.findViewById(R.id.tvDetail)

            fun bind(event: DiagnosticEvent) {
                tvTime.text = event.formatTime()
                tvLevel.text = event.levelEnum().emoji
                tvChannel.text = event.channelEnum().label
                tvSummary.text = event.summary
                tvDetail.text = event.detail
                tvDetail.visibility = if (event.detail.isNullOrBlank()) View.GONE else View.VISIBLE

                val levelColor = when (event.levelEnum()) {
                    DiagLevel.ERROR -> R.color.error
                    DiagLevel.WARNING -> R.color.warning
                    DiagLevel.SUCCESS -> R.color.success
                    DiagLevel.INFO -> R.color.text_secondary
                }
                tvSummary.setTextColor(ContextCompat.getColor(itemView.context, levelColor))

                itemView.setOnClickListener { onClick(event) }
            }
        }
    }
}
