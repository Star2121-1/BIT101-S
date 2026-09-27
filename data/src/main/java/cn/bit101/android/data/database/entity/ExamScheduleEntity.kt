package cn.bit101.android.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import cn.bit101.api.model.common.ExamInfo
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 考试安排
 */
@Entity(tableName = "exam_schedule")
data class ExamScheduleEntity (
    @PrimaryKey(autoGenerate = true)
    val id: Int,

    /**
     * 学期
     */
    val term: String,

    /**
     * 课程名
     */
    val name: String,

    /**
     * 课程号
     */
    val courseId: String,

    /**
     * 授课教师
     */
    val teacher: String,

    /**
     * 教室
     */
    val classroom: String,

    /**
     * 考试日期
     */
    val date: LocalDate,

    /**
     * 考试开始时间
     */
    val beginTime: LocalTime,

    /**
     * 考试结束时间
     */
    val endTime: LocalTime,

    /**
     * 分散考试 / 集中考试等
     */
    val examMode: String,

    /**
     * 座位号
     */
    val seatId: String,
)

/**
 * 考试安排转换为数据库实体
 */
internal fun ExamInfo.toEntity(): ExamScheduleEntity {
    return ExamScheduleEntity(
        0,
        this.termCode.orEmpty(),
        this.courseCode
            ?.takeWhile { it != ']' }
            ?.takeLastWhile { it != '[' }
            .orEmpty(),
        this.kch.orEmpty(),
        this.teacherName.orEmpty(),
        this.location.orEmpty(),
        LocalDate.parse(this.date.takeWhile { it != ' ' }, DateTimeFormatter.ofPattern("yyyy-MM-dd")),
        LocalTime.parse(this.time
            .dropLastWhile { it != '-' }
            .dropLast(1)
            .dropWhile { it != ' ' }
            .drop(1),
            DateTimeFormatter.ofPattern("HH:mm")),
        LocalTime.parse(this.time
            .dropLastWhile { it != '(' }
            .dropLast(1)
            .takeLastWhile { it != '-' },
            DateTimeFormatter.ofPattern("HH:mm")
        ),
        this.ksmc.orEmpty(),
        this.seatId.orEmpty(),
    )
}

/**
 * 考试开始的绝对时刻（日期 + 开始时间）。
 *
 * ⚠️ 放在这里、而不是各消费方各写一遍：**提醒排期**（`features:notify`）与
 * **考试列表**（`features:schedule`）都要用它，而这两个模块互相不依赖
 * （依赖方向见 `docs/notify.md`）—— 各自实现一遍迟早会在某次边界处理上走偏。
 * 扩展属性不参与 Room 建表（没有 backing field），改这里不影响数据库结构。
 */
val ExamScheduleEntity.startAt: LocalDateTime get() = LocalDateTime.of(date, beginTime)

/** 考试结束的绝对时刻（日期 + 结束时间）。 */
val ExamScheduleEntity.endAt: LocalDateTime get() = LocalDateTime.of(date, endTime)