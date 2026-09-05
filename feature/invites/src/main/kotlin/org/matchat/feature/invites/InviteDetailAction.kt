package org.matchat.feature.invites

sealed interface InviteDetailAction {
    data object Accept : InviteDetailAction
    data object Decline : InviteDetailAction
    data object DeclineAndIgnore : InviteDetailAction
}

sealed interface InviteDetailNav {
    data object Dismissed : InviteDetailNav
}
