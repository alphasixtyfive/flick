package app.flick.notifications

import org.junit.Assert.*
import org.junit.Test

class NoticeQueueTest {
    private fun notice(id: String, title: String = id) = TvNotice(id, title)

    @Test fun `a full queue keeps the active notice and the newest pending notices`() {
        val queue = NoticeQueue(3)
        listOf("active", "old-pending", "new-pending", "newest").forEach { queue.offer(notice(it)) }
        assertEquals("active", queue.current!!.id)
        queue.remove("active")
        assertEquals("new-pending", queue.current!!.id)
        queue.remove("new-pending")
        assertEquals("newest", queue.current!!.id)
        queue.remove("newest")
        assertNull(queue.current)
    }

    @Test fun `updating a pending notice preserves the current card and queue position`() {
        val queue = NoticeQueue()
        queue.offer(notice("first"))
        queue.offer(notice("second"))
        queue.offer(notice("second", "Updated"))
        assertEquals("first", queue.current!!.id)
        queue.remove("first")
        assertEquals("Updated", queue.current!!.title)
        queue.remove("second")
        assertNull(queue.current)
    }

    @Test fun `offer identifies the exact delivery whose photo work is no longer needed`() {
        val queue = NoticeQueue(2)
        val firstPhoto = notice("camera").copy(photo = NoticePhoto.Loading())
        val nextPhoto = notice("camera").copy(photo = NoticePhoto.Loading())
        assertNull(queue.offer(notice("active")))
        assertNull(queue.offer(firstPhoto))
        assertSame(firstPhoto, queue.offer(nextPhoto))
        assertSame(nextPhoto, queue.offer(notice("newest")))
        assertNull(queue.finishPhoto("camera", nextPhoto.photo as NoticePhoto.Loading, byteArrayOf(1)))
    }

    @Test fun `late photo downloads cannot resurrect dismissed notices`() {
        val queue = NoticeQueue()
        val loading = NoticePhoto.Loading()
        queue.offer(notice("first").copy(photo = loading))
        queue.remove("first")
        assertNull(queue.finishPhoto("first", loading, byteArrayOf(1)))
        assertNull(queue.current)
    }

    @Test fun `image updates copy caller bytes and never reorder cards`() {
        val queue = NoticeQueue()
        val loading = NoticePhoto.Loading()
        queue.offer(notice("first"))
        queue.offer(notice("second").copy(photo = loading))
        val bytes = byteArrayOf(1)
        assertNotNull(queue.finishPhoto("second", loading, bytes))
        bytes[0] = 2
        assertEquals(NoticePhoto.None, queue.current!!.photo)
        queue.remove("first")
        assertEquals(1, (queue.current!!.photo as NoticePhoto.Ready).jpeg[0].toInt())
    }

    @Test fun `an older photo does not replace a newer delivery with the same ID`() {
        val queue = NoticeQueue()
        val old = NoticePhoto.Loading()
        val new = NoticePhoto.Loading()
        queue.offer(notice("doorbell").copy(photo = old))
        queue.offer(notice("doorbell").copy(photo = new))
        assertNull(queue.finishPhoto("doorbell", old, byteArrayOf(1)))
        assertSame(new, queue.current!!.photo)
        assertSame(NoticePhoto.Unavailable, queue.finishPhoto("doorbell", new, null))
        assertSame(NoticePhoto.Unavailable, queue.current!!.photo)
    }

    @Test fun `stopping the service clears every queued card`() {
        val queue = NoticeQueue()
        val loading = NoticePhoto.Loading()
        queue.offer(notice("first"))
        queue.offer(notice("second").copy(photo = loading))
        queue.clear()
        assertNull(queue.current)
        assertNull(queue.finishPhoto("second", loading, byteArrayOf(1)))
    }
}
