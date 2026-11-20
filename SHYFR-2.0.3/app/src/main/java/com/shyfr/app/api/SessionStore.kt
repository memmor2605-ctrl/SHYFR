package com.shyfr.app.api

import com.shyfr.app.crypto.KeystoreWrap
import org.json.JSONObject
import java.io.File

/** Szyfrowanie danych w spoczynku. Produkcja: klucz sprzętowy Android Keystore. */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(blob: ByteArray): ByteArray
}

class KeystoreSealer : Sealer {
    override fun seal(plain: ByteArray) = KeystoreWrap.sealHardware(plain)
    override fun open(blob: ByteArray) = KeystoreWrap.openHardware(blob)
}

/** Tylko testy jednostkowe na JVM (bez Android Keystore). NIE używać w aplikacji. */
object NoSealerForTests : Sealer {
    override fun seal(plain: ByteArray) = plain
    override fun open(blob: ByteArray) = blob
}

/** Plik z zaszyfrowanym blobem; zapis atomowy (tmp + rename), więc awaria w trakcie zapisu nie psuje poprzedniej zawartości. */
class SealedFile(private val file: File, private val sealer: Sealer) {
    @Synchronized fun read(): ByteArray? {
        if (!file.exists()) return null
        return try { sealer.open(file.readBytes()) } catch (e: Exception) { null }   // uszkodzony lub nie do odszyfrowania => jak brak danych
    }

    @Synchronized fun write(data: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(sealer.seal(data))
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw java.io.IOException("nie udało się zapisać " + file.name) }
    }

    @Synchronized fun exists(): Boolean = file.exists()

    @Synchronized fun delete() { file.delete() }
}

/**
 * Sesja SHYFR 2.0 + zapamiętane identyfikatory urządzeń per nazwa użytkownika (żeby ponowne logowanie na tym samym telefonie
 * nie tworzyło nowego urządzenia i nie gubiło kluczy). Tokeny nigdy nie trafiają do SharedPreferences ani do logów.
 */
interface SessionStore {
    fun load(): StoredSession?
    fun save(s: StoredSession)
    /** Usuwa tylko sesję (tokeny); zapamiętane urządzenia zostają. */
    fun clear()
    fun deviceIdFor(username: String): String?
    fun rememberDevice(username: String, deviceId: String)
    fun forgetDevice(username: String)
}

class FileSessionStore(private val sealed: SealedFile) : SessionStore {
    private fun read(): JSONObject = sealed.read()?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONObject()
    private fun write(o: JSONObject) = sealed.write(o.toString().toByteArray(Charsets.UTF_8))

    @Synchronized override fun load(): StoredSession? {
        val s = read().optJSONObject("session") ?: return null
        return runCatching {
            StoredSession(s.getString("accountId"), s.getString("deviceId"), s.getString("username"),
                s.getString("access"), s.getLong("accessExp"), s.getString("refresh"))
        }.getOrNull()
    }

    @Synchronized override fun save(s: StoredSession) {
        val o = read()
        o.put("session", JSONObject().put("accountId", s.accountId).put("deviceId", s.deviceId).put("username", s.username)
            .put("access", s.accessToken).put("accessExp", s.accessExpiresAtMs).put("refresh", s.refreshToken))
        write(o)
    }

    @Synchronized override fun clear() { val o = read(); o.remove("session"); write(o) }

    @Synchronized override fun deviceIdFor(username: String): String? = read().optJSONObject("devices")?.optString(username, "")?.takeIf { it.isNotEmpty() }

    @Synchronized override fun rememberDevice(username: String, deviceId: String) {
        val o = read(); val d = o.optJSONObject("devices") ?: JSONObject()
        d.put(username, deviceId); o.put("devices", d); write(o)
    }

    @Synchronized override fun forgetDevice(username: String) {
        val o = read(); o.optJSONObject("devices")?.remove(username); write(o)
    }
}

/** Pamięciowa implementacja (testy jednostkowe). */
class InMemorySessionStore : SessionStore {
    @Volatile private var s: StoredSession? = null
    private val devices = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun load() = s
    override fun save(s: StoredSession) { this.s = s }
    override fun clear() { s = null }
    override fun deviceIdFor(username: String) = devices[username]
    override fun rememberDevice(username: String, deviceId: String) { devices[username] = deviceId }
    override fun forgetDevice(username: String) { devices.remove(username) }
}
