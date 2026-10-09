package org.matchat.core.ui.prefs

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.matchat.core.model.notify.RoomNotificationSounds

/** Binds the SharedPreferences-backed impl. The rest of the app injects only
 *  [UserPreferences] (mirrors :core:policy's PolicyModule). */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class UserPreferencesModule {
    @Binds
    abstract fun bindUserPreferences(impl: SharedPreferencesUserPreferences): UserPreferences

    @Binds
    abstract fun bindRoomNotificationSounds(impl: SharedPreferencesRoomNotificationSounds): RoomNotificationSounds
}
