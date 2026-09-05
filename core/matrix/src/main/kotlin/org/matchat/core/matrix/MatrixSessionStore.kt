package org.matchat.core.matrix

/**
 * Persists and restores the session token, encrypted at rest with an Android
 * Keystore AES-GCM key — NOT androidx.security:security-crypto, which is
 * deprecated (ARCHITECTURE.md, PLAN.md §4). We persist only the session token;
 * the SDK owns its own SQLite store for everything else.
 *
 * No token, room name, message body, or access token is ever logged (AGENTS.md §9).
 */
interface MatrixSessionStore {
    fun hasSession(): Boolean

    /** Opaque, already-encrypted session blob written by the SDK bridge. */
    suspend fun persist(sessionBlob: ByteArray)

    suspend fun load(): ByteArray?

    /** Clears the token on sign-out. Called by MatrixSession.logout(). */
    suspend fun clear()
}
