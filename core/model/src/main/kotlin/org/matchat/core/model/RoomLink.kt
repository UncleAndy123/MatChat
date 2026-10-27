package org.matchat.core.model

/**
 * A room someone shared as a link in a message — a `https://matrix.to/#/…`
 * permalink, a `matrix:` URI, or a web client's `…/#/room/…` link. Knowing a
 * room's address this way is allowed; finding one is not (AGENTS.md §0): this
 * only reads a link the user was sent, it never searches for rooms.
 *
 * [idOrAlias] is a room id (`!abc:server`, or `!abc` for a v12 room) or an
 * alias (`#name:server`); [via] are the servers the link says can route the
 * join, passed through to the SDK unchanged.
 */
data class RoomLink(val idOrAlias: String, val via: List<String> = emptyList()) {

    val isAlias: Boolean get() = idOrAlias.startsWith('#')

    /**
     * The server this room is checked against policy with: the alias's or room
     * id's own server, else (a v12 room id carries none) the first `via`
     * server. Null when the link names no server at all — policy is fail-open,
     * so an unknown server is never treated as blocked (AGENTS.md "Policy rules").
     */
    val domain: String?
        get() = idOrAlias.substringAfter(':', missingDelimiterValue = "").ifEmpty { null }
            ?: via.firstOrNull()

    /** The canonical matrix.to form, used to hand the link between screens. */
    fun toMatrixTo(): String {
        val query = if (via.isEmpty()) "" else via.joinToString("&", prefix = "?") { "via=$it" }
        return "$MATRIX_TO$idOrAlias$query"
    }

    companion object {
        private const val MATRIX_TO = "https://matrix.to/#/"

        // A link runs to the next whitespace or quote/bracket; trailing sentence
        // punctuation is trimmed separately in [parse].
        private val CANDIDATE = Regex("""(?:https?://\S+?/#/\S+|matrix:\S+)""", RegexOption.IGNORE_CASE)
        private const val TRAILING_PUNCTUATION = ".,;:!?)]}>\"'"

        /** The first room link in a message [body], or null when it has none. */
        fun findIn(body: String): RoomLink? = CANDIDATE.findAll(body).firstNotNullOfOrNull { parse(it.value) }

        /** Parses one link; null when it is not a link to a room (a user, an
         *  ordinary web page, or malformed). */
        fun parse(link: String): RoomLink? {
            val text = link.trim().trimEnd { it in TRAILING_PUNCTUATION }
            return when {
                text.startsWith("matrix:", ignoreCase = true) -> parseMatrixUri(text.substring("matrix:".length))
                text.contains("/#/") -> parseFragment(text.substringAfter("/#/"))
                else -> null
            }
        }

        /** `matrix.to/#/<id>[/<event>][?via=…]` or a web client's `#/room/<id>…`. */
        private fun parseFragment(fragment: String): RoomLink? {
            val path = fragment.removePrefix("room/")
            val id = decode(path.substringBefore('?').substringBefore('/'))
            return roomLinkOf(id, viaOf(path))
        }

        /** `matrix:roomid/<id>[/e/<event>][?via=…]` or `matrix:r/<alias>` (MSC2312). */
        private fun parseMatrixUri(rest: String): RoomLink? {
            val path = rest.substringBefore('?')
            val segments = path.split('/')
            if (segments.size < 2) return null
            val name = decode(segments[1])
            val id = when (segments[0]) {
                "roomid" -> "!$name"
                "r" -> "#$name"
                else -> return null
            }
            return roomLinkOf(id, viaOf(rest))
        }

        private fun roomLinkOf(id: String, via: List<String>): RoomLink? {
            if (id.length < 2 || (id[0] != '!' && id[0] != '#')) return null
            // An alias always names its server; a bare "#name" is a hashtag.
            if (id[0] == '#' && !id.contains(':')) return null
            return RoomLink(id, via)
        }

        private fun viaOf(text: String): List<String> = text.substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .filter { it.startsWith("via=") }
            .map { decode(it.removePrefix("via=")) }
            .filter { it.isNotEmpty() }

        /** Percent-decoding only (no '+' → space, which URLDecoder would do). */
        private fun decode(text: String): String {
            if ('%' !in text) return text
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val hex = if (c == '%' && i + 2 <= text.lastIndex) {
                    text.substring(i + 1, i + 3).toIntOrNull(radix = 16)
                } else {
                    null
                }
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                } else {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}

/** Who may join a room, as its preview reports it (S28). */
enum class JoinRule {
    /** Anyone with the address can join. */
    PUBLIC,

    /** Anyone may *ask* to join; a member lets them in (knock, knock_restricted). */
    KNOCK,

    /** Only people a member invites (invite, private). */
    INVITE_ONLY,

    /** The server did not say, or it depends on something only the server can
     *  check (restricted to members of a space) — joining is attempted. */
    UNKNOWN,
}

/** Our own relation to a previewed room. */
enum class PreviewMembership { NONE, JOINED, INVITED, KNOCKED, BANNED, LEFT }

/** What S28 shows about a linked room before joining — a lookup of one known
 *  room, never a search (AGENTS.md §0). */
data class RoomPreview(
    val roomId: RoomId,
    val name: String?,
    val topic: String?,
    val memberCount: Int?,
    val joinRule: JoinRule,
    val membership: PreviewMembership,
    /** A space (S29) rather than a chat room: joining opens the space. */
    val isSpace: Boolean = false,
)

/** The result of joining a linked room. */
sealed interface JoinOutcome {
    data class Joined(val roomId: RoomId) : JoinOutcome

    /** The server refused (403): the room needs an invitation — the user can
     *  still ask to join if the room allows knocking. */
    data object NeedsInvite : JoinOutcome

    /** Network or any other failure — retryable. */
    data object Failed : JoinOutcome
}
