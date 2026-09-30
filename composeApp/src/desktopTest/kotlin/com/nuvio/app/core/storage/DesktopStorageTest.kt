package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopStorageTest {
    @Test
    fun unchanged_operations_do_not_rewrite_the_store() {
        val directory = Files.createTempDirectory("desktop-storage-test")
        val file = directory.resolve("preferences.properties")
        try {
            val store = DesktopStorage.Store(file)
            store.putString("key", "value")
            val sentinel = FileTime.fromMillis(1_000L)
            Files.setLastModifiedTime(file, sentinel)

            store.putString("key", "value")
            store.remove("missing")
            store.removeAll(listOf("also-missing"))

            assertEquals(sentinel, Files.getLastModifiedTime(file))

            store.putString("key", "updated")

            assertNotEquals(sentinel, Files.getLastModifiedTime(file))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun sign_out_wipe_keeps_files_on_disk_and_the_stores_that_index_them() {
        val directory = Files.createTempDirectory("desktop-storage-wipe-test")
        try {
            val auth = Files.writeString(directory.resolve("nuvio_auth.properties"), "token=x")
            val clips = Files.writeString(directory.resolve("nuvio_clips.properties"), "clip_library_1=[]")
            val downloads = Files.writeString(directory.resolve("nuvio_downloads.properties"), "x=y")
            val clip = Files.writeString(Files.createDirectories(directory.resolve("clips")).resolve("a.mp4"), "")
            val ffmpeg = Files.writeString(Files.createDirectories(directory.resolve("ffmpeg")).resolve("ffmpeg"), "")

            DesktopStorage.wipeAccountFiles(directory)

            assertFalse(Files.exists(auth))
            listOf(clips, downloads, clip, ffmpeg).forEach { assertTrue(Files.exists(it), "$it was deleted") }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
