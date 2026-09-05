package org.matchat.core.matrix.internal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matchat.core.matrix.MatrixSessionStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the session blob to a Keystore-encrypted file (MatrixSessionStore).
 * We store only the token blob; the SDK owns its own SQLite state under
 * [sdkStorePath]. No token is ever logged (AGENTS.md §9).
 */
@Singleton
internal class SessionFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : MatrixSessionStore {

    private val file: File get() = File(context.filesDir, "session.bin")

    /** The single-user SDK store directory; must exist before building a Client. */
    val sdkStorePath: String
        get() = File(context.filesDir, "matrix-sdk").apply { mkdirs() }.absolutePath

    override fun hasSession(): Boolean = file.exists()

    override suspend fun persist(sessionBlob: ByteArray) = withContext(Dispatchers.IO) {
        file.writeBytes(KeystoreCrypto.encrypt(sessionBlob))
    }

    override suspend fun load(): ByteArray? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { KeystoreCrypto.decrypt(file.readBytes()) }.getOrNull()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        File(sdkStorePath).deleteRecursively()
        Unit
    }
}
