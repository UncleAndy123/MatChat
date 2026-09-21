package org.matchat.core.ui.media

import android.widget.ImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Binds an avatar into [ImageView], shared by every feature that shows one
 * (room list, timeline, Room Info) — features can't depend on each other, so
 * this lives here instead of being copied three times. Each caller supplies
 * its own byte fetch ([loadBytes] — their ViewModel -> MatrixSession
 * .loadAvatar; :core:ui can't see :core:matrix), the way TimelineFragment's
 * loadImageInto already supplies its own MatrixSession.loadMedia call.
 */
object AvatarBinder {

    /** Shows the no-avatar fallback (a colored circle with [name]'s initial,
     *  AvatarFallback round) immediately, then replaces it with the decoded
     *  bitmap once [loadBytes] resolves — [image].tag guards against a stale
     *  async result landing on a recycled row (same guard
     *  TimelineFragment.loadImageInto uses for message images). A failed
     *  download (no avatar set, or the fetch fails) just leaves the fallback
     *  showing — never the old flat gray placeholder. Call from within the
     *  Fragment's own lifecycleScope; this suspends until done. */
    suspend fun bind(
        image: ImageView,
        url: String?,
        name: String,
        userId: String,
        maxPx: Int,
        loadBytes: suspend (String) -> ByteArray?,
    ) {
        image.tag = url
        image.setImageBitmap(AvatarCache.fallback(userId, name))
        if (url == null) return
        AvatarCache.get(url)?.let {
            image.setImageBitmap(it)
            return
        }
        val bytes = withContext(Dispatchers.IO) { loadBytes(url) } ?: return
        val bitmap = withContext(Dispatchers.Default) { AvatarCache.decodeAndCache(url, bytes, maxPx) }
        if (bitmap != null && image.tag == url) image.setImageBitmap(bitmap)
    }
}
