package com.shyfr.app.api

import org.json.JSONArray
import org.json.JSONObject

enum class OutState { QUEUED, ACCEPTED, DELIVERED, READ, FAILED, EXPIRED }

/** Wiadomość wychodząca. [payloadB64] to NIEPRZEZROCZYSTY szyfrogram: ta warstwa nie wie, co w nim jest. */
data class OutMessage(
    val clientMsgId: String, val recipientUsername: String, val recipientDeviceId: String, val payloadB64: String,
    val state: OutState, val serverId: String?, val attempts: Int, val createdAtMs: Long, val lastError: String?,
)

data class InMessage(
    val serverId: String, val seq: Long, val senderUsername: String, val senderDeviceId: String, val payloadB64: String,
    val createdAtSec: Long, val receivedAtMs: Long, val processed: Boolean,
)

/** Trwała kolejka wychodząca. [put] to upsert po clientMsgId. Implementacja musi być odporna na awarię procesu w trakcie zapisu. */
interface OutboxStore {
    fun put(m: OutMessage)
    fun all(): List<OutMessage>
}

/** Trwała skrzynka odbiorcza: tu ląduje szyfrogram ZANIM wyślemy ACK. */
interface InboxStore {
    /** true, gdy zapisano nową; false, gdy taka wiadomość (serverId) już jest. */
    fun putIfAbsent(m: InMessage): Boolean
    fun unprocessed(): List<InMessage>
    fun markProcessed(serverId: String)
}

class InMemoryOutbox : OutboxStore {
    private val m = LinkedHashMap<String, OutMessage>()
    @Synchronized override fun put(m: OutMessage) { this.m[m.clientMsgId] = m }
    @Synchronized override fun all() = m.values.toList()
}

class InMemoryInbox : InboxStore {
    private val m = LinkedHashMap<String, InMessage>()
    @Synchronized override fun putIfAbsent(m: InMessage): Boolean { if (this.m.containsKey(m.serverId)) return false; this.m[m.serverId] = m; return true }
    @Synchronized override fun unprocessed() = m.values.filter { !it.processed }.sortedBy { it.seq }
    @Synchronized override fun markProcessed(serverId: String) { m[serverId]?.let { this.m[serverId] = it.copy(processed = true) } }
}

/** Kolejka wychodząca w zaszyfrowanym pliku (fundament; docelowo do przeniesienia do bazy sejfu SQLCipher). */
class FileOutbox(private val f: SealedFile) : OutboxStore {
    @Synchronized override fun put(m: OutMessage) {
        val list = all().filter { it.clientMsgId != m.clientMsgId } + m
        // zachowujemy kolejność utworzenia
        write(list.sortedBy { it.createdAtMs })
    }

    @Synchronized override fun all(): List<OutMessage> {
        val a = f.read()?.let { runCatching { JSONArray(String(it, Charsets.UTF_8)) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                OutMessage(it.getString("c"), it.getString("ru"), it.getString("rd"), it.getString("p"), OutState.valueOf(it.getString("s")),
                    it.optString("sid", "").takeIf { s -> s.isNotEmpty() }, it.getInt("a"), it.getLong("t"), it.optString("e", "").takeIf { s -> s.isNotEmpty() })
            }
        }
    }

    private fun write(list: List<OutMessage>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("c", it.clientMsgId).put("ru", it.recipientUsername).put("rd", it.recipientDeviceId).put("p", it.payloadB64)
            .put("s", it.state.name).put("sid", it.serverId ?: "").put("a", it.attempts).put("t", it.createdAtMs).put("e", it.lastError ?: "")) }
        f.write(a.toString().toByteArray(Charsets.UTF_8))
    }
}

class FileInbox(private val f: SealedFile) : InboxStore {
    @Synchronized private fun all(): List<InMessage> {
        val a = f.read()?.let { runCatching { JSONArray(String(it, Charsets.UTF_8)) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).map { i ->
            a.getJSONObject(i).let { InMessage(it.getString("id"), it.getLong("q"), it.getString("su"), it.getString("sd"), it.getString("p"), it.getLong("c"), it.getLong("r"), it.getBoolean("x")) }
        }
    }

    private fun write(list: List<InMessage>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.serverId).put("q", it.seq).put("su", it.senderUsername).put("sd", it.senderDeviceId).put("p", it.payloadB64)
            .put("c", it.createdAtSec).put("r", it.receivedAtMs).put("x", it.processed)) }
        f.write(a.toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized override fun putIfAbsent(m: InMessage): Boolean {
        val cur = all(); if (cur.any { it.serverId == m.serverId }) return false
        write(cur + m); return true
    }

    @Synchronized override fun unprocessed() = all().filter { !it.processed }.sortedBy { it.seq }

    @Synchronized override fun markProcessed(serverId: String) { write(all().map { if (it.serverId == serverId) it.copy(processed = true) else it }) }
}

/**
 * Szyfrowanie wiadomości. Wiadomości SHYFR 2.0 będą szyfrowane protokołem wybranym w docs/2.0/SHYFR_2_CRYPTO_DECISION.md.
 * ŻADNA produkcyjna implementacja nie istnieje: dopóki jej nie ma, E2EE w SHYFR 2.0 NIE jest zaimplementowane
 * i interfejs użytkownika nie może wysyłać wiadomości tą ścieżką.
 */
interface MessageCipher {
    fun encrypt(recipientUsername: String, recipientDeviceId: String, plaintext: ByteArray): ByteArray
    fun decrypt(senderUsername: String, senderDeviceId: String, ciphertext: ByteArray): ByteArray
}
