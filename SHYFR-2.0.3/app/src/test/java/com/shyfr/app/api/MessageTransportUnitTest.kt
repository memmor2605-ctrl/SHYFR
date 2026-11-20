package com.shyfr.app.api

import org.junit.Assert.*
import org.junit.Test

class MessageTransportUnitTest {
    private val NOW = 1_000_000L
    private fun setup(h: (HttpRequest) -> Any): Triple<MessageTransport, ScriptedTransport, Pair<InMemoryOutbox, InMemoryInbox>> {
        val t = ScriptedTransport(h); val s = InMemorySessionStore().apply { save(sessionOf(exp = NOW + 600_000)) }
        val ob = InMemoryOutbox(); val ib = InMemoryInbox()
        return Triple(MessageTransport(ShyfrApiClient(cfg(), t, s, AlwaysOnline, { NOW }), ob, ib, { NOW }), t, ob to ib)
    }
    private fun pending(vararg ids: String) = json(200, """{"has_more":false,"messages":[""" + ids.mapIndexed { i, id ->
        """{"id":"$id","seq":${i + 1},"payload":"QUJD","created_at":1,"sender":{"username":"bob","device_id":"d"}}""" }.joinToString(",") + "]}")

    @Test fun enqueuePersistsBeforeAnyNetworkAndKeepsTheSameClientIdOnRetry() {
        var fail = true
        val (mt, t, st) = setup { if (fail) ApiException.Timeout(java.io.IOException()) else json(202, """{"id":"srv1","state":"accepted"}""") }
        val m = mt.enqueue("Bob", "d".repeat(32), byteArrayOf(1, 2, 3))
        assertEquals(1, st.first.all().size); assertEquals(OutState.QUEUED, st.first.all()[0].state)
        val r1 = mt.flushOutbox(); assertTrue(r1.stoppedBy is ApiException.Timeout); assertEquals(OutState.QUEUED, st.first.all()[0].state); assertEquals(1, st.first.all()[0].attempts)
        fail = false
        val r2 = mt.flushOutbox(); assertEquals(1, r2.sent); assertEquals(OutState.ACCEPTED, st.first.all()[0].state)
        val ids = t.requests.map { Regex("\"client_msg_id\":\"([^\"]+)\"").find(String(it.body!!))!!.groupValues[1] }
        assertEquals(listOf(m.clientMsgId, m.clientMsgId), ids)       // to samo id => serwer nie utworzy duplikatu
        assertEquals("bob", st.first.all()[0].recipientUsername)
    }

    @Test fun orderIsPreservedTransientErrorStopsTheQueue() {
        var calls = 0
        val (mt, _, st) = setup { if (++calls == 1) json(202, """{"id":"s1","state":"accepted"}""") else json(503, "{}") }
        repeat(3) { mt.enqueue("bob", "d".repeat(32), byteArrayOf(it.toByte())) }
        val r = mt.flushOutbox()
        assertEquals(1, r.sent); assertTrue(r.stoppedBy is ApiException.Server)
        assertEquals(listOf(OutState.ACCEPTED, OutState.QUEUED, OutState.QUEUED), st.first.all().map { it.state })
        assertEquals(2, calls)                                         // trzecia nie została nawet próbowana
    }

    @Test fun permanentRejectionMarksFailedAndContinues() {
        var n = 0
        val (mt, _, st) = setup { if (++n == 1) json(404, """{"error":"Nie ma takiego odbiorcy."}""") else json(202, """{"id":"s2","state":"accepted"}""") }
        mt.enqueue("x", "d".repeat(32), byteArrayOf(1)); mt.enqueue("bob", "d".repeat(32), byteArrayOf(2))
        val r = mt.flushOutbox(); assertEquals(1, r.failedPermanently); assertEquals(1, r.sent)
        assertEquals(listOf(OutState.FAILED, OutState.ACCEPTED), st.first.all().map { it.state })
    }

    @Test fun inboxIsPersistedBeforeAck() {
        lateinit var ib: InMemoryInbox; var storedAtAck = -1
        val (mt, _, st) = setup { r -> if (r.url.endsWith("/ack")) { storedAtAck = ib.unprocessed().size; json(200, """{"results":{"m1":"ok","m2":"ok"}}""") } else pending("m1", "m2") }
        ib = st.second
        val r = mt.syncInbox()
        assertEquals(2, storedAtAck)                                   // w chwili ACK oba szyfrogramy były już zapisane
        assertEquals(2, r.received); assertEquals(2, r.acked)
    }

    @Test fun ackFailureDoesNotLoseMessagesAndRedeliveryIsDeduplicated() {
        var ackFails = true
        val (mt, _, st) = setup { r -> if (r.url.endsWith("/ack")) { if (ackFails) ApiException.Network(java.io.IOException()) else json(200, """{"results":{"m1":"ok"}}""") } else pending("m1") }
        assertThrows(ApiException.Network::class.java) { mt.syncInbox() }
        assertEquals(1, st.second.unprocessed().size)                  // zapisana mimo braku ACK
        ackFails = false
        val r = mt.syncInbox()
        assertEquals(0, r.received); assertEquals(1, r.duplicates); assertEquals(1, r.acked); assertEquals(1, st.second.unprocessed().size)
    }

    @Test fun syncAlwaysStartsFromZero() {
        val (mt, t, _) = setup { json(200, """{"has_more":false,"messages":[]}""") }
        mt.syncInbox(); assertTrue(t.requests[0].url.contains("after=0"))
    }

    @Test fun decryptFailureKeepsCiphertextAndDoesNotBlockOthers() {
        val (mt, _, st) = setup { r -> if (r.url.endsWith("/ack")) json(200, """{"results":{"m1":"ok","m2":"ok"}}""") else pending("m1", "m2") }
        mt.syncInbox()
        val cipher = object : MessageCipher {
            override fun encrypt(recipientUsername: String, recipientDeviceId: String, plaintext: ByteArray) = plaintext
            override fun decrypt(senderUsername: String, senderDeviceId: String, ciphertext: ByteArray): ByteArray = if (st.second.unprocessed().first().serverId == "m1") error("zły szyfrogram") else ciphertext
        }
        val got = ArrayList<String>()
        val r = mt.processInbox(cipher) { m, _ -> got.add(m.serverId) }
        assertEquals(1, r.failed); assertEquals(1, r.processed); assertEquals(listOf("m2"), got)
        assertEquals(listOf("m1"), st.second.unprocessed().map { it.serverId })
    }

    @Test fun fileBackedStoresSurviveRestart() {
        val fo = java.io.File.createTempFile("ob", ".bin"); val fi = java.io.File.createTempFile("ib", ".bin"); fo.delete(); fi.delete()
        FileOutbox(SealedFile(fo, NoSealerForTests)).put(OutMessage("c1", "bob", "d", "QUJD", OutState.QUEUED, null, 2, 5, "x"))
        FileInbox(SealedFile(fi, NoSealerForTests)).let { assertTrue(it.putIfAbsent(InMessage("m1", 1, "bob", "d", "QUJD", 1, 2, false))); assertFalse(it.putIfAbsent(InMessage("m1", 1, "bob", "d", "QUJD", 1, 2, false))) }
        val o = FileOutbox(SealedFile(fo, NoSealerForTests)).all(); assertEquals(1, o.size); assertEquals(2, o[0].attempts); assertEquals("x", o[0].lastError)
        val i = FileInbox(SealedFile(fi, NoSealerForTests)); assertEquals(1, i.unprocessed().size); i.markProcessed("m1")
        assertEquals(0, FileInbox(SealedFile(fi, NoSealerForTests)).unprocessed().size)
        fo.delete(); fi.delete()
    }
}
