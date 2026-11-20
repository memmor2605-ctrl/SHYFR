package com.shyfr.app.api

import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.util.Base64

/**
 * Testy integracyjne: PRAWDZIWY klient (UrlConnectionTransport) przeciw PRAWDZIWEMU backendowi, bez atrap odpowiedzi.
 * Uruchamiane tylko, gdy ustawiono SHYFR2_TEST_URL (np. http://127.0.0.1:8080 dla lokalnego backendu; backend/README.md).
 * Bez tej zmiennej testy są POMIJANE (w raporcie Gradle widać je jako skipped), a nie zaliczane.
 * Sekrety w testach są losowe, nazwy unikalne, więc test można puszczać wielokrotnie na tej samej bazie.
 */
class BackendIntegrationTest {
    private val url: String? = System.getenv("SHYFR2_TEST_URL")?.takeIf { it.isNotBlank() }
    private val rnd = java.security.SecureRandom()
    private fun secret() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { rnd.nextBytes(it) })
    private fun uname() = "it_" + java.lang.Long.toHexString(rnd.nextLong() and 0xffffffffffL)
    private fun dev(n: String = "test") = DeviceRegistration(n, Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { rnd.nextBytes(it) }))
    private fun newClient(store: SessionStore = InMemorySessionStore()) =
        ShyfrApiClient(ApiConfig.parse(url!!, allowInsecureLoopback = true), UrlConnectionTransport(), store) to store

    @Test fun registerProfileDevicesLogoutLogin() {
        assumeNotNull(url)
        val (c, store) = newClient(); val u = uname(); val sec = secret()
        val p = c.register(u, sec, null, dev("Telefon A"))
        assertEquals(u, p.username); assertNotNull(store.load())
        val d = c.devices(); assertEquals(1, d.size); assertTrue(d[0].current); assertEquals("Telefon A", d[0].name)
        assertTrue(c.logout()); assertNull(store.load())
        assertThrows(ApiException.NotSignedIn::class.java) { c.me() }
        val p2 = c.login(u, sec, dev("Telefon A"))
        assertEquals(p.accountId, p2.accountId); assertEquals(p.deviceId, p2.deviceId)       // to samo urządzenie, bez rozrostu listy
        assertEquals(1, c.devices().size)
    }

    @Test fun duplicateUsernameAndBadCredentialsMapToTypedErrors() {
        assumeNotNull(url)
        val (c, _) = newClient(); val u = uname(); val sec = secret()
        c.register(u, sec, null, dev()); c.logout()
        val e = assertThrows(ApiException.Http::class.java) { newClient().first.register(u, secret(), null, dev()) }; assertEquals(409, e.status)
        assertThrows(ApiException.InvalidCredentials::class.java) { newClient().first.login(u, secret(), dev()) }
        assertThrows(ApiException.InvalidCredentials::class.java) { newClient().first.login(uname(), secret(), dev()) }   // nieistniejące konto: ten sam błąd
    }

    @Test fun refreshRotationKeepsWorkingAndOldTokenIsUseless() {
        assumeNotNull(url)
        val (c, store) = newClient(); c.register(uname(), secret(), null, dev())
        val s1 = store.load()!!
        // wymuszamy odświeżenie: sesja z access przeterminowanym lokalnie
        store.save(s1.copy(accessExpiresAtMs = 0))
        c.me()
        val s2 = store.load()!!
        assertNotEquals(s1.refreshToken, s2.refreshToken); assertNotEquals(s1.accessToken, s2.accessToken)
        // stary token odświeżania użyty ponownie => serwer kończy całą sesję => klient zgłasza SessionRevoked
        store.save(s2.copy(refreshToken = s1.refreshToken, accessToken = "zly", accessExpiresAtMs = 0))
        assertThrows(ApiException.SessionRevoked::class.java) { c.me() }
        assertNull(store.load())
    }

    @Test fun sessionRevokedFromAnotherDeviceIsDetected() {
        assumeNotNull(url)
        val u = uname(); val sec = secret()
        val (a, sa) = newClient(); a.register(u, sec, null, dev("A"))
        val (b, _) = newClient(); b.login(u, sec, dev("B"))
        val other = b.devices().first { !it.current }
        b.revokeDevice(other.id)                                    // B usuwa urządzenie A
        assertThrows(ApiException.SessionRevoked::class.java) { a.me() }
        assertNull(sa.load())
        assertEquals(1, b.devices().size)
    }

    @Test fun messageQueueEndToEnd_transportOnly_noEncryptionClaimed() {
        assumeNotNull(url)
        val ua = uname(); val ub = uname()
        val (a, _) = newClient(); val (b, _) = newClient()
        a.register(ua, secret(), null, dev("A")); val pb = b.register(ub, secret(), null, dev("B"))
        val ob = InMemoryOutbox(); val ib = InMemoryInbox(); val ta = MessageTransport(a, ob, InMemoryInbox()); val tb = MessageTransport(b, InMemoryOutbox(), ib)
        val blob = ByteArray(64).also { rnd.nextBytes(it) }            // nieprzezroczyste bajty, NIE szyfrogram E2EE
        val m = ta.enqueue(ub, pb.deviceId, blob)
        assertEquals(1, ta.flushOutbox().sent); assertEquals(OutState.ACCEPTED, ob.all()[0].state)
        // duplikat żądania z tym samym client_msg_id (jak po zerwaniu odpowiedzi): serwer nie tworzy drugiej wiadomości
        val dupe = a.authed("POST", "/v1/messages", org.json.JSONObject().put("recipient_username", ub).put("recipient_device_id", pb.deviceId)
            .put("client_msg_id", m.clientMsgId).put("payload", m.payloadB64))
        assertEquals(ob.all()[0].serverId, dupe.getString("id"))
        val r = tb.syncInbox(); assertEquals(1, r.received); assertEquals(1, r.acked)
        assertArrayEquals(blob, Base64.getUrlDecoder().decode(ib.unprocessed()[0].payloadB64))
        assertEquals(0, tb.syncInbox().received)                       // potwierdzona: nie wraca
        assertEquals(1, ta.refreshStatuses()); assertEquals(OutState.DELIVERED, ob.all()[0].state)
        tb.markRead(ib.unprocessed()[0].serverId); assertEquals(1, ta.refreshStatuses()); assertEquals(OutState.READ, ob.all()[0].state)
    }

    @Test fun recipientOfflineThenReconnects_messagesWaitInServerQueue() {
        assumeNotNull(url)
        val ub = uname(); val secB = secret()
        val (a, _) = newClient(); a.register(uname(), secret(), null, dev("A"))
        val (b1, _) = newClient(); val pb = b1.register(ub, secB, null, dev("B"))
        val ta = MessageTransport(a, InMemoryOutbox(), InMemoryInbox())
        b1.logout()                                                    // odbiorca offline
        repeat(3) { ta.enqueue(ub, pb.deviceId, byteArrayOf(it.toByte(), 9)) }
        assertEquals(3, ta.flushOutbox().sent)
        val (b2, _) = newClient(); b2.login(ub, secB, dev("B"))        // wraca, to samo urządzenie
        val ib = InMemoryInbox(); val r = MessageTransport(b2, InMemoryOutbox(), ib).syncInbox()
        assertEquals(3, r.received); assertEquals(listOf(0, 1, 2), ib.unprocessed().map { Base64.getUrlDecoder().decode(it.payloadB64)[0].toInt() })   // kolejność zachowana
    }
}
