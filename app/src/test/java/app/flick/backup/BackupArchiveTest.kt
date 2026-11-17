package app.flick.backup

import app.flick.settings.LauncherSettings
import app.flick.wallpaper.WallpaperPhoto
import app.flick.wallpaper.WallpaperSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = byteArrayOf(1, 7, 3, 9)
    private val id = BackupArchive.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun manifest() = BackupManifest.create(LauncherSettings(),
        WallpaperSettings(photos = listOf(WallpaperPhoto("content://example/photo"))),
        { BackupManifest.assetReference(id) }, { setOf(id) })

    @Test fun manifestAndImageRoundTripWithDigestValidation() {
        val image = temporary.newFile().apply { writeBytes(bytes) }
        val stream = ByteArrayOutputStream()
        BackupArchive.write(stream, manifest(), mapOf(id to image))
        val directory = temporary.newFolder()
        var validated = 0
        val restored = BackupArchive.read(ByteArrayInputStream(stream.toByteArray()), directory, { validated++ })
        assertEquals(setOf(id), restored.assetIds)
        assertArrayEquals(bytes, File(directory, id).readBytes())
        assertEquals(1, validated)
    }

    @Test fun untrustedPathsAndUnexpectedEntriesNeverEscapeStaging() {
        for (name in listOf("../outside", "/absolute", "media/../outside", "media/fake", "extra.json")) {
            val archive = zip(listOf("manifest.json" to manifest().encoded().toByteArray(), name to bytes))
            assertThrows(IllegalArgumentException::class.java) {
                BackupArchive.read(ByteArrayInputStream(archive), temporary.newFolder(), {})
            }
        }
    }

    @Test fun corruptionMissingImagesAndUnreferencedImagesAreRejected() {
        val entries = listOf(
            listOf("manifest.json" to manifest().encoded().toByteArray()),
            listOf("manifest.json" to manifest().encoded().toByteArray(), "media/$id" to byteArrayOf(8)),
            listOf("media/$id" to bytes),
        )
        for (entry in entries) assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.read(ByteArrayInputStream(zip(entry)), temporary.newFolder(), {})
        }
    }

    @Test fun decompressedImageLimitAppliesEvenWhenZipIsTiny() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("media/$id"))
            val block = ByteArray(8192)
            repeat((BackupArchive.MAX_IMAGE_BYTES / block.size).toInt() + 1) { zip.write(block) }
            zip.closeEntry()
        }
        assertTrue(output.size() < 100_000)
        val directory = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.read(ByteArrayInputStream(output.toByteArray()), directory, {})
        }
        assertTrue(File(directory, id).length() <= BackupArchive.MAX_IMAGE_BYTES)
    }

    @Test fun malformedUtf8AndOversizedManifestAreRejected() {
        assertThrows(CharacterCodingException::class.java) { BackupArchive.decodeUtf8(byteArrayOf(0xc3.toByte(), 0x28)) }
        val oversized = zip(listOf("manifest.json" to ByteArray(BackupManifest.MAX_MANIFEST_BYTES + 1)))
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.read(ByteArrayInputStream(oversized), temporary.newFolder(), {})
        }
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
    }.toByteArray()
}
