package org.matchat.feature.timeline

import org.matchat.core.model.UserId
import org.matchat.core.model.notify.RoomSoundChoice

/** A display row on the Room Info screen (S12). Focusable rows are CENTER targets. */
sealed interface RoomInfoRow {
    val stableId: String

    /** An editable field (name/topic). CENTER opens a text prompt. */
    data class Field(val key: String, val label: String, val value: String) : RoomInfoRow {
        override val stableId: String get() = "field:$key"
    }

    /** This room's notification sound (UX-SPEC S12). CENTER opens the
     *  system sound picker; [choice] AppDefault means the app-wide sound. */
    data class Sound(val choice: RoomSoundChoice) : RoomInfoRow {
        override val stableId: String get() = "sound"
    }

    /** A read-only line (e.g. encryption state). */
    data class Info(val label: String, val value: String) : RoomInfoRow {
        override val stableId: String get() = "info:$label"
    }

    data class Section(val text: String) : RoomInfoRow {
        override val stableId: String get() = "section:$text"
    }

    /** A room member. CENTER opens the member menu (remove), unless [isSelf]. */
    data class Member(
        val userId: UserId,
        val name: String,
        val sub: String,
        val isSelf: Boolean,
        val avatarUrl: String? = null,
    ) : RoomInfoRow {
        override val stableId: String get() = "member:${userId.value}"
    }

    /** A room-level action (add member / leave). */
    data class Action(val key: String, val label: String) : RoomInfoRow {
        override val stableId: String get() = "action:$key"
    }
}

data class RoomInfoState(
    val title: String = "",
    val rows: List<RoomInfoRow> = emptyList(),
    val isBusy: Boolean = false,
)
