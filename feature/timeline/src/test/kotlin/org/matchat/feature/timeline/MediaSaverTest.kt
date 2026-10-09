package org.matchat.feature.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MediaSaverTest {

    @Test
    fun `uniqueFile returns the plain name when nothing exists`(@TempDir dir: File) {
        assertEquals(File(dir, "photo.jpg"), MediaSaver.uniqueFile(dir, "photo.jpg"))
    }

    @Test
    fun `uniqueFile numbers around an existing name, keeping the extension`(@TempDir dir: File) {
        File(dir, "photo.jpg").createNewFile()
        assertEquals(File(dir, "photo (1).jpg"), MediaSaver.uniqueFile(dir, "photo.jpg"))

        File(dir, "photo (1).jpg").createNewFile()
        assertEquals(File(dir, "photo (2).jpg"), MediaSaver.uniqueFile(dir, "photo.jpg"))
    }

    @Test
    fun `uniqueFile handles a name with no extension`(@TempDir dir: File) {
        File(dir, "report").createNewFile()
        assertEquals(File(dir, "report (1)"), MediaSaver.uniqueFile(dir, "report"))
    }
}
