package com.shyfr.app.api

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

class FlushResult(val sent: Int, val failedPermanently: Int, val stoppedBy: ApiException?)
class SyncResult(val received: Int, val duplicates: Int, val acked: Int)
class ProcessResult(val processed: Int, val failed: Int)

/**
 * Transport wiadomości do kolejki serwera. NIE szyfruje i NIE odszyfrowuje; operuje na szyfrogramie dostarczonym przez wywołującego.
 *
 * Gwarancje (patrz docs/2.0/SHYFR_2_DELIVERY.md):
 *  - Wysyłanie jest idempotentne: wiadomość dostaje clientMsgId przy kolejkowaniu i ZAWSZE jest wysyłana z tym samym,
 *    więc ponowienie po zerwanym połączeniu nie tworzy duplikatu. Kolejność jest zachowana: pierwszy błąd przejściowy zatrzymuje wysyłkę.
 *  - Odbiór: szyfrogram jest najpierw trwale zapisany w [InboxStore], DOPIERO POTEM wysyłamy ACK. Awaria między zapisem a ACK
 *    skutkuje ponowną dostawą z serwera, którą odrzuca deduplikacja po serverId.
 *  - Odszyfrowanie jest osobnym krokiem po ACK: błąd odszyfrowania nie traci wiadomości (szyfrogram zostaje w skrzynce).
 */
class MessageTransport(
    private val client: ShyfrApiClient,
    private val outbox: OutboxStore,
    private val inbox: InboxStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val rng: SecureRandom = SecureRandom(),
) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val sendLock = Any()
    private val syncLock = Any()

    fun enqueue(recipientUsername: String, recipientDeviceId: String, ciphertext: ByteArray): OutMessage {
        require(ciphertext.isNotEmpty()) { "pusty szyfrogram" }
        val payload = b64.encodeToString(ciphertext)
        require(payload.length <= MAX_PAYLOAD_CHARS) { "szyfrogram za duży" }
        val id = ByteArray(16).also { rng.nextBytes(it) }.let { b64.encodeToString(it) }
        val m = OutMessage(id, recipientUsername.trim().lowercase(), recipientDeviceId, payload, OutState.QUEUED, null, 0, clock(), null)
        outbox.put(m)
        return m
    }

    fun flushOutbox(): FlushResult = synchronized(sendLock) {
        var sent = 0; var failed = 0
        for (m in outbox.all().filter { it.state == OutState.QUEUED }) {
            try {
                val r = client.authed("POST", "/v1/messages", JSONObject()
                    .put("recipient_username", m.recipientUsername).put("recipient_device_id", m.recipientDeviceId)
                    .put("client_msg_id", m.clientMsgId).put("payload", m.payloadB64))
                outbox.put(m.copy(state = stateOf(r.optString("state", "accepted")), serverId = r.optString("id", "").takeIf { it.isNotEmpty() }, attempts = m.attempts + 1, lastError = null))
                sent++
            } catch (e: ApiException.Http) {                       // 400/404/409/413: ponawianie nic nie zmieni
                outbox.put(m.copy(state = OutState.FAILED, attempts = m.attempts + 1, lastError = e.userMessage())); failed++
            } catch (e: ApiException) {
                if (e.retryable) outbox.put(m.copy(attempts = m.attempts + 1, lastError = e.userMessage()))
                return FlushResult(sent, failed, e)                 // przejściowy błąd lub sesja: stop, zachowujemy kolejność
            }
        }
        FlushResult(sent, failed, null)
    }

    /** Odświeża stany wysłanych wiadomości (accepted -> delivered -> read | expired). */
    fun refreshStatuses(): Int = synchronized(sendLock) {
        var changed = 0
        for (m in outbox.all().filter { (it.state == OutState.ACCEPTED || it.state == OutState.DELIVERED) && it.serverId != null }) {
            try {
                val st = stateOf(client.authed("GET", "/v1/messages/${m.serverId}/status", null).optString("state", ""))
                if (st != m.state) { outbox.put(m.copy(state = st)); changed++ }
            } catch (e: ApiException.Http) { /* 404: wiadomości już nie ma po stronie serwera */ }
        }
        changed
    }

    /** Pobiera zaległe wiadomości: zapis -> ACK. Zawsze zaczyna od after=0 (po restarcie nic nie może zostać pominięte). */
    fun syncInbox(): SyncResult = synchronized(syncLock) {
        var received = 0; var dups = 0; var acked = 0
        repeat(MAX_PAGES) {
            val r = client.authed("GET", "/v1/messages/pending?after=0&wait=0", null)
            val arr: JSONArray = r.getJSONArray("messages")
            if (arr.length() == 0) return SyncResult(received, dups, acked)
            val ids = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val s = o.getJSONObject("sender")
                val fresh = inbox.putIfAbsent(InMessage(o.getString("id"), o.getLong("seq"), s.getString("username"), s.getString("device_id"),
                    o.getString("payload"), o.getLong("created_at"), clock(), false))
                if (fresh) received++ else dups++
                ids.add(o.getString("id"))
            }
            // wszystkie szyfrogramy z tej strony są już trwale zapisane; dopiero teraz potwierdzamy
            val res = client.authed("POST", "/v1/messages/ack", JSONObject().put("ids", JSONArray(ids))).getJSONObject("results")
            acked += ids.count { res.optString(it) == "ok" }
            if (!r.optBoolean("has_more", false)) return SyncResult(received, dups, acked)
        }
        SyncResult(received, dups, acked)
    }

    /** Odszyfrowuje zapisane wiadomości. Błąd odszyfrowania jednej nie blokuje pozostałych i nie usuwa jej ze skrzynki. */
    fun processInbox(cipher: MessageCipher, handle: (InMessage, ByteArray) -> Unit): ProcessResult {
        var ok = 0; var bad = 0
        for (m in inbox.unprocessed()) {
            try {
                val plain = cipher.decrypt(m.senderUsername, m.senderDeviceId, Base64.getUrlDecoder().decode(m.payloadB64))
                handle(m, plain); inbox.markProcessed(m.serverId); ok++
            } catch (e: com.shyfr.app.msg.CipherException) { if (e.drop) inbox.markProcessed(m.serverId); bad++
            } catch (e: Exception) { bad++ }
        }
        return ProcessResult(ok, bad)
    }

    /** Potwierdzenie przeczytania (wysyłać tylko za zgodą użytkownika: ustawienie potwierdzeń). */
    fun markRead(serverId: String) { client.authed("POST", "/v1/messages/$serverId/read", null) }

    private fun stateOf(s: String) = when (s) {
        "delivered" -> OutState.DELIVERED; "read" -> OutState.READ; "expired" -> OutState.EXPIRED; else -> OutState.ACCEPTED
    }

    companion object { const val MAX_PAYLOAD_CHARS = 90_000; const val MAX_PAGES = 20 }
}
