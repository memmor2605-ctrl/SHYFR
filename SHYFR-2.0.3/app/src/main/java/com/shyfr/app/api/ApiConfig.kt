package com.shyfr.app.api

import java.net.URI

/**
 * Konfiguracja adresu serwera SHYFR 2.0. Adresu produkcyjnego NIE ma w kodzie: pochodzi z BuildConfig (-Pshyfr2ApiBaseUrl=...)
 * albo z ustawienia programisty. Wymagany HTTPS; zwykły HTTP wolno tylko do pętli zwrotnej/emulatora i tylko gdy wywołujący
 * wprost na to pozwoli (aplikacja robi to wyłącznie w buildzie debug).
 */
class ApiConfig private constructor(val baseUrl: String) {
    class Invalid(message: String) : IllegalArgumentException(message)

    fun url(path: String): String {
        require(path.startsWith("/")) { "ścieżka musi zaczynać się od /" }
        return baseUrl + path
    }

    companion object {
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]")

        fun parse(raw: String, allowInsecureLoopback: Boolean = false): ApiConfig {
            val s = raw.trim().trimEnd('/')
            if (s.isEmpty()) throw Invalid("Adres serwera nie jest ustawiony.")
            val u = try { URI(s) } catch (e: Exception) { throw Invalid("Niepoprawny adres serwera.") }
            val host = u.host?.lowercase() ?: throw Invalid("Niepoprawny adres serwera.")
            if (u.userInfo != null || u.query != null || u.fragment != null) throw Invalid("Adres serwera nie może zawierać danych logowania, zapytania ani fragmentu.")
            when (u.scheme?.lowercase()) {
                "https" -> Unit
                "http" -> if (!(allowInsecureLoopback && host in LOOPBACK)) throw Invalid("Wymagany jest HTTPS.")
                else -> throw Invalid("Wymagany jest HTTPS.")
            }
            return ApiConfig(s)
        }
    }
}
