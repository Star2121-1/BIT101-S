package cn.bit101.android.features.seat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.bit101.android.features.seat.api.SeatApi
import cn.bit101.android.features.seat.api.SeatTaskRepository
import cn.bit101.android.features.seat.model.ReservationTask
import cn.bit101.android.features.seat.model.SeatNumberComparator
import cn.bit101.android.features.seat.model.SeatStatus
import cn.bit101.android.features.seat.model.TaskMode
import cn.bit101.android.features.seat.model.TaskStatus
import cn.bit101.android.features.seat.model.seatNumberEquals
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
        private const val CHANNEL_RESULT_ID = "seat_result"
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
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ 限制应用在后台启动前台服务，超限时抛
                // ForegroundServiceStartNotAllowedException。这里不让它冒泡成崩溃：
                // 任务本身仍在仓储里，用户回到前台时会再次尝试拉起。
                SeatLog.w(TAG, "startForegroundService rejected: ${e.javaClass.simpleName} ${e.message}")
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
        (jobs.keys - activeIds).forEach { id ->
            jobs.remove(id)?.cancel()
            // 该任务刚离开执行集合：若为成功 / 失败则单独通知一条。
            // 恰好在 jobs 里才通知 —— 服务重建后旧任务从未被本实例执行过，不会误报。
            tasks.find { it.id == id }
                ?.takeIf { it.status == TaskStatus.SUCCESS || it.status == TaskStatus.FAILED }
                ?.let { notifyResult(it) }
        }

        refreshNotification(active.size)
        if (active.isEmpty()) {
            SeatLog.d(TAG, "no active task left, stopping service")
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
                val available = seats.filter { it.status == SeatStatus.AVAILABLE }
                    .sortedWith(compareBy(SeatNumberComparator) { it.no })
                // 优先预约：有偏好清单就按优先级逐个试；清单为空 = 不限，取最早空出的座位
                val byPreference = task.preferredSeats.firstNotNullOfOrNull { wanted ->
                    available.firstOrNull { seatNumberEquals(it.no, wanted) }
                }
                byPreference ?: if (task.preferredSeats.isEmpty()) {
                    available.firstOrNull { seatNumberEquals(it.no, task.seatNo) } ?: available.firstOrNull()
                } else {
                    null
                }
            } else {
                // 监控预约：只盯指定座位。座位号做补零容错 ——
                // 服务端返回 "001" 而用户常填 "1"，直接比较会永远匹配不上
                seats.firstOrNull { seatNumberEquals(it.no, task.seatNo) }
            }

            if (target == null) {
                repository.updateStatus(
                    task.id, TaskStatus.RUNNING,
                    if (mode == TaskMode.PREFER) "暂无空闲座位，等待中…" else "未找到座位，继续监控…"
                )
                delay(interval)
                continue
            }
            // 该座位已被预约：可能是我自己订到的（服务端状态不区分归属），
            // 用「我的预约」核对一次，是本人就直接算成功，避免重复下单
            if (target.status == SeatStatus.RESERVED || target.status == SeatStatus.MINE) {
                val isMine = seatApi.getMyReservations().getOrNull()
                    ?.any { it.isActive && it.seatId == target.id } == true
                if (isMine) {
                    repository.updateStatus(task.id, TaskStatus.SUCCESS, "该座位已是你的预约（${target.no}）")
                    return
                }
            }
            if (target.status != SeatStatus.AVAILABLE) {
                repository.updateStatus(task.id, TaskStatus.RUNNING, "座位被占，继续监控…")
                delay(interval)
                continue
            }

            repository.updateStatus(task.id, TaskStatus.RUNNING, "发现空闲座位 ${target.no}，正在预约…")
            // 记录「尝试了一次」：这是后台服务还活着的直接证据（UI 上显示尝试次数与最近尝试时间）。
            // 放在 confirmSeat 之前 —— 只要真的发出了预约请求就算一次，无论成败。
            repository.markAttempt(task.id)
            val confirm = seatApi.confirmSeat(target.id, params.segmentId)
            if (confirm.isSuccess && confirm.getOrNull() == true) {
                repository.updateStatus(task.id, TaskStatus.SUCCESS, "预约成功，座位 ${target.no}")
                return
            }
            if (handleAuthError(confirm.exceptionOrNull())) return
            // 带上服务端原因：只写「预约失败」用户无从判断——可能是尚未到 6:00 开抢时间，
            // 也可能是座位被别人抢走或当天取消次数用尽，几种情况的处理方式完全不同。
            val reason = confirm.exceptionOrNull()?.message.orEmpty()
                .replace("预约失败：", "").replace("预约失败: ", "").trim()
                .let { if (it.length > 24) it.take(24) + "…" else it }
            repository.updateStatus(
                task.id, TaskStatus.RUNNING,
                if (reason.isEmpty()) "预约失败，继续尝试" else "预约失败（$reason），继续尝试"
            )
            delay(interval)
        }
        repository.updateStatus(task.id, TaskStatus.FAILED, "已超过最长运行时长（2 小时），任务自动停止")
    }

    /** 认证失效：清空会话并终止所有任务。返回 true 表示调用方应立即返回。 */
    private fun handleAuthError(e: Throwable?): Boolean {
        if (e?.message != SeatApi.TOKEN_EXPIRED && e?.cause?.message != SeatApi.TOKEN_EXPIRED) return false
        seatApi.token = ""
        repository.stopAll("登录已失效，请重新登录")
        return true
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "座位监控", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "座位预约监控任务运行期间显示"
                }
            )
        }
        // 结果通知单独一个渠道：轮询进度要安静，抢座结果要能提醒到人，两者重要性不同
        if (manager.getNotificationChannel(CHANNEL_RESULT_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT_ID, "预约结果", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "预约成功或任务停止时提醒"
                }
            )
        }
    }

    private fun buildNotification(activeCount: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("正在监控座位")
            .setContentText(if (activeCount > 0) "$activeCount 个任务进行中" else "正在准备…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** 任务结束（成功 / 失败）时发一条可清除的通知。 */
    private fun notifyResult(task: ReservationTask) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val success = task.status == TaskStatus.SUCCESS
        val where = listOf(task.areaName, task.seatNo).filter { it.isNotBlank() }.joinToString(" ")
        val builder = NotificationCompat.Builder(this, CHANNEL_RESULT_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (success) "座位预约成功" else "预约任务已停止")
            .setContentText(task.message.ifBlank { where.ifBlank { task.reserveDate } })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOfNotNull(
                        where.takeIf { it.isNotBlank() },
                        task.reserveDate.takeIf { it.isNotBlank() },
                        task.message.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                )
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // 点击回到应用（不依赖具体 Activity 类名）
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, 0, launchIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        manager.notify(task.id.hashCode(), builder.build())
    }

    private fun refreshNotification(activeCount: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(activeCount))
    }
}
