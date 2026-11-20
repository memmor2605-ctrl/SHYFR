package com.shyfr.app.api

import java.util.concurrent.CopyOnWriteArrayList

/** Test jednostkowy logiki klienta: zaskrypcowane odpowiedzi HTTP. To NIE jest test integracyjny (te używają prawdziwego backendu). */
class ScriptedTransport(private val handler: (HttpRequest) -> Any) : HttpTransport {
    val requests = CopyOnWriteArrayList<HttpRequest>()
    override fun execute(req: HttpRequest): HttpResponse {
        requests.add(req)
        return when (val r = handler(req)) {
            is HttpResponse -> r
            is ApiException -> throw r
            else -> error("zły wynik skryptu")
        }
    }
    fun paths() = requests.map { it.method + " " + it.url.substringAfter("//").substringAfter('/').let { p -> "/$p" } }
}

fun json(status: Int, body: String) = HttpResponse(status, body.toByteArray())
const val BASE = "https://api.test"
fun cfg() = ApiConfig.parse(BASE)
fun sessionOf(access: String = "A1", refresh: String = "R1", exp: Long) = StoredSession("acc", "dev", "alice", access, exp, refresh)
