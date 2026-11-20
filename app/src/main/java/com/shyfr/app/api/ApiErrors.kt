package com.shyfr.app.api

/** Błędy warstwy sieciowej SHYFR 2.0. Każdy ma [userMessage] do pokazania użytkownikowi (bez szczegółów technicznych). */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Brak sieci wykryty przed wysłaniem żądania. */
    class Offline : ApiException("offline")
    /** Połączenie nie doszło do skutku (DNS, TLS, zerwanie). */
    class Network(cause: Throwable) : ApiException("network", cause)
    class Timeout(cause: Throwable) : ApiException("timeout", cause)
    /** Brak zapisanej sesji. */
    class NotSignedIn : ApiException("not_signed_in")
    /** Zły login lub sekret (odpowiedź 401 na logowanie). */
    class InvalidCredentials : ApiException("invalid_credentials")
    /** Sesja nie istnieje już na serwerze (wygasła, wylogowana, urządzenie unieważnione, wykryto ponowne użycie tokenu). Tokeny lokalne są już usunięte. */
    class SessionRevoked : ApiException("session_revoked")
    class RateLimited(val serverMessage: String) : ApiException("rate_limited")
    /** Pozostałe błędy 4xx; [serverMessage] pochodzi z serwera (po polsku). */
    class Http(val status: Int, val serverMessage: String) : ApiException("http_$status")
    class Server(val status: Int) : ApiException("server_$status")
    class Malformed(cause: Throwable?) : ApiException("malformed", cause)
    /** Znacznik wewnętrzny: serwer odpowiedział 401. Klient zamienia go na [SessionRevoked] albo [InvalidCredentials] zależnie od kontekstu. */
    class Unauthorized401 : ApiException("unauthorized")

    fun userMessage(): String = when (this) {
        is Offline -> "Brak połączenia z internetem."
        is Network -> "Nie można połączyć się z serwerem. Sprawdź internet i spróbuj ponownie."
        is Timeout -> "Serwer nie odpowiada. Spróbuj ponownie za chwilę."
        is NotSignedIn -> "Nie jesteś zalogowany."
        is InvalidCredentials -> "Nieprawidłowy login lub hasło."
        is SessionRevoked -> "Sesja wygasła lub została zakończona. Zaloguj się ponownie."
        is RateLimited -> "Zbyt wiele prób. Odczekaj chwilę i spróbuj ponownie."
        is Http -> when (status) {
            409 -> "Ta nazwa użytkownika jest zajęta."
            404 -> "Nie znaleziono."
            413 -> "Dane są za duże."
            else -> serverMessage.ifBlank { "Serwer odrzucił żądanie ($status)." }
        }
        is Server -> "Błąd serwera ($status). Spróbuj ponownie później."
        is Malformed -> "Serwer zwrócił nieoczekiwaną odpowiedź."
        is Unauthorized401 -> "Sesja wygasła lub została zakończona. Zaloguj się ponownie."
    }

    /** Czy ponowienie tego samego żądania ma sens (błąd przejściowy). */
    val retryable: Boolean get() = this is Offline || this is Network || this is Timeout || this is Server || this is RateLimited
}
