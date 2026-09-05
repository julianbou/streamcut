package com.nuvio.app.features.clip

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of per-title filing that touches the disk: creating the folder, and
 * what happens when it cannot be created. Run against a real temporary
 * directory rather than the user's clips folder.
 */
class ClipOutputDirTest {

    private fun tempRoot(): File =
        File.createTempFile("nuvio-clip-dir-", "").let { file ->
            file.delete()
            file.mkdirs()
            file.deleteOnExit()
            file
        }

    @Test
    fun `no segments writes straight into the clips folder`() {
        val root = tempRoot()
        assertEquals(root, clipOutputDir(root, emptyList()))
        assertEquals(emptyList(), root.list()?.toList())
    }

    @Test
    fun `an episode's folders are created, nested, on first use`() {
        val root = tempRoot()
        val target = clipOutputDir(root, listOf("Severance", "Season 02", "Episode 05"))

        assertEquals(File(File(File(root, "Severance"), "Season 02"), "Episode 05"), target)
        assertTrue(target.isDirectory)
    }

    @Test
    fun `a second clip of the same episode reuses the folder instead of failing`() {
        val root = tempRoot()
        val segments = listOf("Severance", "Season 02", "Episode 05")
        val first = clipOutputDir(root, segments)
        val second = clipOutputDir(root, segments)
        assertEquals(first, second)
        assertTrue(second.isDirectory)
    }

    @Test
    fun `a folder that cannot be created costs the folder, not the clip`() {
        val root = tempRoot()
        // A plain file where the folder would go: mkdirs cannot win here, which
        // is the same shape of failure as a read-only or full volume.
        File(root, "Dune").writeText("not a directory")

        assertEquals(root, clipOutputDir(root, listOf("Dune")))
    }
}
