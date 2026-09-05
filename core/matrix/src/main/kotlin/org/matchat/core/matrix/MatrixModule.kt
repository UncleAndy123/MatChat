package org.matchat.core.matrix

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.matchat.core.matrix.internal.RustMatrixAuth
import org.matchat.core.matrix.internal.RustMatrixSession
import org.matchat.core.matrix.internal.SessionFileStore

/**
 * Binds the Matrix contract to the SDK-backed implementations (M1). Only the
 * bindings changed from M0 — every caller is unaffected, because the interfaces
 * in [MatrixSession]/[MatrixAuth]/[MatrixSessionStore] are unchanged.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MatrixModule {
    @Binds
    abstract fun bindSession(impl: RustMatrixSession): MatrixSession

    @Binds
    abstract fun bindAuth(impl: RustMatrixAuth): MatrixAuth

    @Binds
    abstract fun bindStore(impl: SessionFileStore): MatrixSessionStore
}
