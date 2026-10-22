package com.payriff.android.card

import com.payriff.android.PayriffResult
import com.payriff.android.internal.HttpRequest
import com.payriff.android.internal.HttpResponse
import com.payriff.android.internal.HttpTransport
import com.payriff.android.internal.card.CardCredentials
import com.payriff.android.internal.card.CardPlaintext
import com.payriff.android.internal.card.ThreeDsUi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.io.IOException

internal const val HOST = "https://api.payriff.com"
internal const val PUBLISHABLE_KEY = "pr_pk_live_TEST"
internal const val CLIENT_SECRET = "pr_cs_TEST"
internal const val APP_ID = "com.payriff.example.ios"

internal fun credentials(host: String = HOST, appId: String? = APP_ID) = CardCredentials(host, PUBLISHABLE_KEY, CLIENT_SECRET, appId)

/** A Client API success envelope around [payload] (JSON). */
internal fun ok(payload: String) = """{"code":"00000","message":"OK","route":"/api/v3/client/orders","responseId":"r-1","payload":$payload}"""

internal fun orderPayload(status: String = "CREATED", methods: String = "[\"CARD\",\"GOOGLE_PAY\"]") =
    """{"orderId":"o1","amount":10.5,"currency":"AZN","description":"Order #42","merchantName":"Test Merchant","status":"$status","language":"AZ","paymentMethods":$methods}"""

internal fun statusPayload(status: String) = """{"orderId":"o1","status":"$status","amount":10.5,"currency":"AZN"}"""

internal fun testCard(holder: String = "Unknown") = CardPlaintext(
    "4242424242424242".toCharArray(),
    holder.toCharArray(),
    "2030".toCharArray(),
    "12".toCharArray(),
    "987".toCharArray(),
)

/** An HTTP transport that answers from per-endpoint scripts and records every request. Nothing leaves the JVM. */
internal class FakeTransport(private val clock: () -> Long) : HttpTransport {

    sealed class Reply(val delayMillis: Long) {
        class Http(val status: Int, val body: String, delayMillis: Long = 0) : Reply(delayMillis)
        class NoResponse(delayMillis: Long = 0) : Reply(delayMillis)
        object Hang : Reply(0)
    }

    class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray?, val time: Long) {
        val bodyText: String? get() = body?.toString(Charsets.UTF_8)
    }

    val requests = mutableListOf<Recorded>()
    private val scripts = LinkedHashMap<String, MutableList<Reply>>()

    /** Replies for `METHOD path`, in order; the last one repeats. */
    fun on(method: String, path: String, vararg replies: Reply) {
        scripts["$method $path"] = replies.toMutableList()
    }

    fun requestsTo(path: String) = requests.filter { it.path == path }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val path = request.url.substringAfter("://").let { "/" + it.substringAfter('/') }
        // Copy the body now: the client zeroes it after the call.
        requests += Recorded(request.method, path, request.headers, request.body?.copyOf(), clock())
        val script = scripts["${request.method} $path"] ?: error("No reply scripted for ${request.method} $path")
        val reply = if (script.size > 1) script.removeAt(0) else script.single()
        delay(reply.delayMillis)
        return when (reply) {
            is Reply.Http -> HttpResponse(reply.status, reply.body)
            is Reply.NoResponse -> throw IOException("offline")
            Reply.Hang -> awaitCancellation()
        }
    }
}

internal fun http(status: Int, body: String, delayMillis: Long = 0) = FakeTransport.Reply.Http(status, body, delayMillis)

/** Records what the 3DS flow asks the sheet to do. */
internal class FakeThreeDsUi : ThreeDsUi {
    val events = mutableListOf<String>()
    val challenges = mutableListOf<Pair<String, String>>()
    val redirects = mutableListOf<String>()
    val methods = mutableListOf<Triple<String?, String?, String?>>()
    var closeButtonEnabled = true
    var pendingConfirmation: (() -> Unit)? = null
    var result: PayriffResult? = null
    var finishCount = 0

    override fun showLoader(verifying: Boolean) {
        events += if (verifying) "verifying" else "loader"
    }

    override fun showPage() {
        events += "page"
    }

    override fun postChallenge(url: String, body: ByteArray) {
        events += "challenge"
        challenges += url to body.toString(Charsets.US_ASCII)
    }

    override fun loadRedirect(url: String) {
        events += "redirect"
        redirects += url
    }

    override fun runMethod(methodUrl: String?, methodBody: ByteArray?, methodUrlServer: String?) {
        events += "method"
        methods += Triple(methodUrl, methodBody?.toString(Charsets.US_ASCII), methodUrlServer)
    }

    override fun destroyHiddenWebViews() {
        events += "destroyHidden"
    }

    override fun stopWebViews() {
        events += "stop"
    }

    override fun showPageLoadError() {
        events += "pageLoadError"
    }

    override fun confirmClose(onConfirm: () -> Unit) {
        events += "confirmClose"
        pendingConfirmation = onConfirm
    }

    override fun dismissCloseConfirmation() {
        pendingConfirmation = null
    }

    override fun setCloseEnabled(enabled: Boolean) {
        closeButtonEnabled = enabled
    }

    override fun finishWith(result: PayriffResult) {
        finishCount++
        this.result = result
    }

    fun confirm() {
        val confirmation = checkNotNull(pendingConfirmation) { "No confirmation dialog is showing" }
        pendingConfirmation = null
        confirmation()
    }
}
