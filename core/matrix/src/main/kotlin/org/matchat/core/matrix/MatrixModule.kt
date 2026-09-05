package org.matchat.core.matrix

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.matchat.core.matrix.internal.StubMatrixAuth
import org.matchat.core.matrix.internal.StubMatrixSession

/**
 * Binds the Matrix contract. In M0 the bindings point at the in-memory stubs;
 * M1 swaps them for the SDK-backed implementations without touching any caller,
 * because only the binding changes — the interfaces are stable.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MatrixModule {
    @Binds
    abstract fun bindSession(impl: StubMatrixSession): MatrixSession

    @Binds
    abstract fun bindAuth(impl: StubMatrixAuth): MatrixAuth
}
