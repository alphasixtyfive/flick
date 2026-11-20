package app.flick.tv.updates

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadTest {
    @Test fun copiesBytesAndHashesTheActualDownloadedContent() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        val digest = copyUpdate(ByteArrayInputStream("abc".toByteArray()), output, 3, { true }, progress::add)
        assertEquals("abc", output.toString("UTF-8"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
        assertEquals(listOf(100), progress)
    }

    @Test fun rejectsTruncatedDownloads() {
        try {
            copyUpdate(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayOutputStream(), 3, { true }) {}
            fail("A truncated APK was accepted")
        } catch (_: UpdateException) { }
    }

    @Test fun doesNotWriteBeyondThePublishedAssetSize() {
        val output = ByteArrayOutputStream()
        try {
            copyUpdate(ByteArrayInputStream(ByteArray(70_000)), output, 65_000, { true }) {}
            fail("An oversized APK was accepted")
        } catch (_: UpdateException) { }
        assertTrue(output.size() <= 65_000)
    }

    @Test fun cancellationStopsTheCopyAndDoesNotReportCompletion() {
        var checks = 0
        val progress = mutableListOf<Int>()
        val output = ByteArrayOutputStream()
        try {
            copyUpdate(ByteArrayInputStream(ByteArray(200_000)), output, 200_000, { ++checks <= 1 }, progress::add)
            fail("A cancelled download completed")
        } catch (_: IOException) { }
        assertEquals(65_536, output.size())
        assertFalse(100 in progress)
    }

    @Test fun progressIsMonotonicAndFinishesAtOneHundred() {
        val progress = mutableListOf<Int>()
        copyUpdate(ByteArrayInputStream(ByteArray(200_000)), ByteArrayOutputStream(), 200_000, { true }, progress::add)
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
    }
}
