package com.nikonlink.app.shared.diagnostics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 诊断事件 DAO。
 *
 * 设计原则：
 * - 写入快：插入后立即返回，不阻塞主线程
 * - 查询灵活：支持按分类/级别/时间范围筛选
 * - 空间可控：自动清理超出上限的旧记录
 */
@Dao
interface DiagnosticEventDao {

    /** 插入单条事件 */
    @Insert
    suspend fun insert(event: DiagnosticEvent): Long

    /** 批量插入 */
    @Insert
    suspend fun insertAll(events: List<DiagnosticEvent>)

    /** 获取最近 N 条事件（时间线展示用） */
    @Query("SELECT * FROM diagnostic_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<DiagnosticEvent>

    /** 观察最近事件（实时更新 UI） */
    @Query("SELECT * FROM diagnostic_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<DiagnosticEvent>>

    /** 按分类筛选 */
    @Query("SELECT * FROM diagnostic_events WHERE category = :category ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByCategory(category: String, limit: Int = 200): List<DiagnosticEvent>

    /** 按级别筛选（如只看错误） */
    @Query("SELECT * FROM diagnostic_events WHERE level = :level ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByLevel(level: String, limit: Int = 200): List<DiagnosticEvent>

    /** 按通道筛选 */
    @Query("SELECT * FROM diagnostic_events WHERE channel = :channel ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByChannel(channel: String, limit: Int = 200): List<DiagnosticEvent>

    /** 按会话 ID 获取（追踪一次连接的全部事件） */
    @Query("SELECT * FROM diagnostic_events WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: String): List<DiagnosticEvent>

    /** 按时间范围查询 */
    @Query("SELECT * FROM diagnostic_events WHERE timestamp BETWEEN :startMs AND :endMs ORDER BY timestamp DESC")
    suspend fun getByTimeRange(startMs: Long, endMs: Long): List<DiagnosticEvent>

    /** 获取总记录数 */
    @Query("SELECT COUNT(*) FROM diagnostic_events")
    suspend fun count(): Int

    /** 删除最旧的 N 条记录 */
    @Query("DELETE FROM diagnostic_events WHERE id IN (SELECT id FROM diagnostic_events ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    /** 清空全部 */
    @Query("DELETE FROM diagnostic_events")
    suspend fun clearAll()

    /** 删除指定天数之前的记录 */
    @Query("DELETE FROM diagnostic_events WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    /** 获取最近一条连接成功事件（用于获取当前相机型号） */
    @Query(
        "SELECT * FROM diagnostic_events " +
            "WHERE category = 'CONNECTION' AND level = 'SUCCESS' " +
            "ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun getLastConnectionSuccess(): DiagnosticEvent?

    /** 获取最近的错误事件 */
    @Query("SELECT * FROM diagnostic_events WHERE level = 'ERROR' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentErrors(limit: Int = 50): List<DiagnosticEvent>

    /** 导出用：获取指定时间范围内的全部事件（按时间正序） */
    @Query("SELECT * FROM diagnostic_events WHERE timestamp >= :sinceMs ORDER BY timestamp ASC")
    suspend fun exportSince(sinceMs: Long): List<DiagnosticEvent>

    /** 导出用：获取全部事件（按时间正序） */
    @Query("SELECT * FROM diagnostic_events ORDER BY timestamp ASC")
    suspend fun exportAll(): List<DiagnosticEvent>
}
