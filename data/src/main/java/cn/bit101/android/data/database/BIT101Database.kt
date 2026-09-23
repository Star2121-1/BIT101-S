package cn.bit101.android.data.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cn.bit101.android.data.database.dao.CourseOverlayDao
import cn.bit101.android.data.database.dao.CoursesDao
import cn.bit101.android.data.database.dao.CustomScheduleDao
import cn.bit101.android.data.database.dao.DDLScheduleDao
import cn.bit101.android.data.database.dao.ExamsDao
import cn.bit101.android.data.database.entity.CourseOverlayEntity
import cn.bit101.android.data.database.entity.CourseScheduleEntity
import cn.bit101.android.data.database.entity.CustomScheduleEntity
import cn.bit101.android.data.database.entity.DDLScheduleEntity
import cn.bit101.android.data.database.entity.ExamScheduleEntity

/**
 * @author flwfdd
 * @date 2023/3/31 17:49
 * @description _(:з」∠)_
 */

@Database(
    entities = [
        CourseScheduleEntity::class,
        ExamScheduleEntity::class,
        CustomScheduleEntity::class,
        DDLScheduleEntity::class,
        /**
         * 手动修改课程表的覆盖层（v3 新增）。
         *
         * ⚠️ 单独一张表是**必须**的：`course_schedule` 在同步时被全量覆盖
         * （`CoursesRepo.saveCourses()` 先删光再插），手动改在那儿活不过一次同步。
         */
        CourseOverlayEntity::class,
    ],
    version = 3,
//    exportSchema = false  // 不知道为什么原来要禁用, developer.android.com 似乎推荐保留数据库架构历史, 而且启用的话数据库迁移会方便很多, 所以我注释掉了
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        // 2 → 3：只新增了一张表，Room 能自动迁移（schema 已导出 1/2/3.json）
        AutoMigration(from = 2, to = 3),
    ]
)
@TypeConverters(Converters::class)
internal abstract class BIT101Database : RoomDatabase() {
    abstract fun coursesDao(): CoursesDao
    abstract fun examsDao(): ExamsDao
    abstract fun customScheduleDao(): CustomScheduleDao
    abstract fun DDLScheduleDao(): DDLScheduleDao
    abstract fun courseOverlayDao(): CourseOverlayDao
}
