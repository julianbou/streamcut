package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopStorageMigrationTest {
    private fun tempRoot(): Path = Files.createTempDirectory("storage-migration")

    @Test
    fun `copies settings stores and leaves the legacy directory intact`() {
        val root = tempRoot()
        val legacy = Files.createDirectories(root.resolve("Nuvio"))
        legacy.resolve("nuvio_addons.properties").writeText("a=1")
        legacy.resolve("nuvio_auth.properties").writeText("b=2")
        Files.createDirectories(legacy.resolve("clips")).resolve("clip.mp4").writeText("video")
        val target = root.resolve("StreamCut")

        DesktopStorage.adoptLegacySettings(from = legacy, to = target)

        assertEquals("a=1", target.resolve("nuvio_addons.properties").readText())
        assertEquals("b=2", target.resolve("nuvio_auth.properties").readText())
        assertFalse(target.resolve("clips").exists(), "clip files stay where the library points")
        assertTrue(legacy.resolve("nuvio_addons.properties").exists(), "upstream Nuvio may still use it")
        assertTrue(legacy.resolve("clips/clip.mp4").exists())
        assertFalse(root.resolve("StreamCut.migrating").exists())
    }

    @Test
    fun `no legacy directory leaves nothing behind`() {
        val root = tempRoot()
        val target = root.resolve("StreamCut")

        DesktopStorage.adoptLegacySettings(from = root.resolve("Nuvio"), to = target)

        assertFalse(target.exists())
    }

    @Test
    fun `a staging directory left by an interrupted copy is replaced`() {
        val root = tempRoot()
        val legacy = Files.createDirectories(root.resolve("Nuvio"))
        legacy.resolve("nuvio_addons.properties").writeText("a=1")
        Files.createDirectories(root.resolve("StreamCut.migrating")).resolve("nuvio_addons.properties").writeText("half")
        val target = root.resolve("StreamCut")

        DesktopStorage.adoptLegacySettings(from = legacy, to = target)

        assertEquals("a=1", target.resolve("nuvio_addons.properties").readText())
    }
}
