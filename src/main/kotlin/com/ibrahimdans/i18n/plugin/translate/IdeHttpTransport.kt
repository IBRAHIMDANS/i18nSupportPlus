package com.ibrahimdans.i18n.plugin.translate

import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.HttpURLConnection

/** The platform's HTTP client: the IDE's proxy settings apply. */
object IdeHttpTransport : HttpTransport {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val JSON = "application/json"

    override fun post(url: String, headers: Map<String, String>, body: String): HttpTransport.Response =
        request("POST", url, headers, body)

    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpTransport.Response =
        when (method) {
            "POST" -> HttpRequests.post(url, JSON)
            "PUT" -> HttpRequests.put(url, JSON)
            "GET" -> HttpRequests.request(url)
            else -> throw IOException("HTTP $method is not supported here")
        }
            .tuner { connection -> headers.forEach { (name, value) -> connection.setRequestProperty(name, value) } }
            .connectTimeout(CONNECT_TIMEOUT_MS)
            .readTimeout(READ_TIMEOUT_MS)
            .throwStatusCodeException(false)
            .connect { request ->
                if (body != null) request.write(body)
                val connection = request.connection as HttpURLConnection
                val status = connection.responseCode
                val text = if (status in 200..299) request.readString()
                else connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                HttpTransport.Response(status, text)
            }
}
