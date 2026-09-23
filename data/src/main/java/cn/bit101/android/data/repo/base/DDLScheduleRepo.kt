package cn.bit101.android.data.repo.base

import cn.bit101.android.data.database.entity.DDLScheduleEntity
import cn.bit101.api.model.http.school.GetCalendarDataModel
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

interface DDLScheduleRepo {

    /**
     * 从网络获取乐学 DDL 导出地址
     */
    suspend fun getCalendarUrl(): String?

    /**
     * 从网络获取 DDL
     */
    suspend fun getCalendarFromNet(url: String): List<GetCalendarDataModel.CalendarEvent>

    /**
     * 从本地获取 DDL
     */
    suspend fun getCalendarFromLocal(uids: List<String>): List<DDLScheduleEntity>

    /**
     * 向数据库中插入 DDL
     */
    suspend fun insertDDL(ddl: DDLScheduleEntity)

    /**
     * 更新数据库中的 DDL
     */
    suspend fun updateDDL(ddl: DDLScheduleEntity)

    /**
     * 删除数据库中的 DDL
     */
    suspend fun deleteDDL(ddl: DDLScheduleEntity)

    /**
     * 从数据库中获取特定时间之后的 DDL
     */
    fun getFutureDDL(time: LocalDateTime): Flow<List<DDLScheduleEntity>>

    /**
     * 根据 uid 从数据库中获取 DDL
     */
    /**
     * **全部** DDL（不过滤时间）。
     *
     * ⚠️ 与 [getFutureDDL] 的区别就是「不筛时间」：桌面组件要显示所有条目 ——
     * 包括很久以后才截止的、以及已经过期的（过期的标「已过期」而不是消失）。
     */
    fun getAllDDL(): Flow<List<DDLScheduleEntity>>

    suspend fun getDDLByUIDs(
        uids: List<String>
    ): List<DDLScheduleEntity>
}