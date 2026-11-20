package com.shyfr.app.api

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ShyfrApiClientUnitTest {
    private val ME = """{"username":"alice","display_name":"alice","bio":"","created_at":1,"account_id":"acc","device_id":"dev"}"""
    private val NOW = 1_000_000L
    private fun client(t: HttpTransport, s: SessionStore, net: NetworkStatus = AlwaysOnline) = ShyfrApiClient(cfg(), t, s, net, clock = { NOW })

    @Test fun offlineFailsFastWithoutTouchingTheNetwork() {
        val t = ScriptedTransport { json(200, ME) }; val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 600_000)) }
        assertThrows(ApiException.Offline::class.java) { client(t, s, { false }).me() }
        assertTrue(t.requests.isEmpty()); assertNotNull(s.load())          // brak sieci nie kasuje sesji
    }

    @Test fun meSendsBearerToken() {
        val t = ScriptedTransport { json(200, ME) }; val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 600_000)) }
        assertEquals("alice", client(t, s).me().username)
        assertEquals("Bearer A1", t.requests[0].headers["Authorization"])
    }

    @Test fun expiringTokenIsRefreshedProactively() {
        val t = ScriptedTransport { r ->
            if (r.url.endsWith("/v1/auth/refresh")) json(200, """{"access_token":"A2","refresh_token":"R2","expires_in":900}""") else json(200, ME)
        }
        val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 5_000)) }       // < 30 s do wygaśnięcia
        client(t, s).me()
        assertEquals(listOf("POST /v1/auth/refresh", "GET /v1/accounts/me"), t.paths())
        assertEquals("Bearer A2", t.requests[1].headers["Authorization"])
        assertEquals("R2", s.load()!!.refreshToken); assertEquals(NOW + 900_000, s.load()!!.accessExpiresAtMs)
    }

    @Test fun unauthorizedTriggersOneRefreshAndRetry() {
        val t = ScriptedTransport { r ->
            when {
                r.url.endsWith("/refresh") -> json(200, """{"access_token":"A2","refresh_token":"R2","expires_in":900}""")
                r.headers["Authorization"] == "Bearer A1" -> json(401, """{"error":"x"}""")
                else -> json(200, ME)
            }
        }
        val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 600_000)) }
        assertEquals("alice", client(t, s).me().username)
        assertEquals(3, t.requests.size)
    }

    @Test fun revokedSessionClearsTokensAndReportsIt() {
        val t = ScriptedTransport { json(401, """{"error":"Sesja wygasła."}""") }
        val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 600_000)) }
        assertThrows(ApiException.SessionRevoked::class.java) { client(t, s).me() }
        assertNull(s.load())
        assertThrows(ApiException.NotSignedIn::class.java) { client(t, s).me() }
    }

    @Test fun secondUnauthorizedAfterSuccessfulRefreshAlsoEndsTheSession() {
        val t = ScriptedTransport { r -> if (r.url.endsWith("/refresh")) json(200, """{"access_token":"A2","refresh_token":"R2","expires_in":900}""") else json(401, "{}") }
        val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 600_000)) }
        assertThrows(ApiException.SessionRevoked::class.java) { client(t, s).me() }
        assertNull(s.load())
    }

    @Test fun networkErrorDuringRefreshKeepsTokens() {
        val t = ScriptedTransport { r -> if (r.url.endsWith("/refresh")) ApiException.Network(java.io.IOException("x")) else json(401, "{}") }
        val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 600_000)) }
        assertThrows(ApiException.Network::class.java) { client(t, s).me() }
        assertEquals("R1", s.load()!!.refreshToken)          // to nie odwołanie sesji
    }

    @Test fun concurrentRequestsRefreshOnlyOnce() {
        val refreshes = AtomicInteger()
        val t = ScriptedTransport { r ->
            when {
                r.url.endsWith("/refresh") -> { refreshes.incrementAndGet(); Thread.sleep(150); json(200, """{"access_token":"A2","refresh_token":"R2","expires_in":900}""") }
                r.headers["Authorization"] == "Bearer A1" -> json(401, "{}")
                else -> json(200, ME)
            }
        }
        val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 600_000)) }; val c = client(t, s)
        val pool = Executors.newFixedThreadPool(6); val go = CountDownLatch(1); val ok = AtomicInteger()
        repeat(6) { pool.submit { go.await(); runCatching { c.me() }.onSuccess { ok.incrementAndGet() } } }
        go.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, refreshes.get()); assertEquals(6, ok.get())     // ponowne użycie tokenu odświeżania zabiłoby sesję na serwerze
    }

    @Test fun loginWrongSecretIsInvalidCredentials_andStoresNothing() {
        val t = ScriptedTransport { json(401, """{"error":"Nieprawidłowy login lub sekret."}""") }; val s = InMemorySessionStore()
        assertThrows(ApiException.InvalidCredentials::class.java) { client(t, s).login("alice", "x".repeat(43), DeviceRegistration("tel", "AAAA")) }
        assertNull(s.load())
    }

    @Test fun loginReusesKnownDeviceAndFallsBackWhenServerForgotIt() {
        var n = 0
        val t = ScriptedTransport { r ->
            when {
                r.url.endsWith("/v1/auth/login") && ++n == 1 -> json(400, """{"error":"Nieznane urządzenie."}""")
                r.url.endsWith("/v1/auth/login") -> json(200, """{"account_id":"acc","device_id":"newdev","access_token":"A","refresh_token":"R","expires_in":900}""")
                else -> json(200, ME)
            }
        }
        val s = InMemorySessionStore().apply { rememberDevice("alice", "a".repeat(32)) }
        client(t, s).login("Alice", "x".repeat(43), DeviceRegistration("tel", "AAAA"))
        assertTrue(String(t.requests[0].body!!).contains("device_id")); assertTrue(String(t.requests[1].body!!).contains("\"device\""))
        assertEquals("newdev", s.deviceIdFor("alice"))
    }

    @Test fun registerConflictAndRateLimitAndServerErrorsAreMapped() {
        fun status(code: Int, body: String): ApiException = try { client(ScriptedTransport { json(code, body) }, InMemorySessionStore()).register("alice", "x".repeat(43), null, DeviceRegistration("t", "AAAA")); fail(); throw IllegalStateException() } catch (e: ApiException) { e }
        assertEquals(409, (status(409, """{"error":"zajęta"}""") as ApiException.Http).status)
        assertTrue(status(429, "{}") is ApiException.RateLimited); assertTrue(status(429, "{}").retryable)
        assertTrue(status(503, "{}") is ApiException.Server)
        assertEquals("Ta nazwa użytkownika jest zajęta.", status(409, "{}").userMessage())
    }

    @Test fun malformedResponseIsReported() {
        val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 600_000)) }
        assertThrows(ApiException.Malformed::class.java) { client(ScriptedTransport { json(200, "<html>") }, s).me() }
        assertThrows(ApiException.Malformed::class.java) { client(ScriptedTransport { json(200, "{}") }, s).me() }
    }

    @Test fun logoutAlwaysClearsLocalSessionAndReportsServerResult() {
        val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 600_000)) }
        assertTrue(client(ScriptedTransport { json(200, "{}") }, s).logout()); assertNull(s.load())
        s.save(sessionOf(exp = NOW + 600_000))
        assertFalse(client(ScriptedTransport { ApiException.Network(java.io.IOException()) }, s).logout()); assertNull(s.load())
        s.save(sessionOf(exp = NOW + 600_000))
        assertFalse(client(ScriptedTransport { json(200, "{}") }, s, { false }).logout()); assertNull(s.load())
        s.save(sessionOf(exp = NOW + 600_000))
        assertTrue(client(ScriptedTransport { json(401, "{}") }, s).logout())              // już nieważna = cel osiągnięty
    }

    @Test fun fileSessionStorePersistsAndSurvivesCorruption() {
        val f = java.io.File.createTempFile("sess", ".bin"); f.delete()
        val st = FileSessionStore(SealedFile(f, NoSealerForTests))
        st.save(sessionOf(exp = 5)); st.rememberDevice("alice", "d1")
        val again = FileSessionStore(SealedFile(f, NoSealerForTests))
        assertEquals("R1", again.load()!!.refreshToken); assertEquals("d1", again.deviceIdFor("alice"))
        again.clear(); assertNull(again.load()); assertEquals("d1", again.deviceIdFor("alice"))
        f.writeBytes("śmieci".toByteArray()); assertNull(FileSessionStore(SealedFile(f, NoSealerForTests)).load())
        f.delete()
    }
}
