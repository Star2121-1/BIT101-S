package cn.bit101.android.config.seat.base

import cn.bit101.android.config.common.SettingItem

/**
 * 座位预约任务的持久化存储。
 *
 * 任务需要在 ViewModel 生命周期之外被读写（例如进程重启后恢复、或由后台任务续跑），
 * 因此必须落盘。存储内容为**序列化后的任务列表 JSON**，具体结构由座位模块定义，
 * 这里只负责持久化，不关心格式。
 */
interface SeatTaskStore {

    /** 序列化后的任务列表。空串表示没有任务。 */
    val tasks: SettingItem<String>
}
