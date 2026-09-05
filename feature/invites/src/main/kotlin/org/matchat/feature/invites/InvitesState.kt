package org.matchat.feature.invites

import org.matchat.core.model.RoomId

/** One invitation row (S18). A blocked domain still shows, tagged "Not allowed";
 *  the reason belongs on S19, never a silent omission (AGENTS.md "Policy rules"). */
data class InviteRow(
    val roomId: RoomId,
    val name: String,
    val from: String,
    val blocked: Boolean,
)

data class InvitesState(
    val invites: List<InviteRow> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = !isLoading && invites.isEmpty()
}
