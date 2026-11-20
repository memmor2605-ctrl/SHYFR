package com.shyfr.app.e2ee

import com.shyfr.app.api.MessageCipher
import com.shyfr.app.api.RemoteKeys
import com.shyfr.app.api.Sealer
import com.shyfr.app.api.ShyfrApiClient
import com.shyfr.app.msg.FailClosedCipher
import java.io.File

/**
 * Składa szyfr E2EE (libsignal). Gdy stanu kluczy nie da się odczytać, zwraca [FailClosedCipher]: aplikacja pokazuje,
 * że szyfrowanie jest niedostępne, i ODMAWIA wysyłania. Nigdy nie wysyła jawnego tekstu.
 */
object CipherFactory {
    const val UNAVAILABLE = "Szyfrowanie end-to-end jest niedostępne na tym urządzeniu (nie można odczytać kluczy). Wiadomości nie zostały wysłane."

    class Built(val cipher: MessageCipher, val e2eeAvailable: Boolean, val provision: (() -> Unit)?)

    fun create(dir: File, sealer: Sealer, keys: RemoteKeys, client: ShyfrApiClient): Built = try {
        val store = E2eeRuntime.store(dir, sealer)
        val prov = KeyProvisioner(store, client)
        Built(LibsignalMessageCipher(store, keys), true) { prov.ensurePublished() }
    } catch (e: Exception) {
        Built(FailClosedCipher(UNAVAILABLE), false, null)
    }
}
