package org.matchat.core.policy

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the RestrictionsManager-backed provider. The rest of the app injects
 *  only [PolicyProvider] and never sees RestrictionsManager. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class PolicyModule {
    @Binds
    abstract fun bindPolicyProvider(impl: RestrictionsPolicyProvider): PolicyProvider
}
