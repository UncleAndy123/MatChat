package org.matchat.client.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.matchat.client.notify.BundledSounds
import org.matchat.client.sync.BackgroundSyncStatusProvider
import org.matchat.core.model.background.BackgroundSync
import org.matchat.core.model.notify.BundledSoundInstaller

/** Screens read background-sync status through [BackgroundSync]; `:app` owns the
 *  hosts, so it provides the implementation (docs/adr/0008). */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackgroundSyncModule {
    @Binds
    abstract fun bindBackgroundSync(impl: BackgroundSyncStatusProvider): BackgroundSync

    /** The sound screens copy bundled sounds out before opening a picker. */
    @Binds
    abstract fun bindBundledSoundInstaller(impl: BundledSounds): BundledSoundInstaller
}
