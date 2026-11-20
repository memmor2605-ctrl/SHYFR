package com.shyfr.app.api

import com.google.crypto.tink.subtle.Hkdf
import com.shyfr.app.crypto.Kdf
import com.shyfr.app.crypto.Keys
import java.security.MessageDigest
import java.util.Base64

/**
 * Sekret uwierzytelnienia SHYFR 2.0 liczony NA URZĄDZENIU: Argon2id(login, hasło, sól zależna od loginu), potem HKDF z etykietą
 * domenową. Do serwera idzie tylko wynik; hasło nigdy. Serwer dodatkowo haszuje go scryptem.
 * Etykieta "shyfr2-auth-secret-v1" oddziela ten sekret od kluczy 1.x (konto, odzyskiwanie) wyprowadzanych z tych samych danych.
 */
object V2Credentials {
    fun normalizeUsername(u: String) = u.trim().lowercase()

    fun saltFor(username: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(("shyfr2-auth-salt-v1|" + normalizeUsername(username)).toByteArray(Charsets.UTF_8)).copyOf(16)

    /** Czysta funkcja bez Argon2 (testowalna na JVM): HKDF z wyniku KDF na sekret w base64url (43 znaki). */
    fun secretFromRoot(root: ByteArray): String {
        val s = Hkdf.computeHkdf("HmacSha256", root, ByteArray(0), "shyfr2-auth-secret-v1".toByteArray(Charsets.UTF_8), 32)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s)
    }

    /** Wolno (ok. 0,5-1 s, 64 MiB): wołać poza wątkiem UI. Wywołujący zeruje [password] po użyciu. */
    fun deriveAuthSecret(username: String, password: CharArray): String {
        val root = Kdf.derive(normalizeUsername(username), password, saltFor(username))
        try { return secretFromRoot(root) } finally { root.fill(0) }
    }

    const val MIN_PASSWORD_CHARS = 10
}

/**
 * Klucz urządzenia przekazywany serwerowi przy rejestracji urządzenia (pole identity_key).
 * UWAGA: to tymczasowy klucz X25519 wyłącznie po to, by rejestracja urządzenia miała realny, unikalny klucz publiczny.
 * Nie jest używany do żadnego szyfrowania. Docelowy klucz tożsamości wygeneruje wybrany protokół E2EE
 * (docs/2.0/SHYFR_2_CRYPTO_DECISION.md); przy jego integracji to urządzenie trzeba będzie zarejestrować od nowa.
 */
class DeviceKey(private val sealed: SealedFile) {
    @Synchronized fun publicKeyB64(): String {
        val priv = sealed.read()?.takeIf { it.size == 32 } ?: Keys.newPriv().also { sealed.write(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Keys.pub(priv))
    }
}
