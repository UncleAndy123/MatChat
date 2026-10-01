package org.matchat.core.ui.prefs

/**
 * The sentinel stored in [UserPreferences.notificationSoundUri] to mean "no
 * sound," as distinct from `null` ("system default"). Android's own ringtone
 * picker returns `null` for both "Silent" and "nothing picked," so this app
 * needs its own marker to tell them apart — shared between `:app`
 * (MessageNotifier, which builds the actual notification channel) and
 * `:feature:settings` (NotificationsViewModel, which reads the picker
 * result), neither of which can depend on the other.
 */
const val SILENT_NOTIFICATION_SOUND: String = org.matchat.core.model.notify.SILENT_SOUND
