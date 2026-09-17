package cn.bit101.android.features.seat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.bit101.android.features.seat.api.SeatApi
import cn.bit101.android.features.seat.api.SeatTaskRepository
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.SeatStatus
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.model.TaskStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 座位监控前台服务。
 *
 * 为什么用前台服务而不是 WorkManager：WorkManager 是为**可延迟任务**设计的，
 * Doze / App Standby 会把执行推迟到分钟级，10s 级的轮询抢座会直接失效。
 * 前台服务可以持续执行，代价是一条常驻通知（也正好让用户知道正在后台抢座）。
 *
 * 服务**由仓储的任务流驱动**：ViewModel 只负责往仓储里加/取消任务，
 * 这里负责实际轮询，因此退到后台、锁屏乃至进程被杀后重建都能继续。
 */
@AndroidEntryPoint
class SeatMonitorService : Service() {

    companion object {
        private const val TAG = "SeatMonitorService"
        private const val CHANNEL_ID = "seat_monitor"
        private const val NOTIFICATION_ID = 0x5EA7
        private const val ACTION_START = "cn.bit101.android.features.seat.action.START"

        /** 单个任务的最长运行时长：超过后自动停止，避免忘记取消导致无限轮询。 */
        private const val MAX_TASK_DURATION_MS = 2 * 60 * 60 * 1000L
        /** 轮询退避上限（5 分钟）。 */
        private const val MAX_POLL_INTERVAL_MS = 5 * 60 * 1000L
        /** 监控预约的基准轮询间隔。 */
        private const val MONITOR_INTERVAL_MS = 10_000L
        /** 优先预约的基准轮询间隔。 */
        private const val PREFER_INTERVAL_MS = 5_000L

        /** 启动监控服务。无副作用，可重复调用。 */
        fun start(context: Context) {
            val intent = Intent(context, SeatMonitorService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @Inject lateinit var seatApi: SeatApi
    @Inject lateinit var repository: SeatTaskRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))
        scope.launch {
            repository.loadOnce()
            repository.tasks.collect { syncJobs(it) }
        }
    }

    /** START_STICKY：进程被杀后由系统重建，结合持久化的任务即可自动续跑。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 任务调度 ----------

    /** 按当前任务列表增删执行协程；没有活跃任务时自行停止。 */
    private fun syncJobs(tasks: List<ReservationTask>) {
        val active = tasks.filter { it.status == TaskStatus.RUNNING || it.status == TaskStatus.IDLE }
        val activeIds = active.map { it.id }.toSet()

        active.forEach { task ->
            if (jobs[task.id] == null) {
                jobs[task.id] = scope.launch { runTask(task) }
            }
        }
        (jobs.keys - activeIds).forEach { id -> jobs.remove(id)?.cancel() }

        refreshNotification(active.size)
        if (active.isEmpty()) {
            Log.d(TAG, "no active task left, stopping service")
            stopSelf()
        }
    }

    private data class SegmentParams(val segmentId: String, val startTime: String, val endTime: String)

    /** 解析任务对应日期的时段参数；拉取失败返回 null。 */
    private suspend fun resolveSegment(areaId: String, day: String): SegmentParams? {
        val r = seatApi.getSeatDates(buildId = areaId)
        if (r.isFailure) { handleAuthError(r.exceptionOrNull()); return null }
        val dates = r.getOrThrow()
        val seg = dates.firstOrNull { it.day == day } ?: dates.firstOrNull() ?: return null
        fun String.usable() = takeIf { it.isNotBlank() && it != "null" }
        return SegmentParams(
            segmentId = seg.segmentId.usable() ?: "1",
            startTime = seg.start.usable() ?: "08:00",
            endTime = seg.end.usable() ?: "22:30"
        )
    }

    /** 指数退避：连续失败时每次翻倍，封顶 MAX_POLL_INTERVAL_MS。 */
    private fun backoff(current: Long): Long = minOf(current * 2, MAX_POLL_INTERVAL_MS)

    private suspend fun runTask(task: ReservationTask) {
        val mode = task.mode
        val baseInterval = if (mode == TaskMode.PREFER) PREFER_INTERVAL_MS else MONITOR_INTERVAL_MS

        val params = resolveSegment(task.areaId, task.reserveDate) ?: run {
            repository.updateStatus(task.id, TaskStatus.FAILED, "获取时段失败")
            return
        }

        val deadline = System.currentTimeMillis() + MAX_TASK_DURATION_MS
        var interval = baseInterval

        while (System.currentTimeMillis() < deadline) {
            val result = seatApi.getSeats(task.areaId, params.segmentId, task.reserveDate, params.startTime, params.endTime)
            if (result.isFailure) {
                if (handleAuthError(result.exceptionOrNull())) return
                interval = backoff(interval)
                repository.updateStatus(task.id, TaskStatus.RUNNING, "查询失败，${interval / 1000}s 后重试")
                delay(interval)
                continue
            }
            interval = baseInterval
            val seats = result.getOrNull().orEmpty()

            val target = if (mode == TaskMode.PREFER) {
                // 优先预约：区域内最早可用的座位；若指定座位号恰好可用则优先它
                val available = seats.filter { it.status == SeatStatus.AVAILABLE }.sortedBy { it.no }
                available.find { it.no == task.seatNo } ?: available.firstOrNull()
            } else {
                // 监控预约：只盯指定座位
                seats.find { it.no == task.seatNo }
            }

            if (target == null) {
                repository.updateStatus(
                    task.id, TaskStatus.RUNNING,
                    if (mode == TaskMode.PREFER) "暂无空闲座位，等待中…" else "未找到座位，继续监控…"
                )
                delay(interval)
                continue
            }
            if (target.status != SeatStatus.AVAILABLE) {
                repository.updateStatus(task.id, TaskStatus.RUNNING, "座位被占，继续监控…")
                delay(interval)
                continue
            }

            repository.updateStatus(task.id, TaskStatus.RUNNING, "发现空闲座位 ${target.no}，正在预约…")
            val confirm = seatApi.confirmSeat(target.id, params.segmentId)
            if (confirm.isSuccess && confirm.getOrNull() == true) {
                repository.updateStatus(task.id, TaskStatus.SUCCESS, "预约成功，座位 ${target.no}")
                return
            }
            if (handleAuthError(confirm.exceptionOrNull())) return
            repository.updateStatus(task.id, TaskStatus.RUNNING, "预约失败，继续尝试")
            delay(interval)
        }
        repository.updateStatus(task.id, TaskStatus.FAILED, "已超过最长运行时长（2 小时），任务自动停止")
    }

    /** 认证失效：清空会话并终止所有任务。返回 true 表示调用方应立即返回。 */
    private fun handleAuthError(e: Throwable?): Boolean {
        if (e?.message != "TOKEN_EXPIRED" && e?.cause?.message != "TOKEN_EXPIRED") return false
        seatApi.token = ""
        repository.stopAll("登录已失效，请重新登录")
        return true
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "座位监控", NotificationManager.IMPORTANCE_LOW).apply {
                description = "座位预约监控任务运行期间显示"
            }
        )
    }

    private fun buildNotification(activeCount: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("正在监控座位")
            .setContentText(if (activeCount > 0) "$activeCount 个任务进行中" else "正在准备…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun refreshNotification(activeCount: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(activeCount))
    }
}
