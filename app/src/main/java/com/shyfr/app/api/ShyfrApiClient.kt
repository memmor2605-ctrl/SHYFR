package com.shyfr.app.api

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Klient API SHYFR 2.0 (blokujący: wołać z wątku roboczego, np. Dispatchers.IO).
 *
 * Tokeny: access (15 min) i refresh (30 dni, jednorazowy: każde odświeżenie wydaje nowy). Klient:
 *  - odświeża z wyprzedzeniem, gdy access wygasa za mniej niż [REFRESH_SKEW_MS],
 *  - na 401 odświeża RAZ i ponawia żądanie; drugie 401 => [ApiException.SessionRevoked],
 *  - odświeżanie jest szeregowane jednym zamkiem: dwa równoległe żądania nie zużyją tego samego tokenu odświeżania
 *    (serwer uznałby to za kradzież tokenu i zakończył sesję),
 *  - 401 z endpointu odświeżania => tokeny lokalne usunięte, [ApiException.SessionRevoked],
 *  - błąd sieci przy odświeżaniu NIE kasuje tokenów (to nie jest odwołanie sesji).
 */
class ShyfrApiClient(
    private val config: ApiConfig,
    private val transport: HttpTransport,
    private val store: SessionStore,
    private val network: NetworkStatus = AlwaysOnline,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val refreshLock = Any()

    // ------------------------------------------------------------------ konto
    fun register(username: String, authSecret: String, displayName: String?, device: DeviceRegistration): Profile {
        val body = JSONObject().put("username", username).put("auth_secret", authSecret).put("device", deviceJson(device))
        if (!displayName.isNullOrBlank()) body.put("display_name", displayName)
        val r = call("POST", "/v1/accounts", body, null)
        val s = sessionFrom(r, username.trim().lowercase())
        store.save(s); store.rememberDevice(s.username, s.deviceId)
        return me()
    }

    /** Logowanie: jeśli ten telefon ma już urządzenie dla tej nazwy, używa go; jeśli serwer go nie zna (unieważnione), rejestruje nowe. */
    fun login(username: String, authSecret: String, device: DeviceRegistration): Profile {
        val u = username.trim().lowercase()
        val known = store.deviceIdFor(u)
        val resp = try {
            callLogin(u, authSecret, device, known)
        } catch (e: ApiException.Http) {
            if (known != null && e.status == 400) { store.forgetDevice(u); callLogin(u, authSecret, device, null) } else throw e
        }
        val s = sessionFrom(resp, u)
        store.save(s); store.rememberDevice(u, s.deviceId)
        return me()
    }

    private fun callLogin(u: String, secret: String, device: DeviceRegistration, deviceId: String?): JSONObject {
        val body = JSONObject().put("username", u).put("auth_secret", secret)
        if (deviceId != null) body.put("device_id", deviceId) else body.put("device", deviceJson(device))
        return try { call("POST", "/v1/auth/login", body, null) } catch (e: ApiException.SessionRevoked) { throw ApiException.InvalidCredentials() }
    }

    fun me(): Profile {
        val r = authed("GET", "/v1/accounts/me", null)
        return try {
            Profile(r.getString("account_id"), r.getString("device_id"), r.getString("username"), r.getString("display_name"), r.optString("bio", ""), r.getLong("created_at"))
        } catch (e: JSONException) { throw ApiException.Malformed(e) }
    }

    fun devices(): List<DeviceInfo> {
        val r = authed("GET", "/v1/devices", null)
        return try {
            val a: JSONArray = r.getJSONArray("devices")
            (0 until a.length()).map { i -> a.getJSONObject(i).let { DeviceInfo(it.getString("id"), it.getString("name"), it.getLong("created_at"), it.getLong("last_seen"), it.getBoolean("current")) } }
        } catch (e: JSONException) { throw ApiException.Malformed(e) }
    }

    fun revokeDevice(deviceId: String) { authed("DELETE", "/v1/devices/$deviceId", null) }

    /**
     * Wylogowanie. Tokeny lokalne są usuwane ZAWSZE. Zwraca true, gdy serwer potwierdził unieważnienie sesji;
     * false, gdy się nie udało (brak sieci itp.): wtedy sesja na serwerze wygaśnie sama, a użytkownik powinien o tym wiedzieć.
     */
    fun logout(): Boolean {
        val s = store.load() ?: return true
        var serverRevoked = false
        try {
            if (network.isOnline()) { send("POST", "/v1/auth/logout", null, s.accessToken); serverRevoked = true }
        } catch (e: ApiException.Unauthorized401) {
            serverRevoked = true           // już nieważna: cel osiągnięty
        } catch (e: ApiException) {
            serverRevoked = false
        } finally {
            store.clear()
        }
        return serverRevoked
    }

    fun isSignedIn(): Boolean = store.load() != null
    fun currentSession(): StoredSession? = store.load()

    // ------------------------------------------------------------------ wywołania uwierzytelnione (używa też MessageTransport)
    fun authed(method: String, path: String, body: JSONObject?, readTimeoutMs: Int = 30_000): JSONObject {
        var s = store.load() ?: throw ApiException.NotSignedIn()
        if (s.accessExpiresAtMs - clock() < REFRESH_SKEW_MS) s = refreshSession(s.accessToken)
        try {
            return send(method, path, body, s.accessToken, readTimeoutMs)
        } catch (e: ApiException.Unauthorized401) {
            val s2 = refreshSession(s.accessToken)
            try { return send(method, path, body, s2.accessToken, readTimeoutMs) }
            catch (e2: ApiException.Unauthorized401) { store.clear(); throw ApiException.SessionRevoked() }
        }
    }

    private fun refreshSession(failedAccessToken: String): StoredSession = synchronized(refreshLock) {
        val cur = store.load() ?: throw ApiException.NotSignedIn()
        if (cur.accessToken != failedAccessToken) return cur        // inny wątek już odświeżył
        val r = try {
            call("POST", "/v1/auth/refresh", JSONObject().put("refresh_token", cur.refreshToken), null)
        } catch (e: ApiException.SessionRevoked) {
            store.clear(); throw e
        }
        val next = try {
            cur.copy(accessToken = r.getString("access_token"), refreshToken = r.getString("refresh_token"), accessExpiresAtMs = clock() + r.getLong("expires_in") * 1000)
        } catch (e: JSONException) { throw ApiException.Malformed(e) }
        store.save(next)
        return next
    }


    // ------------------------------------------------------------------ etap 41: klucze, profil rozmówcy, push, usuwanie konta
    /** Profil innego użytkownika. 404 => [ApiException.Http] (status 404): nie ma takiego użytkownika. */
    fun profileOf(username: String): Profile? {
        val r = authed("GET", "/v1/accounts/" + username.trim().lowercase(), null)
        return try { Profile("", "", r.getString("username"), r.getString("display_name"), r.optString("bio", ""), r.optLong("created_at", 0)) }
        catch (e: JSONException) { throw ApiException.Malformed(e) }
    }

    /** Katalog kluczy rozmówcy (jeden wpis na urządzenie). Uwaga: pobranie rezerwuje klucze jednorazowe dla tego pytającego (serwer: ten sam klucz przy ponowieniu). */
    fun fetchKeyBundles(username: String): List<DeviceBundle> {
        val r = authed("GET", "/v1/keys/" + username.trim().lowercase(), null)
        return try {
            val a = r.getJSONArray("devices")
            (0 until a.length()).map { i ->
                val d = a.getJSONObject(i)
                fun key(o: JSONObject?, withSig: Boolean) = o?.let { KeyRef(it.getInt("key_id"), it.getString("key"), if (withSig) it.getString("signature") else null) }
                DeviceBundle(d.getString("device_id"), d.getString("identity_key"),
                    key(d.optJSONObject("signed_prekey"), true), key(d.optJSONObject("kyber_prekey"), true), key(d.optJSONObject("one_time_prekey"), false))
            }
        } catch (e: JSONException) { throw ApiException.Malformed(e) }
    }

    fun uploadKeys(body: JSONObject): JSONObject = authed("PUT", "/v1/keys", body)
    fun keyCount(): JSONObject = authed("GET", "/v1/keys/me/count", null)

    fun registerPushToken(token: String) { authed("PUT", "/v1/devices/me/push-token", JSONObject().put("provider", "fcm").put("token", token)) }
    fun deletePushToken() { authed("DELETE", "/v1/devices/me/push-token", null) }

    /**
     * Usuwa konto NA SERWERZE (nieodwracalnie: wiadomości, klucze, urządzenia, sesje). Wymaga ponownego podania hasła (sekret liczony przez wywołującego).
     * Po sukcesie tokeny lokalne są kasowane. Dane lokalne (historia, klucze E2EE) czyści wywołujący.
     */
    fun deleteAccount(authSecret: String) {
        authed("DELETE", "/v1/accounts/me", JSONObject().put("auth_secret", authSecret))
        store.clear()
    }

    // ------------------------------------------------------------------ niski poziom
    /** Żądanie bez tokenu. 401 => [ApiException.SessionRevoked] (znaczenie zależy od endpointu; wywołujący je przemapowuje). */
    private fun call(method: String, path: String, body: JSONObject?, token: String?): JSONObject =
        try { send(method, path, body, token) } catch (e: ApiException.Unauthorized401) { throw ApiException.SessionRevoked() }

    private fun send(method: String, path: String, body: JSONObject?, token: String?, readTimeoutMs: Int = 30_000): JSONObject {
        if (!network.isOnline()) throw ApiException.Offline()
        val h = HashMap<String, String>()
        h["Accept"] = "application/json"
        if (body != null) h["Content-Type"] = "application/json; charset=utf-8"
        if (token != null) h["Authorization"] = "Bearer $token"
        val resp = transport.execute(HttpRequest(method, config.url(path), h, body?.toString()?.toByteArray(Charsets.UTF_8), readTimeoutMs))
        val text = String(resp.body, Charsets.UTF_8)
        if (resp.status in 200..299) {
            return try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (e: JSONException) { throw ApiException.Malformed(e) }
        }
        val msg = runCatching { JSONObject(text).optString("error", "") }.getOrDefault("")
        throw when (resp.status) {
            401 -> ApiException.Unauthorized401()
            429 -> ApiException.RateLimited(msg)
            in 500..599 -> ApiException.Server(resp.status)
            else -> ApiException.Http(resp.status, msg)
        }
    }

    private fun deviceJson(d: DeviceRegistration) = JSONObject().put("name", d.name).put("identity_key", d.identityKeyB64)

    private fun sessionFrom(r: JSONObject, username: String): StoredSession = try {
        StoredSession(r.getString("account_id"), r.getString("device_id"), username, r.getString("access_token"),
            clock() + r.getLong("expires_in") * 1000, r.getString("refresh_token"))
    } catch (e: JSONException) { throw ApiException.Malformed(e) }

    companion object { const val REFRESH_SKEW_MS = 30_000L }
}
