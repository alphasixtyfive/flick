package app.flick.tv.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Byte limits apply while reading, independent of untrusted ZIP entry sizes. */
internal object BackupArchive {
    const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
    const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
    const val MAX_IMAGES = 600
    private val mediaName = Regex("media/([a-f0-9]{64})")

    fun write(output: OutputStream, manifest: BackupManifest, assets: Map<String, File>, check: () -> Unit = {}) {
        require(assets.keys == manifest.assetIds) { "Backup images are incomplete" }
        require(assets.size <= MAX_IMAGES) { "Too many backup images" }
        var total = 0L
        ZipOutputStream(output).use { zip ->
            val data = manifest.encoded().toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(data)
            zip.closeEntry()
            total += data.size
            for ((id, file) in assets.toSortedMap()) {
                check()
                require(file.length() in 1..MAX_IMAGE_BYTES) { "Invalid backup image size" }
                total += file.length()
                require(total <= MAX_ARCHIVE_BYTES) { "Backup is too large" }
                zip.putNextEntry(ZipEntry("media/$id"))
                file.inputStream().use { copyBounded(it, zip, MAX_IMAGE_BYTES, check) }
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, directory: File, validateImage: (File) -> Unit, check: () -> Unit = {}): BackupManifest {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot prepare backup images" }
        var manifest: BackupManifest? = null
        var total = 0L
        val names = mutableSetOf<String>()
        val assets = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                check()
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && names.add(entry.name) && names.size <= MAX_IMAGES + 1) { "Invalid backup entry" }
                if (entry.name == "manifest.json") {
                    val bytes = java.io.ByteArrayOutputStream()
                    total += copyBounded(zip, bytes, minOf(BackupManifest.MAX_MANIFEST_BYTES.toLong(), MAX_ARCHIVE_BYTES - total), check)
                    manifest = BackupManifest.parse(decodeUtf8(bytes.toByteArray()))
                } else {
                    val id = mediaName.matchEntire(entry.name)?.groupValues?.get(1)
                    require(id != null && assets.add(id)) { "Invalid backup image path" }
                    val file = File(directory, id)
                    val digest = MessageDigest.getInstance("SHA-256")
                    total += file.outputStream().use { out ->
                        copyBounded(zip, out, minOf(MAX_IMAGE_BYTES, MAX_ARCHIVE_BYTES - total), check) { bytes, count -> digest.update(bytes, 0, count) }
                    }
                    require(file.length() > 0 && hex(digest.digest()) == id) { "Backup image checksum does not match" }
                    validateImage(file)
                }
                require(total <= MAX_ARCHIVE_BYTES) { "Backup is too large" }
                zip.closeEntry()
            }
        }
        return requireNotNull(manifest) { "Backup settings are missing" }.also {
            require(it.assetIds == assets) { "Backup images are missing or unreferenced" }
        }
    }

    fun copyBounded(input: InputStream, output: OutputStream, limit: Long, check: () -> Unit = {},
        consume: (ByteArray, Int) -> Unit = { _, _ -> }): Long {
        var total = 0L
        val buffer = ByteArray(16 * 1024)
        while (true) {
            check()
            var count = input.read(buffer)
            if (count < 0) return total
            if (count == 0) {
                val single = input.read()
                if (single < 0) return total
                buffer[0] = single.toByte()
                count = 1
            }
            total += count
            require(total <= limit) { "Backup content exceeds the size limit" }
            consume(buffer, count)
            output.write(buffer, 0, count)
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
}
