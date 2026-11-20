package app.flick.tv.platform

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.concurrent.thread
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class HttpClientCleanupTest {
    @Test fun idleConnectionIsClosedOnTheOwnedExecutorWithoutBlockingItsCaller() {
        val caller = Thread.currentThread()
        val closedOn = AtomicReference<Thread?>()
        val closeCount = AtomicInteger()
        val socketClosed = CountDownLatch(1)
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "Flick test HTTP disposal") }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(name = "Flick test HTTP server", isDaemon = true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                    flush()
                }
                releaseServer.await(5, TimeUnit.SECONDS)
            }
        }
        val sockets = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun close() {
                    closedOn.set(Thread.currentThread())
                    closeCount.incrementAndGet()
                    try { super.close() } finally { socketClosed.countDown() }
                }
            }
            override fun createSocket(host: String, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: InetAddress, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = createSocket().apply {
                bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port))
            }
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = createSocket().apply {
                bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port))
            }
        }
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).socketFactory(sockets)
            .protocols(listOf(Protocol.HTTP_1_1)).build()
        try {
            executor.execute {
                workerBlocked.countDown()
                releaseWorker.await(5, TimeUnit.SECONDS)
            }
            assertTrue(workerBlocked.await(2, TimeUnit.SECONDS))
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()).execute().use {
                assertEquals(200, it.code)
            }
            assertEquals(1, client.connectionPool.idleConnectionCount())

            client.closeAsync()
            // Disposal cannot run while its executor is blocked. The caller must not close a socket.
            assertNull(closedOn.get())
            assertEquals(1, client.connectionPool.idleConnectionCount())
            releaseWorker.countDown()

            assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
            assertNotSame(caller, closedOn.get())
            assertEquals("Flick test HTTP disposal", closedOn.get()?.name)
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(0, client.connectionPool.connectionCount())
            client.closeAsync()
            assertEquals(1, closeCount.get())
        } finally {
            releaseWorker.countDown()
            client.closeAsync()
            releaseServer.countDown()
            server.close()
            serverThread.join(2_000)
            executor.shutdownNow()
        }
    }
}
