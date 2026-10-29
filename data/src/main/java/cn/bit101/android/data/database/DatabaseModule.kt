package cn.bit101.android.data.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton


private const val FILE_NAME = "bit101.db"


@Module
@InstallIn(SingletonComponent::class)
internal class DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): BIT101Database {
        return Room.databaseBuilder(
            context,
            BIT101Database::class.java,
            FILE_NAME
        )
            // 3 → 4 是列类型变更（credit INTEGER→REAL），Room 的 AutoMigration 处理不了，
            // 必须注册手写迁移；漏了这条，老用户升级会直接抛 IllegalStateException。
            .addMigrations(MIGRATION_3_4)
            .build()
    }
}