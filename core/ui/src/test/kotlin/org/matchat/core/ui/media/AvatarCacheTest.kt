package org.matchat.core.ui.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** decodeAndCache round-crops real photo avatars (Canvas.drawCircle with a
 *  BitmapShader — see [AvatarCache]), so it needs real rasterisation. Request
 *  Robolectric's NATIVE graphics (default legacy no-ops the draw); the test also
 *  guards itself and skips where even that won't render (see the probe below),
 *  so it still really runs in CI. Scoped to this class — the module's logic-only
 *  Robolectric tests (KeyMapTest, ThemeColorTest) stay on the default mode. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class AvatarCacheTest {

    @Test
    fun `a real photo is cropped to a circle, not left square`() {
        assumeRealCanvasRendering()
        val square = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val bitmap = AvatarCache.decodeAndCache("mxc://s/photo", square.toPng(), maxPx = 100)
        requireNotNull(bitmap)

        // A far corner sits outside the inscribed circle -> fully transparent.
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        // The center sits inside the circle -> keeps the source color.
        val center = bitmap.width / 2
        assertEquals(Color.RED, bitmap.getPixel(center, center))
    }

    /** Skip (don't fail) when the environment can't actually rasterise Canvas
     *  draws — e.g. Robolectric's legacy graphics on a host where native
     *  graphics won't start. A one-pixel probe: draw red and read it back; a
     *  no-op renderer leaves it transparent. CI (Linux, NATIVE graphics) renders
     *  for real, so the pixel assertions below still run there. */
    private fun assumeRealCanvasRendering() {
        val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        Canvas(probe).drawColor(Color.RED)
        assumeTrue(
            "Canvas drawing is a no-op here (no native graphics); the real-render " +
                "assertions run in CI.",
            Color.red(probe.getPixel(0, 0)) == 255,
        )
    }

    private fun Bitmap.toPng(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
