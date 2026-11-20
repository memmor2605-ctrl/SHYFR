package com.shyfr.app.msg

import com.shyfr.app.api.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/**
 * NIE URUCHOMIONE w etapie 41 (brak Gradle/SDK w środowisku). Testują logikę repozytorium (kolejka, stany, deduplikacja, odmowa przy braku E2EE).
 * [TestOnlyCipher] jest atrapą WYŁĄCZNIE do testów JVM tej logiki i nie ma związku z kryptografią produkcyjną.
 */
class MessageRepositoryTest {
    private val NOW = 1_000_000L
    private val DEV = "d".repeat(32)
    private class TestOnlyCipher : MessageCipher {
        override fun encrypt(recipientUsername: String, recipientDeviceId: String, plaintext: ByteArray) = "T:".toByteArray() + plaintext
        override fun decrypt(senderUsername: String, senderDeviceId: String, ciphertext: ByteArray) = ciphertext.copyOfRange(2, ciphertext.size)
    }
    private class Keys(var devices: List<String>, var fail: ApiException? = null) : RemoteKeys {
        override fun bundles(username: String): List<DeviceBundle> { fail?.let { throw it }; return devices.map { DeviceBundle(it, "id", null, null, null) } }
    }
    private class Env(val repo: MessageRepository, val store: InMemoryConversations, val outbox: InMemoryOutbox, val inbox: InMemoryInbox, val net: ScriptedTransport, val keys: Keys)

    private fun env(cipher: MessageCipher = TestOnlyCipher(), keys: Keys = Keys(listOf(DEV)), h: (HttpRequest) -> Any): Env {
        val t = ScriptedTransport(h); val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 600_000)) }
        val client = ShyfrApiClient(cfg(), t, s, AlwaysOnline, { NOW })
        val ob = InMemoryOutbox(); val ib = InMemoryInbox(); val cs = InMemoryConversations()
        return Env(MessageRepository(client, MessageTransport(client, ob, ib, { NOW }), ob, cs, cipher, keys, { NOW }), cs, ob, ib, t, keys)
    }
    private val empty = json(200, """{"has_more":false,"messages":[]}""")

    @Test fun sendEncryptsBeforeTheNetworkAndStoresOnlyCiphertextInOutbox() {
        val e = env { r -> if (r.method == "POST" && r.url.endsWith("/v1/messages")) json(202, """{"id":"s1","state":"accepted"}""") else empty }
        e.repo.send("bob", "tajny tekst"); e.repo.pump()
        val m = e.store.all().single(); assertEquals(MsgState.ACCEPTED, m.state)
        assertTrue(e.net.requests.none { String(it.body ?: ByteArray(0)).contains("tajny tekst") })   // jawny tekst nie trafia do żądań HTTP
    }

    @Test fun acceptedIsNotDeliveredUntilServerReportsDelivery() {
        var state = "accepted"
        val e = env { r -> when { r.method == "POST" && r.url.endsWith("/v1/messages") -> json(202, """{"id":"s1","state":"accepted"}""")
            r.url.endsWith("/status") -> json(200, """{"state":"$state"}"""); else -> empty } }
        e.repo.send("bob", "hej"); e.repo.pump()
        assertEquals(MsgState.ACCEPTED, e.store.all().single().state)
        state = "delivered"; e.repo.pump()
        assertEquals(MsgState.DELIVERED, e.store.all().single().state)
    }

    @Test fun offlineSendStaysQueuedAndIsSentOnceNetworkReturns() {
        var online = false
        val e = env { r -> if (!online) ApiException.Timeout(java.io.IOException()) else if (r.method == "POST" && r.url.endsWith("/v1/messages")) json(202, """{"id":"s1","state":"accepted"}""") else empty }
        e.repo.send("bob", "offline"); val r1 = e.repo.pump()
        assertEquals(MsgState.QUEUED, e.store.all().single().state); assertNotNull(r1.error)
        online = true; e.repo.pump()
        assertEquals(MsgState.ACCEPTED, e.store.all().single().state)
        val ids = e.net.requests.filter { it.url.endsWith("/v1/messages") }.map { Regex("\"client_msg_id\":\"([^\"]+)\"").find(String(it.body!!))!!.groupValues[1] }
        assertEquals(1, ids.toSet().size)                                      // ponowienie z tym samym id: serwer nie zrobi duplikatu
    }

    @Test fun failClosedCipherRefusesAndNothingIsSent() {
        val e = env(FailClosedCipher("brak E2EE")) { empty }
        e.repo.send("bob", "x"); e.repo.pump()
        val m = e.store.all().single(); assertEquals(MsgState.FAILED, m.state); assertEquals("brak E2EE", m.error)
        assertTrue(e.net.requests.none { it.url.endsWith("/v1/messages") && it.method == "POST" }); assertTrue(e.outbox.all().isEmpty())
    }

    @Test fun unknownRecipientFailsPermanently() {
        val e = env(keys = Keys(listOf(DEV), ApiException.Http(404, ""))) { empty }
        e.repo.send("nikt", "x"); e.repo.pump()
        assertEquals(MsgState.FAILED, e.store.all().single().state)
    }

    @Test fun retryReencryptsAFailedMessage() {
        val keys = Keys(listOf(DEV), ApiException.Http(404, ""))
        val e = env(keys = keys) { r -> if (r.method == "POST" && r.url.endsWith("/v1/messages")) json(202, """{"id":"s1","state":"accepted"}""") else empty }
        val m = e.repo.send("bob", "x"); e.repo.pump(); assertEquals(MsgState.FAILED, e.store.get(m.id)!!.state)
        keys.fail = null; e.repo.retry(m.id); e.repo.pump()
        assertEquals(MsgState.ACCEPTED, e.store.get(m.id)!!.state)
    }

    @Test fun multiDeviceRecipientGetsOneCiphertextPerDevice() {
        val e = env(keys = Keys(listOf("a".repeat(32), "b".repeat(32)))) { r -> if (r.method == "POST" && r.url.endsWith("/v1/messages")) json(202, """{"id":"s${r.hashCode()}","state":"accepted"}""") else empty }
        e.repo.send("bob", "x"); e.repo.pump()
        assertEquals(2, e.outbox.all().size); assertEquals(2, e.store.all().single().parts.size)
    }

    private fun incoming(id: String, body: String): String {
        val env = """{"v":1,"t":"text","id":"$id","body":"$body","ts":1}"""
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("T:".toByteArray() + env.toByteArray())
        return """{"has_more":false,"messages":[{"id":"srv-$id","seq":1,"payload":"$payload","created_at":5,"sender":{"username":"bob","device_id":"$DEV"}}]}"""
    }

    @Test fun incomingIsStoredDecryptedAckedAndNotDuplicated() {
        var served = incoming("e1", "cześć")
        val e = env { r -> if (r.url.endsWith("/ack")) json(200, """{"results":{"srv-e1":"ok"}}""") else if (r.url.contains("/pending")) json(200, served) else empty }
        val r = e.repo.pump(); assertEquals(1, r.received)
        val m = e.store.all().single(); assertEquals("cześć", m.text); assertEquals(Direction.IN, m.dir); assertTrue(m.unread)
        assertTrue(e.net.paths().any { it.endsWith("/v1/messages/ack") })
        e.repo.pump()                                                            // ta sama dostawa ponownie (np. utracone ACK)
        assertEquals(1, e.store.all().size)
    }

    @Test fun undecryptableMessageStaysInInboxAndIsCounted() {
        val bad = object : MessageCipher { override fun encrypt(a: String, b: String, c: ByteArray) = c; override fun decrypt(a: String, b: String, c: ByteArray): ByteArray = throw CipherException("x", true) }
        val e = env(bad) { r -> if (r.url.endsWith("/ack")) json(200, """{"results":{"srv-e1":"ok"}}""") else if (r.url.contains("/pending")) json(200, incoming("e1", "x")) else empty }
        val r = e.repo.pump(); assertEquals(1, r.decryptFailures); assertEquals(1, e.inbox.unprocessed().size); assertTrue(e.store.all().isEmpty())
    }

    @Test fun markReadSendsReceiptOnlyWhenEnabled() {
        val e = env { r -> if (r.url.endsWith("/ack")) json(200, """{"results":{"srv-e1":"ok"}}""") else if (r.url.contains("/pending")) json(200, incoming("e1", "x")) else json(200, "{}") }
        e.repo.pump(); e.repo.markRead("bob", false)
        assertTrue(e.net.requests.none { it.url.endsWith("/read") }); assertFalse(e.store.all().single().unread)
        e.repo.markRead("bob", true)
        assertTrue(e.net.requests.any { it.url.endsWith("/read") })
    }

    @Test fun summariesAreSortedUnreadCountedAndSearchable() {
        val s = InMemoryConversations()
        s.upsert(ChatMessage("1", "ala", Direction.IN, "a", 10, MsgState.RECEIVED, unread = true)); s.upsert(ChatMessage("2", "bob", Direction.OUT, "b", 20, MsgState.ACCEPTED))
        val l = Conversations.summarize(s.all(), mapOf("ala" to "Ala K."))
        assertEquals(listOf("bob", "ala"), l.map { it.peer }); assertEquals(1, l[1].unread)
        assertEquals(listOf("ala"), Conversations.summarize(s.all(), mapOf("ala" to "Ala K."), "k.").map { it.peer })
    }
}
