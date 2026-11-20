package app.flick.tv.notifications

import org.junit.Assert.*
import org.junit.Test

class NoticePolicyTest {
    @Test fun `persistent notices always provide a dismissible interactive card`() {
        val notice = NoticePolicy.sanitize(TvNotice("doorbell", "Visitor", persistent = true))!!
        assertTrue(notice.interactive)
        assertTrue(notice.persistent)
    }

    @Test fun `action callbacks are bound to the notice that was displayed`() {
        val notice = NoticePolicy.sanitize(TvNotice("doorbell", "Visitor", actions = listOf(
            NoticeAction("answer", "Answer", "another-notice"),
            NoticeAction("answer", "Duplicate"),
            NoticeAction("later", "Later"),
            NoticeAction("third", "Third"),
        )))!!
        assertEquals(listOf("answer", "later"), notice.actions.map { it.actionId })
        assertEquals(listOf("doorbell", "doorbell"), notice.actions.map { it.noticeId })
        assertTrue(notice.interactive)
    }

    @Test fun `oversized photos keep the photo slot and the text`() {
        val notice = NoticePolicy.sanitize(TvNotice("notice", "Keep this", photo = NoticePhoto.Ready(ByteArray(NoticePolicy.MaxImageBytes + 1))))!!
        assertEquals(NoticePhoto.Unavailable, notice.photo)
        assertEquals("Keep this", notice.title)
    }

    @Test fun `photo bytes are owned by the notice after validation`() {
        val source = byteArrayOf(1, 2, 3)
        val notice = NoticePolicy.sanitize(TvNotice("notice", "Photo", photo = NoticePhoto.Ready(source)))!!
        source[0] = 9
        assertEquals(1, (notice.photo as NoticePhoto.Ready).jpeg[0].toInt())
    }

    @Test fun `blank and malformed notifications are ignored`() {
        assertNull(NoticePolicy.sanitize(TvNotice("notice", " ", "\n")))
        assertNull(NoticePolicy.sanitize(TvNotice("invalid id", "Title")))
        assertNull(NoticePolicy.sanitize(TvNotice("", "Title")))
    }

    @Test fun `zero timeout means normal duration rather than permanent input focus`() {
        assertEquals(8_000L, NoticePolicy.sanitize(TvNotice("notice", "Title", timeoutMillis = 0))!!.timeoutMillis)
        assertFalse(NoticePolicy.sanitize(TvNotice("notice", "Title", timeoutMillis = 0))!!.interactive)
        assertEquals(120_000L, NoticePolicy.sanitize(TvNotice("notice", "Title", timeoutMillis = Long.MAX_VALUE))!!.timeoutMillis)
    }

    @Test fun `large photos fit within the allowed dimensions without stretching`() {
        assertEquals(1280 to 720, NoticePolicy.fitImageSize(3840, 2160))
        assertEquals(405 to 720, NoticePolicy.fitImageSize(1080, 1920))
        assertEquals(640 to 480, NoticePolicy.fitImageSize(640, 480))
    }
}
