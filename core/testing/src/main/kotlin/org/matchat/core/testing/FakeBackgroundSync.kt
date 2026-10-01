package org.matchat.core.testing

import kotlinx.coroutines.flow.MutableStateFlow
import org.matchat.core.model.background.BackgroundSync
import org.matchat.core.model.background.BackgroundSyncStatus

/** In-memory [BackgroundSync]. Tests set [status] directly, or set
 *  [systemBatteryExempt] (what the "system" would report) and observe that
 *  [refresh] picks it up — the same shape as the real PowerManager read. */
class FakeBackgroundSync(initial: BackgroundSyncStatus = BackgroundSyncStatus()) : BackgroundSync {
    override val status = MutableStateFlow(initial)

    var systemBatteryExempt: Boolean = initial.batteryExempt

    override fun refresh() {
        status.value = status.value.copy(batteryExempt = systemBatteryExempt)
    }
}
