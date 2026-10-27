package org.matchat.feature.invites

/**
 * S28 Join group: a room linked in a message, before joining. Loading, the
 * explanation line, a policy block and errors are all fields here
 * (AGENTS.md §3). When [blockedDomain] is set there is no button — only the
 * explanation naming the domain (AGENTS.md "Policy rules").
 */
data class JoinRoomState(
    /** The room's name, or its address until the preview arrives. */
    val name: String = "",
    /** The alias or room id from the link — what the user is judging. */
    val address: String = "",
    val topic: String? = null,
    val memberCount: Int? = null,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val note: JoinNote? = null,
    val button: JoinButton = JoinButton.NONE,
    val blockedDomain: String? = null,
    val error: JoinError? = null,
)

/** The one plain-language line explaining where the user stands. */
enum class JoinNote {
    KNOCK_ROOM,
    INVITE_ONLY,
    NEEDS_INVITE,
    INVITED,
    REQUEST_SENT,
    ALREADY_JOINED,
    BANNED,
    BAD_LINK,
    NO_PREVIEW,
}

/** The single action button; NONE hides it. */
enum class JoinButton { NONE, JOIN, ASK, OPEN }

enum class JoinError { JOIN_FAILED, KNOCK_FAILED }
