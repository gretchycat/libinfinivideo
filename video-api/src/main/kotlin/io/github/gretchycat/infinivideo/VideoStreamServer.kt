package io.github.gretchycat.infinivideo

import io.github.gretchycat.infinistream.*
import java.io.*
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Borrows a resource for bounded native HTTP reads. Positional handles serve ranges
 * directly; sequential sources replay only a requested prefix. No disk staging.
 * Nonblocking writes keep paused clients from occupying the two source workers.
 * Close this transport before closing the caller's resource owner.
 */
class VideoStreamServer(private val resource: SourceResource) : Closeable {
    private val description = resource.metadata
    private val closed = AtomicBoolean(false)
    private val handles = ConcurrentHashMap.newKeySet<ByteSource>()
    private val clients = ConcurrentHashMap.newKeySet<Client>()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "InfiniVideo-read").apply { isDaemon = true } })
    private val selector = Selector.open()
    private val server = ServerSocketChannel.open().apply {
        configureBlocking(false)
        bind(InetSocketAddress("127.0.0.1", 0), 8)
        register(selector, SelectionKey.OP_ACCEPT)
    }
    private val extension = description.name?.substringAfterLast('.', "")?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
    private val path = "/${UUID.randomUUID().toString().replace("-", "")}/media" + (extension?.let { ".$it" } ?: "")
    val url = "http://127.0.0.1:${(server.localAddress as InetSocketAddress).port}$path"
    private val size = description.length
    private val ranges = size != null && (Capability.POSITIONAL_READ in description.capabilities || Capability.REOPEN in description.capabilities)
    val access = when {
        Capability.POSITIONAL_READ in description.capabilities -> "positional-source"
        ranges -> "sequential-replay"
        else -> "sequential-source"
    }
    val metadata = description.copy(attributes = description.attributes + mapOf(
        "video.transport" to "loopback-http", "video.access" to access),
        warnings = description.warnings + if (access == "sequential-replay")
            listOf("Seeking this sequential source replays the encoded prefix") else emptyList())
    private val readCount = AtomicLong()
    private val replayCount = AtomicLong()
    private val requestCount = AtomicLong()
    val bytesRead get() = readCount.get()
    val replayedBytes get() = replayCount.get()
    val requests get() = requestCount.get()
    @Volatile var lastFailure: IOException? = null
        private set

    private class Client(val channel: SocketChannel, val key: SelectionKey) {
        val closed = AtomicBoolean(false)
        val header = ByteBuffer.allocate(16384)
        val pending = java.util.ArrayDeque<ByteBuffer>()
        val acceptedAt = System.nanoTime()
        @Volatile var source: ByteSource? = null
        var parsed = false
        var reading = false
        var ending = false
        var headersSent = false
        var inputEnded = false
        var position = 0L
        var remaining: Long? = null
        var chunked = false
    }
    init { Thread({ loop() }, "InfiniVideo-http").apply { isDaemon = true; start() } }

    private fun loop() {
        try {
            while (!closed.get()) {
                while (true) (commands.poll() ?: break).invoke()
                selector.select(1000)
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next(); keys.remove()
                    if (!key.isValid) continue
                    try {
                        if (key.isAcceptable) {
                            val channel = server.accept() ?: continue
                            if (clients.size >= 8) { channel.close(); continue }
                            channel.configureBlocking(false); channel.socket().sendBufferSize = 65536
                            val client = Client(channel, channel.register(selector, SelectionKey.OP_READ))
                            client.key.attach(client); clients.add(client)
                            if (closed.get()) closeClient(client)
                        } else {
                            val client = key.attachment() as Client
                            if (key.isReadable) readHeader(client)
                            if (key.isValid && key.isWritable) write(client)
                        }
                    } catch (_: Exception) { (key.attachment() as? Client)?.let(::closeClient) }
                }
                clients.filter { !it.parsed && System.nanoTime() - it.acceptedAt > TimeUnit.SECONDS.toNanos(10) }.forEach(::closeClient)
            }
        } catch (_: IOException) { /* Closing the listener ends the session. */ }
        finally { close(); runCatching { selector.close() }; commands.clear() }
    }
    private fun interests(client: Client, writable: Boolean = client.pending.isNotEmpty()) {
        if (!client.closed.get() && client.key.isValid)
            client.key.interestOps((if (client.inputEnded) 0 else SelectionKey.OP_READ) or (if (writable) SelectionKey.OP_WRITE else 0))
    }
    private fun post(client: Client, action: () -> Unit) {
        if (closed.get() || client.closed.get()) { closeClient(client); return }
        commands.add {
            if (!closed.get() && !client.closed.get()) try { action() } catch (_: Exception) { closeClient(client) }
            else closeClient(client)
        }
        selector.wakeup()
    }
    private fun readHeader(client: Client) {
        if (client.parsed) {
            if (client.channel.read(ByteBuffer.allocate(1)) < 0) { client.inputEnded = true; interests(client) }
            return
        }
        val count = client.channel.read(client.header)
        if (count < 0) { closeClient(client); return }
        val text = String(client.header.array(), 0, client.header.position(), Charsets.ISO_8859_1)
        val end = text.indexOf("\r\n\r\n")
        if (end < 0) { if (!client.header.hasRemaining()) reply(client, 431, "Request Header Fields Too Large"); return }
        client.parsed = true
        val lines = text.substring(0, end).split("\r\n")
        val request = lines.first().split(' ')
        if (request.size != 3 || request[2] !in setOf("HTTP/1.0", "HTTP/1.1")) { reply(client, 400, "Bad Request"); return }
        if (request[1] != path) { reply(client, 404, "Not Found"); return }
        if (request[0] !in setOf("GET", "HEAD")) { reply(client, 405, "Method Not Allowed"); return }
        val headers = lines.drop(1).filter { it.substringBefore(':').equals("Range", true) }
        if (headers.size > 1) { reply(client, 400, "Bad Request"); return }
        val requested = headers.singleOrNull()?.substringAfter(':')?.trim()
        val range = if (requested != null && ranges) parseVideoRange(requested, size!!) else null
        if (requested != null && ranges && range == null) { reply(client, 416, "Range Not Satisfiable", "Content-Range: bytes */$size\r\n"); return }
        requestCount.incrementAndGet()
        client.position = range?.first ?: 0L
        client.remaining = range?.let { it.last - it.first + 1 } ?: size
        client.chunked = client.remaining == null
        val mime = description.content.mimeType?.takeIf { it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) } ?: "application/octet-stream"
        val response = "HTTP/1.1 ${if (range != null) "206 Partial Content" else "200 OK"}\r\n" +
            "Content-Type: $mime\r\nAccept-Ranges: ${if (ranges) "bytes" else "none"}\r\n" +
            (client.remaining?.let { "Content-Length: $it\r\n" } ?: "Transfer-Encoding: chunked\r\n") +
            (range?.let { "Content-Range: bytes ${it.first}-${it.last}/$size\r\n" } ?: "") + "Connection: close\r\n\r\n"
        if (request[0] == "HEAD" || client.remaining == 0L) {
            client.pending.add(ascii(response)); client.ending = true; interests(client); return
        }
        client.reading = true
        submit(client) {
            val source = resource.open()
            handles.add(source); client.source = source
            if (closed.get() || client.closed.get()) { closeSource(source); return@submit }
            if (size != null && source.metadata.length != null && size != source.metadata.length) throw IOException("Video source length changed")
            if (client.position > 0 && source !is PositionalReadable) skipPrefix(client, source, client.position)
            post(client) { client.reading = false; client.pending.add(ascii(response)); interests(client) }
        }
    }
    private fun write(client: Client) {
        while (client.pending.isNotEmpty()) {
            val buffer = client.pending.first()
            if (client.channel.write(buffer) == 0) return
            client.headersSent = true
            if (!buffer.hasRemaining()) client.pending.removeFirst()
        }
        if (client.ending) { closeClient(client); return }
        interests(client, false)
        if (!client.reading) readChunk(client)
    }
    private fun readChunk(client: Client) {
        if (client.remaining == 0L) { closeClient(client); return }
        client.reading = true
        val position = client.position
        val wanted = minOf(65536L, client.remaining ?: 65536L).toInt()
        submit(client) {
            val source = client.source ?: throw IOException("Video source closed")
            val bytes = ByteArray(wanted)
            val count = if (source is PositionalReadable) source.readAt(position, bytes, 0, wanted) else source.read(bytes, 0, wanted)
            if (count == -1) {
                if (client.remaining != null) throw EOFException("Video source ended before declared length")
                post(client) { client.reading = false; client.ending = true; client.pending.add(ascii("0\r\n\r\n")); interests(client) }
            } else {
                if (count <= 0 || count > wanted) throw IOException("Invalid video source read: $count")
                readCount.addAndGet(count.toLong())
                post(client) {
                    client.reading = false; client.position += count; client.remaining = client.remaining?.minus(count)
                    if (client.chunked) client.pending.add(ascii(count.toString(16) + "\r\n"))
                    client.pending.add(ByteBuffer.wrap(bytes, 0, count))
                    if (client.chunked) client.pending.add(ascii("\r\n"))
                    interests(client)
                }
            }
        }
    }
    private fun submit(client: Client, action: () -> Unit) {
        try { workers.execute {
            try { if (!closed.get() && !client.closed.get()) action() }
            catch (error: Exception) {
                if (!closed.get() && !client.closed.get()) {
                    lastFailure = error as? IOException ?: IOException("Video source read failed", error)
                    post(client) { if (!client.headersSent) reply(client, 500, "Source Read Failed") else closeClient(client) }
                }
            }
        } } catch (_: RejectedExecutionException) { closeClient(client) }
    }
    private fun skipPrefix(client: Client, source: ByteSource, offset: Long) {
        val bytes = ByteArray(65536); var remaining = offset
        while (remaining > 0) {
            if (closed.get() || client.closed.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Video stream closed")
            val wanted = minOf(remaining, bytes.size.toLong()).toInt()
            val count = source.read(bytes, 0, wanted)
            if (count == -1) throw EOFException("Video source ended before seek position")
            if (count <= 0 || count > wanted) throw IOException("Invalid video prefix read: $count")
            readCount.addAndGet(count.toLong()); replayCount.addAndGet(count.toLong()); remaining -= count
        }
    }
    private fun ascii(text: String) = ByteBuffer.wrap(text.toByteArray(Charsets.US_ASCII))
    private fun reply(client: Client, code: Int, reason: String, extra: String = "") {
        client.parsed = true; client.pending.clear(); client.ending = true
        client.pending.add(ascii("HTTP/1.1 $code $reason\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n")); interests(client)
    }
    private fun closeSource(source: ByteSource) { if (handles.remove(source)) runCatching { source.close() } }
    private fun closeClient(client: Client) {
        if (!client.closed.compareAndSet(false, true)) return
        clients.remove(client); client.key.cancel(); runCatching { client.channel.close() }; client.source?.let(::closeSource)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }; selector.wakeup()
        clients.forEach(::closeClient); handles.forEach(::closeSource); workers.shutdownNow()
    }
}

internal fun parseVideoRange(header: String, size: Long): LongRange? {
    if (size <= 0 || !header.startsWith("bytes=", true) || ',' in header) return null
    val parts = header.substring(6).split('-')
    if (parts.size != 2) return null
    if (parts[0].isEmpty()) {
        val suffix = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (size - minOf(size, suffix))..(size - 1)
    }
    val start = parts[0].toLongOrNull()?.takeIf { it >= 0 && it < size } ?: return null
    val end = if (parts[1].isEmpty()) size - 1 else parts[1].toLongOrNull() ?: return null
    if (end < start) return null
    return start..minOf(end, size - 1)
}
