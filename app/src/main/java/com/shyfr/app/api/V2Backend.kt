package com.shyfr.app.api

import android.content.Context
import android.os.Build
import com.shyfr.app.BuildConfig
import java.io.File

/**
 * Jedyne miejsce, które składa klienta SHYFR 2.0 z konfiguracji aplikacji.
 * Adres backendu jest ustawiany centralnie, wyłącznie przy budowaniu: `shyfr2ApiBaseUrl=https://...` w gradle.properties
 * (albo -Pshyfr2ApiBaseUrl=...). Użytkownik niczego nie wpisuje. Pusty adres = backend nie został jeszcze wdrożony.
 */
object V2Backend {
    @Volatile private var cached: Triple<String, ShyfrApiClient, SessionStore>? = null

    const val NOT_DEPLOYED = "Serwer SHYFR nie jest jeszcze wdrożony: ta wersja aplikacji nie ma adresu backendu."

    fun baseUrl(): String = BuildConfig.SHYFR2_API_BASE_URL

    fun isConfigured(): Boolean = baseUrl().isNotBlank()

    /** Rzuca [ApiConfig.Invalid], gdy adres jest pusty lub niedozwolony (np. zwykły HTTP w wydaniu). */
    fun config(@Suppress("UNUSED_PARAMETER") ctx: Context): ApiConfig = ApiConfig.parse(baseUrl(), allowInsecureLoopback = BuildConfig.DEBUG)

    @Synchronized fun client(ctx: Context): ShyfrApiClient {
        val cfg = config(ctx)
        cached?.let { if (it.first == cfg.baseUrl) return it.second }
        val app = ctx.applicationContext
        val dir = File(app.noBackupFilesDir, "v2")
        val store = FileSessionStore(SealedFile(File(dir, "session.bin"), KeystoreSealer()))
        val c = ShyfrApiClient(cfg, UrlConnectionTransport(), store, AndroidNetworkStatus(app))
        cached = Triple(cfg.baseUrl, c, store); return c
    }

    /**
     * Rejestracja urządzenia: identity_key = PUBLICZNY klucz tożsamości protokołu E2EE (prywatny zostaje w SignalStateStore).
     * Zmiana konta na tym samym telefonie czyści lokalne dane poprzedniego konta, żeby nie dziedziczyć jego kluczy, sesji i rozmów.
     */
    fun deviceRegistration(ctx: Context, username: String): DeviceRegistration {
        val dir = File(ctx.applicationContext.noBackupFilesDir, "v2")
        val sealer = KeystoreSealer()
        var st = com.shyfr.app.e2ee.E2eeRuntime.store(dir, sealer)
        val owner = st.owner
        if (owner != null && owner != username.trim().lowercase()) {
            com.shyfr.app.msg.MessengerGraph.wipeLocal(ctx)
            st = com.shyfr.app.e2ee.E2eeRuntime.store(dir, sealer)
        }
        st.bindOwner(username)
        val name = (Build.MODEL ?: "Android").take(60).ifBlank { "Android" }
        return DeviceRegistration(name, st.identityPublicB64)
    }
}
