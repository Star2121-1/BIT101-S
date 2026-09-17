package cn.bit101.android.config.seat

import cn.bit101.android.config.seat.base.SeatTaskStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SeatConfigModule {
    @Binds
    @Singleton
    abstract fun bindSeatTaskStore(
        seatTaskStore: DefaultSeatTaskStore
    ): SeatTaskStore
}
