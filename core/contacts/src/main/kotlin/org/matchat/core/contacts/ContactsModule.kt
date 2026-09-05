package org.matchat.core.contacts

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ContactsModule {
    @Binds
    abstract fun bindRepository(impl: DefaultContactsRepository): ContactsRepository

    @Binds
    abstract fun bindLocalStore(impl: InMemoryLocalContactsStore): LocalContactsStore
}
