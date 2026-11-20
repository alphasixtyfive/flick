package app.flick.tv.homeassistant

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

class HaTransportTest {
    private val clients = mutableListOf<OkHttpClient>()
    private val credentials = HaCredentials("https://home.example.test", "test-only-token")

    @After fun closeClients() {
        clients.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
    }

    @Test fun `an already cancelled service command never starts an HTTP call`() = runBlocking {
        val starts = AtomicInteger()
        val client = client("".toResponseBody(), object : EventListener() {
            override fun callStart(call: Call) { starts.incrementAndGet() }
        })
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().job.cancel()
            HaTransport(client).request(credentials, "/api/services/light/turn_on",
                "{\"entity_id\":\"light.example\"}")
            fail("A cancelled command returned normally")
        }
        command.join()
        assertTrue(command.isCancelled)
        assertEquals(0, starts.get())
    }

    @Test fun `cancelling an active read cancels the HTTP call and closes its response`() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val body = object : ResponseBody() {
            private val stream = object : InputStream() {
                override fun read(): Int = read(ByteArray(1), 0, 1)
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    started.countDown()
                    if (!cancelled.await(5, TimeUnit.SECONDS)) throw IOException("Test request did not cancel")
                    throw IOException("Cancelled")
                }
                override fun close() { closed.countDown() }
            }.source().buffer()
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = stream
        }
        val client = client(body, object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        })
        val request = async(start = CoroutineStart.UNDISPATCHED) { HaTransport(client).request(credentials, "/api/") }
        assertTrue("Response reading did not start", await(started))
        request.cancelAndJoin()
        assertTrue("OkHttp call was not cancelled", await(cancelled))
        assertTrue("Response was not closed", await(closed))
        assertTrue(request.isCancelled)
    }

    private fun client(body: ResponseBody, listener: EventListener): OkHttpClient =
        OkHttpClient.Builder().followRedirects(false).eventListener(listener).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Test response")
                .body(body).build()
        }.build().also(clients::add)

    private suspend fun await(latch: CountDownLatch): Boolean = withContext(Dispatchers.IO) {
        latch.await(5, TimeUnit.SECONDS)
    }
}
