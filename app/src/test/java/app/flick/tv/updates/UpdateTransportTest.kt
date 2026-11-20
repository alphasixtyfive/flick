package app.flick.tv.updates

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Application interceptors exercise the real async transport without opening a socket. */
class UpdateTransportTest {
    private val clients = mutableListOf<OkHttpClient>()
    private val url = UpdatePolicy.metadataUrl(123L)

    @After fun closeClients() {
        clients.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
    }

    @Test fun cancellingTheCoroutineCancelsTheCallAndClosesItsResponse() = runBlocking {
        val body = WaitingBody()
        val client = client(body, onCancel = { body.cancelled.countDown() })
        val request = async(start = CoroutineStart.UNDISPATCHED) { UpdateDownload(client).metadata(url) }
        assertTrue("Response reading did not start", await(body.started))
        request.cancelAndJoin()
        assertTrue("OkHttp call was not cancelled", await(body.cancelled))
        assertTrue("Cancelled response was not closed", await(body.closed))
        assertTrue(request.isCancelled)
    }

    @Test fun alreadyCancelledCoroutineDoesNotEnqueueARequest() = runBlocking {
        val started = AtomicInteger()
        val client = client("".toResponseBody(), onStart = { started.incrementAndGet() })
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            UpdateDownload(client).metadata(url)
        }
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(0, started.get())
    }

    @Test fun timeoutRemainsCancellationAndClosesTheResponse() = runBlocking {
        val body = WaitingBody()
        val client = client(body, onCancel = { body.cancelled.countDown() })
        try {
            withTimeout(1_000) { UpdateDownload(client).metadata(url) }
            fail("A blocked request did not time out")
        } catch (_: TimeoutCancellationException) { }
        assertEquals("Response reading never started", 0L, body.started.count)
        assertTrue("Timeout did not cancel OkHttp", await(body.cancelled))
        assertTrue("Timed-out response was not closed", await(body.closed))
    }

    @Test fun metadataWithoutContentLengthStillHasABoundedRead() = runBlocking {
        val body = StreamBody(ByteArrayInputStream(ByteArray(UpdatePolicy.MAX_METADATA_BYTES + 1)))
        try {
            UpdateDownload(client(body)).metadata(url)
            fail("Oversized metadata was accepted")
        } catch (failure: UpdateException) {
            assertEquals("The release information is too large.", failure.message)
        }
        assertTrue(await(body.closed))
    }

    @Test fun untrustedRedirectIsRejectedBeforeAnotherRequest() = runBlocking {
        val count = AtomicInteger()
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            count.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://downloads.example.test/flick.apk")
                .body("".toResponseBody()).build()
        }.build().also(clients::add)
        val release = UpdateRelease("0.2.19", "v0.2.19",
            "https://github.com/example/flick/releases/download/v0.2.19/Flick-0.2.19.apk", "a".repeat(64), 3)
        val file = java.io.File.createTempFile("flick-transport-", ".apk")
        try {
            UpdateDownload(client).apk(release, file) {}
            fail("A redirect to an unrelated host was accepted")
        } catch (failure: UpdateException) {
            assertEquals("The update address is not supported.", failure.message)
        } finally { file.delete() }
        assertEquals(1, count.get())
    }

    @Test fun rateLimitsAreReportedAndTheResponseIsClosed() = runBlocking {
        val body = StreamBody(ByteArrayInputStream("Rate limited".toByteArray()))
        try {
            UpdateDownload(client(body, code = 429)).metadata(url)
            fail("A rate-limited request was accepted")
        } catch (failure: UpdateException) {
            assertEquals("GitHub is limiting requests. Try again later.", failure.message)
        }
        assertTrue(await(body.closed))
    }

    private fun client(body: ResponseBody, code: Int = 200, onCancel: () -> Unit = {}, onStart: () -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().followRedirects(false).eventListener(object : EventListener() {
            override fun canceled(call: Call) { onCancel() }
            override fun callStart(call: Call) { onStart() }
        }).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test response")
                .body(body).build()
        }.build().also(clients::add)

    private suspend fun await(latch: CountDownLatch): Boolean = withContext(Dispatchers.IO) {
        latch.await(5, TimeUnit.SECONDS)
    }

    private open class StreamBody(input: InputStream) : ResponseBody() {
        val closed = CountDownLatch(1)
        private val stream = object : java.io.FilterInputStream(input) {
            override fun close() {
                try { super.close() } finally { closed.countDown() }
            }
        }.source().buffer()
        override fun contentType() = "application/json".toMediaType()
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = stream
    }

    private class WaitingBody private constructor(
        val started: CountDownLatch,
        val cancelled: CountDownLatch,
    ) : StreamBody(object : InputStream() {
        override fun read(): Int = read(ByteArray(1), 0, 1)
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            started.countDown()
            if (!cancelled.await(5, TimeUnit.SECONDS)) throw IOException("Test request did not cancel")
            throw IOException("Cancelled")
        }
    }) {
        constructor() : this(CountDownLatch(1), CountDownLatch(1))
    }
}
