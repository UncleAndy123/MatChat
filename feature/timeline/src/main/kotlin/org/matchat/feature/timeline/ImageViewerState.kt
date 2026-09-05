package org.matchat.feature.timeline

/** The full-screen image viewer's state (S9). The bytes are decoded to a bitmap
 *  in the Fragment; the ViewModel only fetches and reports load/fail. */
data class ImageViewerState(
    val isLoading: Boolean = true,
    val bytes: ByteArray? = null,
    val failed: Boolean = false,
) {
    // Array field forces explicit equals/hashCode (identity is fine here: the
    // bytes reference changes exactly once, on load).
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is ImageViewerState &&
                isLoading == other.isLoading &&
                failed == other.failed &&
                bytes === other.bytes
            )

    override fun hashCode(): Int =
        (isLoading.hashCode() * 31 + failed.hashCode()) * 31 + (bytes?.size ?: 0)
}
