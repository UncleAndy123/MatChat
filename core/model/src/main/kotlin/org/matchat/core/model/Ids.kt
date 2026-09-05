package org.matchat.core.model

/**
 * Typed identifiers. Value classes so an id can never be passed where a
 * different kind of id is expected, at zero runtime cost (ARCHITECTURE.md).
 */
@JvmInline
value class RoomId(val value: String)

@JvmInline
value class EventId(val value: String)

@JvmInline
value class UserId(val value: String) {
    /**
     * The homeserver domain of a Matrix id `@local:domain`. Used only to show
     * the user which server an address lives on and to check it against policy —
     * never to discover or resolve anything (AGENTS.md §0).
     */
    val domain: String
        get() = value.substringAfter(':', missingDelimiterValue = "")
}
