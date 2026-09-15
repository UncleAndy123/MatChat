package org.matchat.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.matchat.core.matrix.Draft
import org.matchat.core.matrix.DraftStore
import org.matchat.core.model.RoomId

/** An in-memory [DraftStore] for ViewModel tests. */
class FakeDraftStore(
    val draftsFlow: MutableStateFlow<Map<String, Draft>> = MutableStateFlow(emptyMap()),
) : DraftStore {

    override val drafts: StateFlow<Map<String, Draft>> = draftsFlow

    override fun getDraft(roomId: RoomId): Draft? = draftsFlow.value[roomId.value]

    override suspend fun setDraft(roomId: RoomId, draft: Draft) {
        draftsFlow.value = draftsFlow.value + (roomId.value to draft)
    }

    override suspend fun clearDraft(roomId: RoomId) {
        draftsFlow.value = draftsFlow.value - roomId.value
    }

    override suspend fun clearAll() {
        draftsFlow.value = emptyMap()
    }
}
