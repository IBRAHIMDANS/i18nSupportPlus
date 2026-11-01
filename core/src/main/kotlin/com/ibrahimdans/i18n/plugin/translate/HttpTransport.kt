package com.ibrahimdans.i18n.plugin.translate

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** One HTTP exchange, so tests never reach the network. */
fun interface HttpTransport {
    /** [retryAfter] is the answer's `Retry-After` header as written, null when it has none. */
    data class Response(val status: Int, val body: String, val retryAfter: String? = null)

    fun post(url: String, headers: Map<String, String>, body: String): Response

    /**
     * [post], waiting up to [readTimeout] for the answer: a batch of texts takes an LLM longer than one.
     * A transport with a fixed timeout keeps its own.
     */
    fun post(url: String, headers: Map<String, String>, body: String, readTimeout: Duration): Response = post(url, headers, body)

    /**
     * Another verb — `GET`, `PUT` — for what an engine needs besides translating: a DeepL glossary.
     * A transport that only knows [post] refuses the others as a network error.
     */
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): Response =
        if (method == "POST") post(url, headers, body.orEmpty()) else throw IOException("HTTP $method is not supported here")
}

/**
 * The JDK's HTTP client, for a caller without an IDE. Inside the IDE the plugin passes its own
 * transport instead, which follows the IDE's proxy settings.
 *
 * It answers as that one does: a JSON body, [connectTimeout] to connect and [readTimeout] for the
 * answer, any status returned with its body rather than thrown, a timeout as a
 * [SocketTimeoutException] and every other failure as an [IOException]. What differs: the proxy is
 * the JVM's ([java.net.ProxySelector.getDefault]), the user agent is the JDK's, and [readTimeout]
 * bounds the wait for the answer's headers rather than each read of its body.
 */
class JdkHttpTransport(
    private val connectTimeout: Duration = Duration.ofSeconds(10),
    private val readTimeout: Duration = Duration.ofSeconds(60)
) : HttpTransport {

    // HTTP/1.1 like HttpURLConnection: an h2c upgrade on a plain-HTTP local server is not worth the risk.
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(connectTimeout)
            .build()
    }

    override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response =
        request("POST", url, headers, body)

    override fun post(url: String, headers: Map<String, String>, body: String, readTimeout: Duration): HttpTransport.Response =
        send("POST", url, headers, body, readTimeout)

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpTransport.Response =
        send(method, url, headers, body, readTimeout)

    private fun send(method: String, url: String, headers: Map<String, String>, body: String?, readTimeout: Duration): HttpTransport.Response {
        if (method !in METHODS) throw IOException("HTTP $method is not supported here")
        val request = try {
            HttpRequest.newBuilder(URI.create(url))
                .timeout(readTimeout)
                .apply { if (method != "GET") setHeader("Content-Type", JSON) }
                .apply { headers.forEach { (name, value) -> setHeader(name, value) } }
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it, Charsets.UTF_8) } ?: HttpRequest.BodyPublishers.noBody())
                .build()
        } catch (e: IllegalArgumentException) {
            // A malformed URL, or a header the JDK reserves (Host, Content-Length): a request that cannot go out.
            throw IOException(e.message, e)
        }
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            HttpTransport.Response(response.statusCode(), response.body().orEmpty(), response.headers().firstValue(RETRY_AFTER).orElse(null))
        } catch (e: HttpTimeoutException) {
            throw SocketTimeoutException(e.message).apply { initCause(e) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException(e.message).apply { initCause(e) }
        }
    }

    private companion object {
        const val JSON = "application/json"
        const val RETRY_AFTER = "Retry-After"
        val METHODS = setOf("POST", "PUT", "GET")
    }
}
