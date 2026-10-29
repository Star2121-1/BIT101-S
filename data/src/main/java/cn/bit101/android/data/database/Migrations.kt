package cn.bit101.android.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移脚本。
 *
 * ## 为什么手写而不是 AutoMigration
 *
 * 3 → 4 是**列类型变更**（`INTEGER` → `REAL`），SQLite 不支持 `ALTER COLUMN`，
 * 必须「建新表 → 搬数据 → 删旧表 → 改名」。
 * 上游 BIT101-Android 1.4.4 也改了同样两个字段，但他们先试了 AutoMigration，
 * 在老设备上没生效，1.4.6 才补 `MigrationsTest` 和手写迁移——那次的直接后果是
 * 老用户升级后数据异常。我们一开始就手写 + 写测试，不走那条路。
 *
 * ## 关键约束
 *
 * 1. **索引要重建**：`DROP TABLE` 会连带删索引，上游第一次迁移就漏了这条
 * 2. **`INSERT ... SELECT` 用列名**，不要 `SELECT *`，避免列顺序错位
 * 3. 两张表都要改：`course_schedule` 与 `course_overlay` 都有 `credit`
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        recreateCourseSchedule(db)
        recreateCourseOverlay(db)
    }
}

/**
 * `course_schedule`：`credit` INTEGER → REAL。
 *
 * 用 `CAST(credit AS REAL)` 搬值，整数会变成 `3.0`，与新类型一致。
 */
private fun recreateCourseSchedule(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `course_schedule_new` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`term` TEXT NOT NULL, `name` TEXT NOT NULL, `teacher` TEXT NOT NULL, " +
            "`classroom` TEXT NOT NULL, `description` TEXT NOT NULL, `weeks` TEXT NOT NULL, " +
            "`weekday` INTEGER NOT NULL, `start_section` INTEGER NOT NULL, " +
            "`end_section` INTEGER NOT NULL, `campus` TEXT NOT NULL, `number` TEXT NOT NULL, " +
            "`credit` REAL NOT NULL, `hour` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
            "`category` TEXT NOT NULL, `department` TEXT NOT NULL)"
    )
    db.execSQL(
        "INSERT INTO `course_schedule_new` (" +
            "`id`, `term`, `name`, `teacher`, `classroom`, `description`, `weeks`, " +
            "`weekday`, `start_section`, `end_section`, `campus`, `number`, " +
            "`credit`, `hour`, `type`, `category`, `department`) " +
            "SELECT `id`, `term`, `name`, `teacher`, `classroom`, `description`, `weeks`, " +
            "`weekday`, `start_section`, `end_section`, `campus`, `number`, " +
            "CAST(`credit` AS REAL), `hour`, `type`, `category`, `department` " +
            "FROM `course_schedule`"
    )
    db.execSQL("DROP TABLE `course_schedule`")
    db.execSQL("ALTER TABLE `course_schedule_new` RENAME TO `course_schedule`")
    // 本表在 v3 没有索引（schema 3.json 可查），因此无需重建
}

/**
 * `course_overlay`：`credit` INTEGER → REAL，**并重建它的复合索引**。
 */
private fun recreateCourseOverlay(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `course_overlay_new` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
            "`term` TEXT NOT NULL, `anchorNumber` TEXT NOT NULL, " +
            "`anchorWeekday` INTEGER NOT NULL, `anchorStartSection` INTEGER NOT NULL, " +
            "`number` TEXT NOT NULL, `name` TEXT NOT NULL, `teacher` TEXT NOT NULL, " +
            "`classroom` TEXT NOT NULL, `description` TEXT NOT NULL, `weeks` TEXT NOT NULL, " +
            "`weekday` INTEGER NOT NULL, `startSection` INTEGER NOT NULL, " +
            "`endSection` INTEGER NOT NULL, `campus` TEXT NOT NULL, `credit` REAL NOT NULL, " +
            "`hour` INTEGER NOT NULL, `type` TEXT NOT NULL, `category` TEXT NOT NULL, " +
            "`department` TEXT NOT NULL, `updatedAtMillis` INTEGER NOT NULL)"
    )
    db.execSQL(
        "INSERT INTO `course_overlay_new` (" +
            "`id`, `kind`, `term`, `anchorNumber`, `anchorWeekday`, `anchorStartSection`, " +
            "`number`, `name`, `teacher`, `classroom`, `description`, `weeks`, " +
            "`weekday`, `startSection`, `endSection`, `campus`, `credit`, `hour`, " +
            "`type`, `category`, `department`, `updatedAtMillis`) " +
            "SELECT `id`, `kind`, `term`, `anchorNumber`, `anchorWeekday`, `anchorStartSection`, " +
            "`number`, `name`, `teacher`, `classroom`, `description`, `weeks`, " +
            "`weekday`, `startSection`, `endSection`, `campus`, CAST(`credit` AS REAL), " +
            "`hour`, `type`, `category`, `department`, `updatedAtMillis` " +
            "FROM `course_overlay`"
    )
    db.execSQL("DROP TABLE `course_overlay`")
    db.execSQL("ALTER TABLE `course_overlay_new` RENAME TO `course_overlay`")
    // ⚠️ DROP TABLE 连带删掉了索引，必须重建（上游那次迁移就漏了这条）
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS " +
            "`index_course_overlay_term_anchorNumber_anchorWeekday_anchorStartSection` " +
            "ON `course_overlay` (`term`, `anchorNumber`, `anchorWeekday`, `anchorStartSection`)"
    )
}
