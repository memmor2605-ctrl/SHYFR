package com.shyfr.app.api

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val readTimeoutMs: Int = 30_000,
)

class HttpResponse(val status: Int, val body: ByteArray)

/** Szew do testów jednostkowych logiki klienta. Produkcyjna implementacja: [UrlConnectionTransport]. */
interface HttpTransport {
    /** Zwraca odpowiedź dla KAŻDEGO kodu HTTP; rzuca [ApiException.Network]/[ApiException.Timeout] przy błędach połączenia. */
    fun execute(req: HttpRequest): HttpResponse
}

/**
 * HttpURLConnection z domyślnym magazynem zaufania systemu (weryfikacja certyfikatu i nazwy hosta NIE jest wyłączana).
 * Bez przekierowań (token nie może trafić pod inny adres). Odpowiedź ograniczona do [MAX_RESPONSE] bajtów.
 * Uwaga prywatności: to bezpośrednie połączenie HTTPS, serwer widzi adres IP klienta.
 */
class UrlConnectionTransport(private val connectTimeoutMs: Int = 10_000) : HttpTransport {
    override fun execute(req: HttpRequest): HttpResponse {
        val conn = try { URL(req.url).openConnection() as HttpURLConnection } catch (e: IOException) { throw ApiException.Network(e) }
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = req.readTimeoutMs
            conn.requestMethod = req.method
            conn.useCaches = false
            req.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (req.body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(req.body.size)
                conn.outputStream.use { it.write(req.body) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            return HttpResponse(code, stream?.use { readLimited(it) } ?: ByteArray(0))
        } catch (e: SocketTimeoutException) {
            throw ApiException.Timeout(e)
        } catch (e: IOException) {
            throw ApiException.Network(e)
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(s: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (true) {
            val n = s.read(buf); if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_RESPONSE) throw IOException("odpowiedź za duża")
        }
        return out.toByteArray()
    }

    companion object { const val MAX_RESPONSE = 1_000_000 }
}
