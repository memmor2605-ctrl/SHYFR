package com.shyfr.app.crypto

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Keys {
    fun newPriv(): ByteArray = X25519.generatePrivateKey()
    fun pub(priv: ByteArray): ByteArray = X25519.publicFromPrivate(priv)
    fun dh(priv: ByteArray, pub: ByteArray): ByteArray = X25519.computeSharedSecret(priv, pub)
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { m.update(it) }
        return m.doFinal()
    }
    fun equalsCt(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    /** Ziarno sesji: miesza jednorazowy sekret z zaproszenia z DH kluczy tożsamości. */
    fun rootSeed(secret: ByteArray, dhOut: ByteArray, pubA: ByteArray, pubB: ByteArray): ByteArray =
        Hkdf.computeHkdf("HmacSha256", dhOut, secret, "shyfr-root-v1".toByteArray() + pubA + pubB, 32)

    /** Dane uwierzytelniane (AD): obie tożsamości w stałej kolejności, niezależnie od roli. */
    fun pairAd(a: ByteArray, b: ByteArray): ByteArray {
        var cmp = 0
        for (i in a.indices) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) { cmp = d; break } }
        return if (cmp <= 0) a + b else b + a
    }

    /** Krótki identyfikator nadawcy w ramce (pozwala odbiorcy wybrać sesję). */
    fun sid(pub: ByteArray): ByteArray = sha256(pub).copyOf(16)

    /** Kod bezpieczeństwa: 10 grup po 5 cyfr, identyczny po obu stronach. Porównajcie go na żywo. */
    fun safetyNumber(a: ByteArray, b: ByteArray): String {
        val ad = pairAd(a, b)
        val d = sha256(ad) + sha256(ad + byteArrayOf(1))
        return (0 until 10).joinToString(" ") { i ->
            var v = 0L
            for (j in 0 until 4) v = (v shl 8) or (d[i * 4 + j].toLong() and 0xff)
            "%05d".format(v % 100000)
        }
    }
}
