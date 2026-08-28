package cn.bit101.android.features.seat

import cn.bit101.android.features.seat.SeatViewModel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SeatModule {
    @Binds
    @Singleton
    abstract fun bindSeatViewModel(
        vm: SeatViewModel
    ): SeatViewModel
}
