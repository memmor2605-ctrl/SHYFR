package com.shyfr.app.api

data class StoredSession(
    val accountId: String,
    val deviceId: String,
    val username: String,
    val accessToken: String,
    val accessExpiresAtMs: Long,
    val refreshToken: String,
)

data class Profile(
    val accountId: String,
    val deviceId: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val createdAtSec: Long,
)

data class DeviceInfo(val id: String, val name: String, val createdAtSec: Long, val lastSeenSec: Long, val current: Boolean)

data class DeviceRegistration(val name: String, val identityKeyB64: String)

/** Odwołanie do klucza w katalogu serwera. [signatureB64] jest null dla kluczy bez podpisu (jednorazowy klucz EC). */
data class KeyRef(val keyId: Int, val keyB64: String, val signatureB64: String?)

/** Zestaw kluczy jednego urządzenia rozmówcy, zwracany przez GET /v1/keys/{user}. */
data class DeviceBundle(val deviceId: String, val identityKeyB64: String, val signedPreKey: KeyRef?, val kyberPreKey: KeyRef?, val oneTimePreKey: KeyRef?)

/** Źródło kluczy rozmówców (w aplikacji: [ShyfrApiClient]; w testach: atrapa). */
interface RemoteKeys { fun bundles(username: String): List<DeviceBundle> }
