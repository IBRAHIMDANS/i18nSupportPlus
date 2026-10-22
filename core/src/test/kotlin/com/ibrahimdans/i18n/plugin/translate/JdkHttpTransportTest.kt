package com.ibrahimdans.i18n.plugin.translate

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.time.Duration

/** The JDK transport against a server on the loopback: what it sends, and how it reports what comes back. */
class JdkHttpTransportTest {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }

    private val url get() = "http://127.0.0.1:${server.address.port}/translate"

    /** What the server saw of the last request. */
    private class Seen(val method: String, val contentType: String?, val auth: String?, val body: String)

    private var seen: Seen? = null

    private fun answer(status: Int, body: String, delay: Duration = Duration.ZERO) {
        server.createContext("/translate") { exchange: HttpExchange ->
            seen = Seen(
                exchange.requestMethod,
                exchange.requestHeaders.getFirst("Content-Type"),
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            )
            Thread.sleep(delay.toMillis())
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a POST sends the JSON body and the headers, and returns the answer`() {
        answer(200, """{"text":"Café"}""")

        val response = JdkHttpTransport().post(url, mapOf("Authorization" to "DeepL-Auth-Key k"), """{"text":"Café"}""")

        assertEquals(HttpTransport.Response(200, """{"text":"Café"}"""), response)
        val request = seen!!
        assertEquals("POST", request.method)
        assertEquals("application/json", request.contentType)
        assertEquals("DeepL-Auth-Key k", request.auth)
        assertEquals("""{"text":"Café"}""", request.body, "UTF-8 both ways")
    }

    @Test
    fun `an error status is returned with its body, not thrown`() {
        answer(456, """{"message":"Quota exceeded"}""")

        assertEquals(HttpTransport.Response(456, """{"message":"Quota exceeded"}"""), JdkHttpTransport().post(url, emptyMap(), "{}"))
    }

    @Test
    fun `GET and PUT are sent as such`() {
        answer(200, "[]")

        JdkHttpTransport().request("GET", url, emptyMap(), null)
        assertEquals("GET", seen!!.method)
        assertEquals("", seen!!.body)

        JdkHttpTransport().request("PUT", url, emptyMap(), """{"entries":"a"}""")
        assertEquals("PUT", seen!!.method)
        assertEquals("""{"entries":"a"}""", seen!!.body)
    }

    @Test
    fun `an engine header overrides the JSON content type`() {
        answer(200, "")

        JdkHttpTransport().post(url, mapOf("Content-Type" to "application/json; charset=utf-8"), "{}")

        assertEquals("application/json; charset=utf-8", seen!!.contentType)
    }

    @Test
    fun `a verb other than POST, PUT and GET is a network error`() {
        assertThrows(IOException::class.java) { JdkHttpTransport().request("DELETE", url, emptyMap(), null) }
    }

    @Test
    fun `a reserved header or a malformed URL is a network error`() {
        assertThrows(IOException::class.java) { JdkHttpTransport().post(url, mapOf("Host" to "example.com"), "{}") }
        assertThrows(IOException::class.java) { JdkHttpTransport().post("not a url", emptyMap(), "{}") }
    }

    @Test
    fun `nobody listening is a network error`() {
        val gone = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
        val port = gone.address.port
        gone.stop(0)

        assertThrows(IOException::class.java) { JdkHttpTransport().post("http://127.0.0.1:$port/translate", emptyMap(), "{}") }
    }

    @Test
    fun `an answer too slow is a timeout, as the IDE transport reports it`() {
        answer(200, "late", delay = Duration.ofMillis(1_500))

        assertThrows(SocketTimeoutException::class.java) {
            JdkHttpTransport(readTimeout = Duration.ofMillis(200)).post(url, emptyMap(), "{}")
        }
    }
}
