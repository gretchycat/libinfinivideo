package io.github.gretchycat.infinivideo

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class VideoStreamServerTest {
    private class Resource(val size: Long?, val positional: Boolean = true, val actual: Long = size ?: 257,
        val short: Int = 65536, val failing: Boolean = false) : SourceResource {
        val opens = AtomicInteger(); val closes = AtomicInteger(); val readBytes = AtomicLong()
        override val metadata = StreamMetadata(name = "movie.mp4", length = size,
            capabilities = setOf(Capability.READ, Capability.REOPEN) + if (positional) setOf(Capability.POSITIONAL_READ) else emptySet())
        override fun open(): ByteSource {
            opens.incrementAndGet()
            if (failing) throw IOException("original source failure")
            val base = object : ByteSource {
                override val metadata = this@Resource.metadata
                var cursor = 0L
                val closed = java.util.concurrent.atomic.AtomicBoolean()
                fun at(position: Long, b: ByteArray, off: Int, len: Int): Int {
                    if (closed.get()) throw IOException("closed")
                    if (position >= actual) return -1
                    val count = minOf(len.toLong(), actual - position, short.toLong()).toInt()
                    for (i in 0 until count) b[off + i] = ((position + i) % 251).toByte()
                    readBytes.addAndGet(count.toLong()); return count
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = at(cursor, buffer, offset, length).also { if (it > 0) cursor += it }
                override fun close() { if (closed.compareAndSet(false, true)) closes.incrementAndGet() }
            }
            return if (!positional) base else object : ByteSource by base, PositionalReadable {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) = base.at(position, buffer, offset, length)
            }
        }
    }
    private data class Response(val code: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    }
    private fun request(url: String, range: String? = null, method: String = "GET"): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2000; connection.readTimeout = 2000; connection.requestMethod = method
        if (range != null) connection.setRequestProperty("Range", range)
        try {
            val code = connection.responseCode
            val body = if (method == "HEAD") byteArrayOf() else
                (if (code < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: byteArrayOf()
            return Response(code, connection.headerFields.filterKeys { it != null }, body)
        } finally { connection.disconnect() }
    }
    private fun expected(start: Long, size: Int) = ByteArray(size) { ((start + it) % 251).toByte() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
    @Test fun openingAndHeadDoNotReadOrOpenEncodedBody() {
        val r = Resource(5_000_000_000)
        VideoStreamServer(r).use { server ->
            assertEquals(0, r.opens.get()); assertEquals(0L, server.bytesRead)
            val response = request(server.url, method = "HEAD")
            assertEquals(200, response.code); assertEquals("5000000000", response.header("Content-Length"))
            assertEquals(0, r.opens.get())
        }
    }
    @Test fun distantRangeReadsOnlyRequestedBytesWithLongOffsets() {
        val r = Resource(5_000_000_000)
        VideoStreamServer(r).use { server ->
            val response = request(server.url, "bytes=4000000000-4000000009")
            assertEquals(206, response.code); assertArrayEquals(expected(4_000_000_000, 10), response.body)
            assertEquals("bytes 4000000000-4000000009/5000000000", response.header("Content-Range"))
            assertEquals(10L, r.readBytes.get()); await { r.closes.get() == 1 }
        }
    }
    @Test fun suffixAndClampedEndRangesAreExact() {
        VideoStreamServer(Resource(100)).use { server ->
            assertArrayEquals(expected(95, 5), request(server.url, "bytes=-5").body)
            assertArrayEquals(expected(98, 2), request(server.url, "bytes=98-9999").body)
            assertArrayEquals(expected(99, 1), request(server.url, "bytes=99-").body)
        }
    }
    @Test fun invalidAndMultipleRangesDoNotOpenSource() {
        val r = Resource(100)
        VideoStreamServer(r).use { server ->
            for (range in listOf("bytes=100-", "bytes=5-4", "bytes=-0", "bytes=1-2,4-5", "bytes=999999999999999999999-", "bits=1-2"))
                assertEquals(range, 416, request(server.url, range).code)
            assertEquals(0, r.opens.get())
        }
    }
    @Test fun tokenAndMethodsAreCheckedBeforeSourceOpen() {
        val r = Resource(100)
        VideoStreamServer(r).use { server ->
            assertEquals(404, request(server.url.substringBeforeLast('/') + "/wrong").code)
            assertEquals(405, request(server.url, method = "POST").code)
            assertEquals(0, r.opens.get())
        }
    }
    @Test fun sequentialRangesReplayOnlyRequiredPrefixInSmallChunks() {
        val r = Resource(2000, positional = false, short = 7)
        VideoStreamServer(r).use { server ->
            val response = request(server.url, "bytes=1000-1009")
            assertEquals(206, response.code); assertArrayEquals(expected(1000, 10), response.body)
            assertEquals(1010L, server.bytesRead); assertEquals(1000L, server.replayedBytes)
            assertEquals("sequential-replay", server.access)
        }
    }
    @Test fun unknownLengthUsesChunkedStreaming() {
        val r = Resource(null, positional = false, short = 3)
        VideoStreamServer(r).use { server ->
            val response = request(server.url)
            assertArrayEquals(expected(0, 257), response.body)
            assertEquals("chunked", response.header("Transfer-Encoding"))
            assertEquals("none", response.header("Accept-Ranges")); assertNull(response.header("Content-Length"))
        }
    }
    @Test fun shortPositionalReadsAreFilledWithoutDroppingBytes() {
        VideoStreamServer(Resource(257, short = 3)).use { server ->
            assertArrayEquals(expected(0, 257), request(server.url).body)
        }
    }
    @Test fun prematureEofIsRecordedAsFailure() {
        VideoStreamServer(Resource(100, actual = 20)).use { server ->
            runCatching { request(server.url) }
            await { server.lastFailure != null }
            assertTrue(server.lastFailure is EOFException)
        }
    }
    @Test fun openingFailurePreservesOriginalCauseAndReturnsError() {
        VideoStreamServer(Resource(100, failing = true)).use { server ->
            assertEquals(500, request(server.url).code)
            assertEquals("original source failure", server.lastFailure?.message)
        }
    }
    @Test fun concurrentRangesHaveIndependentOffsets() {
        val r = Resource(10000)
        VideoStreamServer(r).use { server ->
            val pool = Executors.newFixedThreadPool(2)
            try {
                val a = pool.submit<ByteArray> { request(server.url, "bytes=123-222").body }
                val b = pool.submit<ByteArray> { request(server.url, "bytes=8000-8099").body }
                assertArrayEquals(expected(123, 100), a.get(2, TimeUnit.SECONDS))
                assertArrayEquals(expected(8000, 100), b.get(2, TimeUnit.SECONDS))
            } finally { pool.shutdownNow() }
        }
    }
    @Test fun closeIsIdempotentAndDoesNotCloseBorrowedOwner() {
        val r = Resource(100)
        val original = r.open()
        val shared = SharedPositionalResource(original)
        val server = VideoStreamServer(shared)
        assertArrayEquals(expected(0, 5), request(server.url, "bytes=0-4").body)
        server.close(); server.close()
        assertEquals(0, r.closes.get())
        shared.close(); assertEquals(1, r.closes.get())
    }
    @Test fun closeCancelsBlockedActiveHandle() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = AtomicInteger()
        val resource = object : SourceResource {
            override val metadata = StreamMetadata(length = 1, capabilities = setOf(Capability.READ, Capability.REOPEN))
            override fun open() = object : ByteSource {
                override val metadata = StreamMetadata(length = 1)
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown(); release.await(2, TimeUnit.SECONDS); return -1
                }
                override fun close() { if (closed.incrementAndGet() == 1) release.countDown() }
            }
        }
        val server = VideoStreamServer(resource)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val job = pool.submit { runCatching { request(server.url) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); server.close()
            job.get(2, TimeUnit.SECONDS); assertTrue(closed.get() >= 1)
        } finally { server.close(); pool.shutdownNow() }
    }
    @Test fun emptySourceDoesNotOpenBody() {
        val r = Resource(0)
        VideoStreamServer(r).use { server ->
            assertEquals(0, request(server.url).body.size)
            assertEquals(416, request(server.url, "bytes=0-").code)
            assertEquals(0, r.opens.get())
        }
    }
    @Test fun unknownLengthRangeDoesNotInventRandomAccess() {
        VideoStreamServer(Resource(null, positional = false)).use { server ->
            val response = request(server.url, "bytes=100-")
            assertEquals(200, response.code); assertArrayEquals(expected(0, 257), response.body)
            assertEquals("sequential-source", server.access)
        }
    }
    @Test fun metadataPreservesIdentityAndMarksReplayCost() {
        val r = Resource(100, positional = false)
        VideoStreamServer(r).use { server ->
            assertEquals(r.metadata.capabilities, server.metadata.capabilities)
            assertEquals(r.metadata.provenance, server.metadata.provenance)
            assertTrue(server.metadata.warnings.any { "replays" in it })
            assertEquals("loopback-http", server.metadata.attributes["video.transport"])
        }
    }
    @Test fun rangeParserHandlesBoundsAndOverflow() {
        assertEquals(0L..9L, parseVideoRange("bytes=-100", 10))
        assertEquals((Long.MAX_VALUE-2)..(Long.MAX_VALUE-1), parseVideoRange("bytes=${Long.MAX_VALUE-2}-", Long.MAX_VALUE))
        assertNull(parseVideoRange("bytes=0-", 0)); assertNull(parseVideoRange("bytes=-", 10))
        assertNull(parseVideoRange("bytes=0-18446744073709551615", 10))
    }
    @Test fun slowResponsesDoNotBlockNewRangeRequests() {
        val r = Resource(1_000_000_000)
        VideoStreamServer(r).use { server ->
            val url = URL(server.url)
            val slow = mutableListOf<Socket>()
            try {
                repeat(2) {
                    val socket = Socket()
                    socket.receiveBufferSize = 1024; socket.soTimeout = 2000
                    socket.connect(java.net.InetSocketAddress(url.host, url.port), 2000)
                    slow.add(socket)
                    socket.getOutputStream().write("GET ${url.path} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    val input = socket.getInputStream()
                    var header = ""
                    while (!header.endsWith("\r\n\r\n")) {
                        val value = input.read(); assertTrue(value >= 0)
                        header += value.toChar(); assertTrue(header.length < 16384)
                    }
                }
                val response = request(server.url, "bytes=900000000-900000009")
                assertEquals(206, response.code)
                assertArrayEquals(expected(900_000_000, 10), response.body)
                assertTrue("no complete source read", r.readBytes.get() < 2_000_000)
            } finally { slow.forEach { it.close() } }
        }
    }
    @Test fun duplicateRangeHeadersAreRejected() {
        val r = Resource(100)
        VideoStreamServer(r).use { server ->
            val url = URL(server.url)
            Socket(url.host, url.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("GET ${url.path} HTTP/1.1\r\nRange: bytes=0-1\r\nRange: bytes=2-3\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("400"))
            }
            assertEquals(0, r.opens.get())
        }
    }
}
