package com.payriff.android.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor

internal class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val timeoutMillis: Int,
)

/** [headers]: the response headers by name, the values of a repeated name joined with `,`. Only logged (CONTRACT §11). */
internal class HttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** Executes HTTP requests. Throws [IOException] when there is no HTTP response. Cancellable. */
internal fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * [HttpTransport] on `HttpURLConnection`. Cancelling the calling coroutine disconnects the connection, so a
 * blocked read ends immediately. POST bodies use fixed-length streaming, so the platform never replays them.
 */
internal class UrlConnectionTransport(
    private val executor: Executor = Dispatchers.IO.asExecutor(),
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse = suspendCancellableCoroutine { continuation ->
        val call = Call(request)
        continuation.invokeOnCancellation { call.cancel() }
        executor.execute {
            // A resume after cancellation is ignored by the continuation.
            continuation.resumeWith(runCatching { call.run() })
        }
    }

    private class Call(private val request: HttpRequest) {
        @Volatile
        private var connection: HttpURLConnection? = null

        @Volatile
        private var cancelled = false

        fun cancel() {
            cancelled = true
            connection?.disconnect()
        }

        fun run(): HttpResponse {
            if (cancelled) throw IOException("Canceled")
            val connection = URL(request.url).openConnection() as HttpURLConnection
            this.connection = connection
            try {
                if (cancelled) throw IOException("Canceled")
                connection.requestMethod = request.method
                connection.connectTimeout = request.timeoutMillis
                connection.readTimeout = request.timeoutMillis
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                for ((name, value) in request.headers) connection.setRequestProperty(name, value)
                val body = request.body
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = try {
                    connection.responseCode
                } catch (e: HttpRetryException) {
                    // The JDK (not Android) refuses to read 401/407 bodies in streaming mode.
                    return HttpResponse(e.responseCode(), "")
                }
                if (status < 0) throw IOException("Invalid HTTP response")
                val stream: InputStream? = if (status < HttpURLConnection.HTTP_BAD_REQUEST) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val text = stream?.use { readLimited(it) }.orEmpty()
                return HttpResponse(status, text, responseHeaders(connection))
            } finally {
                connection.disconnect()
            }
        }

        /** The response headers; the status line, which `HttpURLConnection` lists under a `null` name, is left out. */
        private fun responseHeaders(connection: HttpURLConnection): Map<String, String> {
            val headers = LinkedHashMap<String, String>()
            for ((name, values) in connection.headerFields) {
                if (name != null && values != null) headers[name] = values.joinToString(",")
            }
            return headers
        }

        private fun readLimited(stream: InputStream): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_BODY_BYTES) throw IOException("Response body is too large")
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
    }
}
