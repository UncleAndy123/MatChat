package org.matchat.core.matrix.internal

import android.content.Context
import android.util.Log
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

    private val sdkStoreDir: File get() = File(context.filesDir, "matrix-sdk")

    /** Cached photos, saved media and voice notes (feature/timeline MediaFiles /
     *  VoiceRecorder both write here). Account data — wiped on sign-out. */
    private val mediaCacheDir: File get() = File(context.cacheDir, "media")

    /** The single-user SDK store directory; must exist before building a Client. */
    val sdkStorePath: String
        get() = sdkStoreDir.apply { mkdirs() }.absolutePath

    /**
     * Wipe and recreate the SDK store, returning the fresh path. Used before a
     * fresh login so a new device's crypto account never clashes with a previous
     * one left in the store (MismatchedAccount). Not for the restore path, which
     * must keep the store — restore is disabled until session persistence lands.
     */
    fun resetSdkStore(): String {
        sdkStoreDir.deleteRecursively()
        return sdkStorePath
    }

    override fun hasSession(): Boolean = file.exists()

    override suspend fun persist(sessionBlob: ByteArray) = withContext(Dispatchers.IO) {
        file.writeBytes(KeystoreCrypto.encrypt(sessionBlob))
    }

    override suspend fun load(): ByteArray? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { KeystoreCrypto.decrypt(file.readBytes()) }.getOrNull()
    }

    /**
     * Sign-out wipe: remove every trace of the account from disk so the next
     * login starts clean — the session token, the SDK's SQLite crypto/state
     * store, and cached media. A leftover crypto store from an interrupted or
     * partial delete is what corrupts a re-login ("disk I/O error" on
     * migrations) and breaks device verification, so the SDK-store delete is
     * verified: if the directory survives (a file was still held open), it is
     * logged rather than silently left behind. Callers must have torn down the
     * live Client first so no native handle keeps the files open.
     */
    override suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        sdkStoreDir.deleteRecursively()
        mediaCacheDir.deleteRecursively()
        if (sdkStoreDir.exists()) {
            Log.w(TAG, "crypto store survived sign-out wipe; a handle may still be open")
        }
        Unit
    }

    private companion object {
        const val TAG = "SessionStore"
    }
}
