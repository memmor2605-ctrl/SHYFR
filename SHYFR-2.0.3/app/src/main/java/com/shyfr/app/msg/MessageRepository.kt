package com.shyfr.app.msg

import com.shyfr.app.api.*
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** Błąd kryptograficzny. [permanent]=true: ponowienie nic nie zmieni (np. zmiana klucza rozmówcy wymaga decyzji użytkownika). */
/** [drop]=true: szyfrogramu nie da się i nie trzeba odzyskać (duplikat, uszkodzony); wiadomość jest usuwana ze skrzynki zamiast być ponawiana w nieskończoność. */
class CipherException(message: String, val permanent: Boolean, cause: Throwable? = null, val drop: Boolean = false) : Exception(message, cause)

/** E2EE niedostępne w tym buildzie (brak biblioteki protokołu). Wysyłanie jest wtedy ODMAWIANE: nigdy nie wysyłamy jawnego tekstu ani "udawanego" szyfrowania. */
class FailClosedCipher(private val reason: String) : MessageCipher {
    override fun encrypt(recipientUsername: String, recipientDeviceId: String, plaintext: ByteArray): ByteArray = throw CipherException(reason, true)
    override fun decrypt(senderUsername: String, senderDeviceId: String, ciphertext: ByteArray): ByteArray = throw CipherException(reason, true)
}

/** Weryfikacja tożsamości rozmówcy (numer bezpieczeństwa) i obsługa zmiany klucza. Implementuje ją szyfr oparty na libsignal. */
interface IdentityVerifier {
    /** Numer bezpieczeństwa do porównania na żywo z rozmówcą; null, gdy nie ma jeszcze sesji. */
    fun safetyNumber(selfUsername: String, peerUsername: String): String?
    /** Akceptuje NOWY klucz rozmówcy po tym, jak użytkownik zweryfikował go poza aplikacją. */
    fun trustChangedIdentity(peerUsername: String)
}

class PumpResult(val sent: Int, val received: Int, val delivered: Int, val decryptFailures: Int, val error: ApiException?)

/**
 * Jedyna warstwa łącząca UI z transportem: Chat UI -> ViewModel -> MessageRepository -> MessageTransport -> MessageCipher -> API.
 * Zasady:
 *  - UI nigdy nie wysyła jawnego tekstu: treść trafia do [cipher], a do [MessageTransport] idzie wyłącznie szyfrogram.
 *  - "Dostarczono" pochodzi z serwera dopiero po ACK urządzenia odbiorcy; "przyjęto" to osobny, niższy stan.
 *  - [pump] jest idempotentne i bezpieczne do wołania z UI, z WorkManagera i z FCM równocześnie (blokada).
 */
class MessageRepository(
    private val client: ShyfrApiClient,
    private val transport: MessageTransport,
    private val outbox: OutboxStore,
    private val store: ConversationStore,
    private val cipher: MessageCipher,
    private val keys: RemoteKeys,
    private val clock: () -> Long = System::currentTimeMillis,
    private val rng: SecureRandom = SecureRandom(),
) {
    private val pumpLock = Any()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun messages(): List<ChatMessage> = store.all()
    fun names(): Map<String, String> = store.contactNames()
    fun verifier(): IdentityVerifier? = cipher as? IdentityVerifier

    /** Sprawdza, że użytkownik istnieje, i zapamiętuje jego pseudonim. 404 => ApiException.Http(404). */
    fun startConversation(username: String): String {
        val u = username.trim().lowercase()
        require(u.matches(Regex("[a-z0-9_]{1,40}"))) { "Nieprawidłowa nazwa użytkownika." }
        val p = client.profileOf(u)
        store.setContactName(u, p?.displayName ?: u)
        return u
    }

    /** Zapisuje wiadomość lokalnie (PREPARING) i natychmiast próbuje wysłać. Nic nie ginie, gdy brak sieci: zostaje w kolejce. */
    fun send(peer: String, text: String): ChatMessage {
        val body = text.trim(); require(body.isNotEmpty()) { "Pusta wiadomość." }
        val id = b64.encodeToString(ByteArray(12).also { rng.nextBytes(it) })
        val m = ChatMessage(id, peer.trim().lowercase(), Direction.OUT, body, clock(), MsgState.PREPARING)
        store.upsert(m)
        return m
    }

    fun retry(id: String) {
        val m = store.get(id) ?: return
        if (m.dir == Direction.OUT && m.state == MsgState.FAILED) store.upsert(m.copy(state = MsgState.PREPARING, parts = emptyList(), error = null))
    }

    /** Oznacza rozmowę jako przeczytaną; potwierdzenia przeczytania idą na serwer tylko gdy [sendReceipts]. */
    fun markRead(peer: String, sendReceipts: Boolean) {
        for (m in store.all().filter { it.peer == peer && it.dir == Direction.IN && (it.unread || (sendReceipts && !it.receiptSent)) }) {
            var sent = m.receiptSent
            if (sendReceipts && !sent && m.serverId != null) sent = try { transport.markRead(m.serverId); true } catch (e: ApiException) { false }
            store.upsert(m.copy(unread = false, receiptSent = sent))
        }
    }

    fun pump(): PumpResult = synchronized(pumpLock) {
        var err: ApiException? = null; var sent = 0; var recv = 0; var deliv = 0; var bad = 0
        fun step(block: () -> Unit) { if (err == null) try { block() } catch (e: ApiException) { err = e } }
        step { encryptPending() }
        step { sent = transport.flushOutbox().also { it.stoppedBy?.let { e -> err = e } }.sent }
        step { transport.refreshStatuses() }
        syncStates().also { deliv = it }
        step { recv = transport.syncInbox().received }
        bad = transport.processInbox(cipher) { m, plain -> handleIncoming(m, plain) }.failed   // odszyfrowanie działa lokalnie, także bez sieci
        PumpResult(sent, recv, deliv, bad, err)
    }

    // ------------------------------------------------------------------ wysyłanie
    private fun encryptPending() {
        for (m in store.all().filter { it.dir == Direction.OUT && it.state == MsgState.PREPARING }.sortedBy { it.tsMs }) {
            val devices = try { keys.bundles(m.peer) } catch (e: ApiException.Http) {
                if (e.status == 404) { store.upsert(m.copy(state = MsgState.FAILED, error = "Nie ma takiego użytkownika.")); continue }
                throw e
            }
            if (devices.isEmpty()) { store.upsert(m.copy(state = MsgState.FAILED, error = "Rozmówca nie ma aktywnego urządzenia.")); continue }
            val plain = JSONObject().put("v", 1).put("t", "text").put("id", m.id).put("body", m.text).put("ts", m.tsMs).toString().toByteArray(Charsets.UTF_8)
            val parts = ArrayList<String>()
            try {
                for (d in devices) parts.add(transport.enqueue(m.peer, d.deviceId, cipher.encrypt(m.peer, d.deviceId, plain)).clientMsgId)
                store.upsert(m.copy(state = MsgState.QUEUED, parts = parts, error = null))
            } catch (e: CipherException) {
                // nic nie opuściło urządzenia w postaci jawnej; wpisy już dodane do Outbox (jeśli były) i tak niosą tylko szyfrogram, a odbiorca odrzuci duplikat po id koperty
                store.upsert(m.copy(state = if (e.permanent) MsgState.FAILED else MsgState.PREPARING, error = e.message, parts = parts))
            }
        }
    }

    /** Przelicza stan wiadomości wychodzących z Outbox. Wiele urządzeń odbiorcy: pokazujemy najdalszy postęp (dostarczono na co najmniej jedno). */
    private fun syncStates(): Int {
        val byId = outbox.all().associateBy { it.clientMsgId }; var delivered = 0
        for (m in store.all().filter { it.dir == Direction.OUT && it.parts.isNotEmpty() && it.state != MsgState.FAILED && it.state != MsgState.READ }) {
            val st = m.parts.mapNotNull { byId[it]?.state }
            if (st.isEmpty()) continue
            val rank = { s: OutState -> when (s) { OutState.QUEUED -> 1; OutState.ACCEPTED -> 2; OutState.DELIVERED -> 3; OutState.READ -> 4; else -> 0 } }
            val best = st.maxBy(rank)
            val next = if (rank(best) == 0) (if (st.all { it == OutState.EXPIRED }) MsgState.EXPIRED else MsgState.FAILED) else when (best) {
                OutState.QUEUED -> MsgState.QUEUED; OutState.ACCEPTED -> MsgState.ACCEPTED; OutState.DELIVERED -> MsgState.DELIVERED; else -> MsgState.READ }
            val err = if (next == MsgState.FAILED) st.firstNotNullOfOrNull { byId[m.parts.first()]?.lastError } else null
            if (next != m.state) { store.upsert(m.copy(state = next, error = err)); if (next == MsgState.DELIVERED) delivered++ }
        }
        return delivered
    }

    // ------------------------------------------------------------------ odbiór
    private fun handleIncoming(m: InMessage, plain: ByteArray) {
        val o = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return   // uszkodzona koperta: porzucamy, inaczej ponawiana w nieskończoność
        if (o.optString("t") != "text" || o.optInt("v") != 1) return                             // nieznany typ: ignorujemy (zgodność w przód)
        val envId = o.optString("id").takeIf { it.isNotEmpty() && it.length <= 64 } ?: return
        val id = "in:" + m.senderUsername + ":" + envId
        if (store.get(id) != null) return                                                         // ten sam komunikat z innej ścieżki (np. ponowne szyfrowanie po awarii)
        store.upsert(ChatMessage(id, m.senderUsername, Direction.IN, o.optString("body").take(20_000), m.createdAtSec * 1000, MsgState.RECEIVED, serverId = m.serverId, unread = true))
    }
}
