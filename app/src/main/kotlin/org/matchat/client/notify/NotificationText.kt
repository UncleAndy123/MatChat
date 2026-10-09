package org.matchat.client.notify

import org.matchat.core.model.MediaKind

/** What a message notification says, before string resources are applied. */
data class NotificationContent(
    val roomName: String,
    /** Who sent the latest message; null when unknown. */
    val sender: String?,
    /** The latest message's text; null for media or when it can't be shown. */
    val text: String?,
    /** Set when the latest message is media. */
    val media: MediaKind?,
    val unread: Int,
)

/** Pure decisions about the notification's wording, unit-testable on the JVM. */
object NotificationText {

    /** What the notification body is built from. */
    sealed interface Body {
        data class Text(val text: String) : Body
        data class Media(val kind: MediaKind) : Body

        /** Nothing to show (encrypted and not yet decrypted, an unknown type):
         *  fall back to "N new messages". */
        data object CountOnly : Body
    }

    fun body(content: NotificationContent): Body = when {
        !content.text.isNullOrBlank() -> Body.Text(content.text)
        content.media != null -> Body.Media(content.media)
        else -> Body.CountOnly
    }

    /** The sender to prefix ("Ann: hi"), or null. Skipped when it would just
     *  repeat the title — a direct chat is usually named after the other person. */
    fun senderPrefix(content: NotificationContent): String? =
        content.sender?.takeIf { it.isNotBlank() && it != content.roomName }
}
