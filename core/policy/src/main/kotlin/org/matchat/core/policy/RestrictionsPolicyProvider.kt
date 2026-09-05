package org.matchat.core.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place [RestrictionsManager] is read. Loads the bundle at startup,
 * registers for ACTION_APPLICATION_RESTRICTIONS_CHANGED, and re-emits so policy
 * is live (docs/MDM.md §4). Nothing here can return a room list or a search
 * result — G3 is structural, not a habit (ARCHITECTURE.md).
 */
@Singleton
class RestrictionsPolicyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : PolicyProvider {

    private val restrictionsManager: RestrictionsManager? = context.getSystemService()

    private val _policy = MutableStateFlow(read())
    override val policy: StateFlow<Policy> = _policy.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _policy.value = read()
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun read(): Policy {
        val bundle: Bundle = restrictionsManager?.applicationRestrictions ?: Bundle.EMPTY
        return PolicyBundleParser.parse(
            isEmpty = bundle.isEmpty,
            pinnedHomeserver = bundle.getString(KEY_PINNED_HOMESERVER),
            allowedDomains = bundle.getString(KEY_ALLOWED_DOMAINS),
            allowDirectChat = bundle.getBoolean(KEY_ALLOW_DIRECT_CHAT, true),
            invitePolicy = bundle.getString(KEY_INVITE_POLICY),
            contactsJson = bundle.getString(KEY_CONTACTS),
            mediaSend = bundle.getBoolean(KEY_MEDIA_SEND, true),
        )
    }

    private companion object {
        const val KEY_PINNED_HOMESERVER = "pinnedHomeserver"
        const val KEY_ALLOWED_DOMAINS = "allowedDomains"
        const val KEY_ALLOW_DIRECT_CHAT = "allowDirectChat"
        const val KEY_INVITE_POLICY = "invitePolicy"
        const val KEY_CONTACTS = "contacts"
        const val KEY_MEDIA_SEND = "mediaSend"
    }
}
