package org.matchat.core.testing

import org.matchat.core.model.notify.BundledSoundInstaller

/** [BundledSoundInstaller] whose permission need tests control; [installed]
 *  records that the copy ran (the observable result of a real install). */
class FakeBundledSoundInstaller(override var needsStoragePermission: Boolean = false) : BundledSoundInstaller {
    var installed = false
        private set

    override suspend fun install() {
        installed = true
    }
}
