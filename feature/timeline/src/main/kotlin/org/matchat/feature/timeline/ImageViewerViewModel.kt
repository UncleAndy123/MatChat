package org.matchat.feature.timeline

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.model.EventId
import javax.inject.Inject

/** Loads one image's bytes by event id for the full-screen viewer (S9). */
@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    private val session: MatrixSession,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val eventId = EventId(requireNotNull(savedStateHandle["eventId"]))

    private val _state = MutableStateFlow(ImageViewerState())
    val state: StateFlow<ImageViewerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val bytes = session.loadMedia(eventId)
            _state.update {
                if (bytes == null) it.copy(isLoading = false, failed = true)
                else it.copy(isLoading = false, bytes = bytes)
            }
        }
    }
}
