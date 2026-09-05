package org.matchat.client

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt graph root. The sync foreground service (not this class) owns the SDK
 *  client; the app just constructs the graph (ARCHITECTURE.md "Sync lifecycle"). */
@HiltAndroidApp
class MatChatApp : Application()
