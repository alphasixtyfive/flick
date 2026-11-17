package app.flick.notifications

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class NoticeImageReadTest {
    @Test fun `a response at the size limit is accepted unchanged`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, readNoticeImageBytes(ByteArrayInputStream(bytes), bytes.size) { true })
    }

    @Test fun `an oversized response is rejected without a content length`() {
        assertNull(readNoticeImageBytes(ByteArrayInputStream(ByteArray(9_000)), 8_500) { true })
    }

    @Test fun `cancellation before reading does not consume the response`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        assertThrows(IOException::class.java) { readNoticeImageBytes(input, 3) { false } }
        assertEquals(3, input.available())
    }

    @Test fun `cancellation during a response stops before another read`() {
        var active = true
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Buffered reads expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                buffer[offset] = 1
                active = false
                return 1
            }
        }
        assertThrows(IOException::class.java) { readNoticeImageBytes(input, 10) { active } }
        assertEquals(1, reads)
    }
}
