package com.shyfr.app.msg

import com.shyfr.app.api.SealedFile
import org.json.JSONArray
import org.json.JSONObject

enum class Direction { IN, OUT }

/** PREPARING: czeka na zaszyfrowanie (np. brak sieci do pobrania kluczy). QUEUED: szyfrogram w Outbox. ACCEPTED: serwer przyjął (NIE znaczy: dostarczono). DELIVERED: urządzenie odbiorcy potwierdziło odbiór. */
enum class MsgState { PREPARING, QUEUED, ACCEPTED, DELIVERED, READ, FAILED, EXPIRED, RECEIVED }

data class ChatMessage(
    val id: String, val peer: String, val dir: Direction, val text: String, val tsMs: Long, val state: MsgState,
    val parts: List<String> = emptyList(),   // clientMsgId wpisów Outbox (jeden na urządzenie odbiorcy)
    val serverId: String? = null, val error: String? = null, val unread: Boolean = false, val receiptSent: Boolean = false,
)

data class ConversationSummary(val peer: String, val displayName: String, val last: ChatMessage, val unread: Int)

object Conversations {
    fun summarize(all: List<ChatMessage>, names: Map<String, String>, query: String = ""): List<ConversationSummary> {
        val q = query.trim().lowercase()
        return all.groupBy { it.peer }.map { (peer, ms) ->
            val last = ms.maxBy { it.tsMs }
            ConversationSummary(peer, names[peer]?.takeIf { it.isNotBlank() } ?: peer, last, ms.count { it.unread })
        }.filter { q.isEmpty() || it.peer.contains(q) || it.displayName.lowercase().contains(q) }.sortedByDescending { it.last.tsMs }
    }

    fun thread(all: List<ChatMessage>, peer: String) = all.filter { it.peer == peer }.sortedWith(compareBy({ it.tsMs }, { it.id }))
}

interface ConversationStore {
    fun all(): List<ChatMessage>
    fun get(id: String): ChatMessage?
    fun upsert(m: ChatMessage)
    fun contactNames(): Map<String, String>
    fun setContactName(peer: String, name: String)
    fun deleteConversation(peer: String)
    fun clear()
}

class InMemoryConversations : ConversationStore {
    private val m = LinkedHashMap<String, ChatMessage>(); private val n = HashMap<String, String>()
    @Synchronized override fun all() = m.values.toList()
    @Synchronized override fun get(id: String) = m[id]
    @Synchronized override fun upsert(m: ChatMessage) { this.m[m.id] = m }
    @Synchronized override fun contactNames() = HashMap(n)
    @Synchronized override fun setContactName(peer: String, name: String) { n[peer] = name }
    @Synchronized override fun deleteConversation(peer: String) { m.values.removeAll { it.peer == peer } }
    @Synchronized override fun clear() { m.clear(); n.clear() }
}

/** Historia rozmów w zaszyfrowanym pliku (SealedFile = klucz z Android Keystore). Zapis atomowy; cały stan w pamięci, zapis po każdej zmianie. */
class FileConversationStore(private val f: SealedFile) : ConversationStore {
    private var msgs: LinkedHashMap<String, ChatMessage>? = null
    private var names: HashMap<String, String> = HashMap()

    @Synchronized private fun load(): LinkedHashMap<String, ChatMessage> {
        msgs?.let { return it }
        val out = LinkedHashMap<String, ChatMessage>()
        val root = f.read()?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        root?.optJSONObject("names")?.let { o -> o.keys().forEach { k -> names[k] = o.getString(k) } }
        root?.optJSONArray("msgs")?.let { a ->
            for (i in 0 until a.length()) runCatching {
                val o = a.getJSONObject(i)
                val parts = o.getJSONArray("parts").let { p -> (0 until p.length()).map { p.getString(it) } }
                val m = ChatMessage(o.getString("id"), o.getString("peer"), Direction.valueOf(o.getString("dir")), o.getString("text"), o.getLong("ts"),
                    MsgState.valueOf(o.getString("st")), parts, o.optString("sid", "").ifEmpty { null }, o.optString("err", "").ifEmpty { null },
                    o.optBoolean("unread", false), o.optBoolean("rs", false))
                out[m.id] = m
            }
        }
        msgs = out; return out
    }

    @Synchronized private fun save() {
        val a = JSONArray()
        load().values.forEach { a.put(JSONObject().put("id", it.id).put("peer", it.peer).put("dir", it.dir.name).put("text", it.text).put("ts", it.tsMs)
            .put("st", it.state.name).put("parts", JSONArray(it.parts)).put("sid", it.serverId ?: "").put("err", it.error ?: "")
            .put("unread", it.unread).put("rs", it.receiptSent)) }
        val n = JSONObject(); names.forEach { (k, v) -> n.put(k, v) }
        f.write(JSONObject().put("msgs", a).put("names", n).toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized override fun all() = load().values.toList()
    @Synchronized override fun get(id: String) = load()[id]
    @Synchronized override fun upsert(m: ChatMessage) { load()[m.id] = m; save() }
    @Synchronized override fun contactNames(): Map<String, String> { load(); return HashMap(names) }
    @Synchronized override fun setContactName(peer: String, name: String) { load(); names[peer] = name; save() }
    @Synchronized override fun deleteConversation(peer: String) { load().values.removeAll { it.peer == peer }; save() }
    @Synchronized override fun clear() { msgs = LinkedHashMap(); names = HashMap(); f.delete() }
}
