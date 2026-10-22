package com.payriff.android.testing

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A minimal HTTP/1.1 server on a local ServerSocket. Android unit tests compile against android.jar, where
 * `com.sun.net.httpserver` is not visible, so tests use this instead. No request ever leaves the machine.
 */
class FakeHttpServer : AutoCloseable {

    class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    class Reply(val status: Int, val body: String, val delayMillis: Long = 0)

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val connections = AtomicInteger()

    val requests: MutableList<Recorded> = CopyOnWriteArrayList()

    @Volatile
    var handler: (Recorded) -> Reply = { Reply(200, "{}") }

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    val connectionCount: Int get() = connections.get()

    init {
        thread(isDaemon = true, name = "fake-http-accept") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break
                }
                connections.incrementAndGet()
                thread(isDaemon = true, name = "fake-http-client") { serve(client) }
            }
        }
    }

    fun reply(status: Int, body: String, delayMillis: Long = 0) {
        handler = { Reply(status, body, delayMillis) }
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            try {
                val input = BufferedInputStream(connection.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val requestLine = lines.first().split(" ")
                val headers = lines.drop(1).filter { ':' in it }
                    .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(bytes, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val recorded = Recorded(requestLine[0], requestLine[1], headers, String(bytes, Charsets.UTF_8))
                requests += recorded
                val reply = handler(recorded)
                if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
                val payload = reply.body.toByteArray(Charsets.UTF_8)
                val out = connection.getOutputStream()
                out.write(
                    (
                        "HTTP/1.1 ${reply.status} Status\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Content-Length: ${payload.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII),
                )
                out.write(payload)
                out.flush()
            } catch (e: IOException) {
                // The client went away (cancellation or timeout tests).
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun readHead(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        return buffer.toString(Charsets.US_ASCII.name()).trimEnd()
    }

    override fun close() {
        socket.close()
    }
}
