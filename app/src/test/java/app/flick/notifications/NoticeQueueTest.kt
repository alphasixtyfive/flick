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

    @Test fun `late photo downloads cannot resurrect dismissed notices`() {
        val queue = NoticeQueue()
        queue.offer(notice("first"))
        queue.remove("first")
        assertFalse(queue.updateImage("first", byteArrayOf(1)))
        assertNull(queue.current)
    }

    @Test fun `image updates copy caller bytes and never reorder cards`() {
        val queue = NoticeQueue()
        queue.offer(notice("first"))
        queue.offer(notice("second"))
        val bytes = byteArrayOf(1)
        assertTrue(queue.updateImage("second", bytes))
        bytes[0] = 2
        assertNull(queue.current!!.imageJpeg)
        queue.remove("first")
        assertEquals(1, queue.current!!.imageJpeg!![0].toInt())
    }

    @Test fun `stopping the service clears every queued card`() {
        val queue = NoticeQueue()
        queue.offer(notice("first"))
        queue.offer(notice("second"))
        queue.clear()
        assertNull(queue.current)
        assertFalse(queue.updateImage("second", byteArrayOf(1)))
    }
}
