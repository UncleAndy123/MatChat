package org.matchat.client.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule that decides whether an outside-button press re-shows the
 *  Kyocera cover card (docs/COVER-DISPLAY.md). */
class CoverReshowRuleTest {

    private val card = 15_000L

    @Test
    fun `re-shows an unread card when the screen is off and the last card has expired`() {
        assertTrue(shouldReshowOnKeyPress(hasPending = true, screenInteractive = false, msSinceLastShown = card, cardDurationMs = card))
    }

    @Test
    fun `does nothing when nothing is unread`() {
        assertFalse(shouldReshowOnKeyPress(hasPending = false, screenInteractive = false, msSinceLastShown = card, cardDurationMs = card))
    }

    @Test
    fun `does nothing while the main screen is on - the flip is open`() {
        assertFalse(shouldReshowOnKeyPress(hasPending = true, screenInteractive = true, msSinceLastShown = card, cardDurationMs = card))
    }

    @Test
    fun `does not restart a card that is still on screen`() {
        assertFalse(shouldReshowOnKeyPress(hasPending = true, screenInteractive = false, msSinceLastShown = card - 1, cardDurationMs = card))
    }
}
