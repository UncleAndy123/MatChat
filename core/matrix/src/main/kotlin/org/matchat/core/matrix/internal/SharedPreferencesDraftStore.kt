package org.matchat.core.matrix.internal

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.matchat.core.matrix.Draft
import org.matchat.core.matrix.DraftStore
import org.matchat.core.model.RoomId
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A dedicated `SharedPreferences` file (separate from `:core:ui`'s
 * `UserPreferences` — different module, different concern), one JSON value
 * per room id ([DraftCodec]). Plain, unencrypted — consistent with the
 * existing security posture: the SDK's own local store already holds
 * decrypted message bodies on disk in the clear
 * ([SessionFileStore.sdkStorePath]); AGENTS.md §9's "no message
 * content" rule governs logging, not on-disk storage.
 */
@Singleton
internal class SharedPreferencesDraftStore @Inject constructor(
    @ApplicationContext context: Context,
) : DraftStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Write-through cache kept in sync on every write, seeded from the file
    // at construction — same shape SharedPreferencesUserPreferences already
    // uses per-key (a MutableStateFlow mirroring each prefs value),
    // generalized to a map here so `drafts` is cheap to observe.
    private val draftsState = MutableStateFlow(loadAll())
    override val drafts: StateFlow<Map<String, Draft>> = draftsState

    private fun loadAll(): Map<String, Draft> = prefs.all.mapNotNull { (key, value) ->
        (value as? String)?.let { DraftCodec.fromJson(it) }?.let { key to it }
    }.toMap()

    override fun getDraft(roomId: RoomId): Draft? {
        val draft = draftsState.value[roomId.value] ?: return null
        val attachment = draft.attachment ?: return draft
        // A cache file the OS has since reclaimed can't be resumed — drop
        // just the attachment half, the text still restores fine.
        return if (File(attachment.path).exists()) draft else draft.copy(attachment = null)
    }

    override suspend fun setDraft(roomId: RoomId, draft: Draft) {
        prefs.edit { putString(roomId.value, DraftCodec.toJson(draft)) }
        draftsState.value = draftsState.value + (roomId.value to draft)
    }

    override suspend fun clearDraft(roomId: RoomId) {
        prefs.edit { remove(roomId.value) }
        draftsState.value = draftsState.value - roomId.value
    }

    override suspend fun clearAll() {
        prefs.edit { clear() }
        draftsState.value = emptyMap()
    }

    private companion object {
        const val PREFS_NAME = "message_drafts"
    }
}
