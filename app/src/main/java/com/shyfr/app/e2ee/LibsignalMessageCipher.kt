// ============================================================================================================
// E2EE SHYFR 2.0: libsignal (PQXDH + Double Ratchet), AGPL-3.0. Żadnej własnej kryptografii: ten plik tylko
//  - przechowuje stan protokołu (klucze, sesje, zaufane tożsamości) w zaszyfrowanym pliku (Android Keystore),
//  - wywołuje SessionBuilder / SessionCipher z libsignal,
//  - publikuje na serwerze WYŁĄCZNIE klucze publiczne.
// Wersja biblioteki jest przypięta w gradle.properties (libsignalVersion). Kod nie był kompilowany lokalnie:
// pierwszą weryfikacją sygnatur API jest build w GitHub Actions (zob. README, sekcja „Pierwszy build").
// ============================================================================================================
package com.shyfr.app.e2ee

import com.shyfr.app.api.*
import com.shyfr.app.msg.CipherException
import com.shyfr.app.msg.IdentityVerifier
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.*
import java.io.File
import java.security.SecureRandom
import java.util.Base64

private val B64 = Base64.getUrlEncoder().withoutPadding()
private val B64D = Base64.getUrlDecoder()
private fun enc(b: ByteArray): String = B64.encodeToString(b)
private fun dec(s: String): ByteArray = B64D.decode(s)

/** Adres sesji: nazwa = "login:deviceId", deviceId protokołu = 1 (identyfikatory urządzeń SHYFR to 32 znaki hex, nie mieszczą się w int). */
internal fun addr(user: String, deviceId: String) = SignalProtocolAddress(user.lowercase() + ":" + deviceId, 1)

/** Stanu E2EE nie da się odczytać (np. utracony klucz Keystore). Nie generujemy po cichu nowej tożsamości: to zerwałoby rozmowy bez ostrzeżenia. */
class E2eeStateException(message: String) : IllegalStateException(message)

/** Jeden egzemplarz magazynu na proces: rejestracja urządzenia, szyfr i provisioner muszą widzieć ten sam stan w pamięci. */
object E2eeRuntime {
    private var cached: Pair<String, SignalStateStore>? = null

    @Synchronized fun store(dir: File, sealer: Sealer): SignalStateStore {
        val f = File(dir, "signal_state.bin")
        cached?.let { if (it.first == f.path) return it.second }
        return SignalStateStore(SealedFile(f, sealer)).also { cached = f.path to it }
    }

    @Synchronized fun reset() { cached = null }
}

/**
 * Trwały magazyn protokołu: tożsamość, prekeye, sesje, zaufane tożsamości rozmówców (TOFU).
 * Cały stan jest jednym blobem w [SealedFile] (AES-GCM, klucz w Android Keystore). Klucze prywatne NIGDY nie opuszczają urządzenia.
 */
class SignalStateStore(private val file: SealedFile) : SignalProtocolStore {
    private lateinit var identity: IdentityKeyPair
    private var regId: Int = 0
    private var ownerName: String? = null
    private val preKeys = HashMap<Int, ByteArray>(); private val signed = HashMap<Int, ByteArray>(); private val kyber = HashMap<Int, ByteArray>()
    private val sessions = HashMap<String, ByteArray>(); private val trusted = HashMap<String, ByteArray>()
    private val lastResort = HashSet<Int>()
    private val senderKeys = HashMap<String, ByteArray>()
    private var nextPreKeyId = 1; private var nextKyberId = 1

    init {
        if (file.exists()) {
            val raw = file.read() ?: throw E2eeStateException("Nie można odczytać kluczy szyfrowania na tym urządzeniu (klucz Keystore utracony lub plik uszkodzony).")
            loadState(JSONObject(String(raw, Charsets.UTF_8)))
        } else resetIdentity()
    }

    private fun loadState(root: JSONObject) {
        identity = IdentityKeyPair(dec(root.getString("identity"))); regId = root.getInt("reg"); ownerName = root.optString("owner", "").takeIf { it.isNotEmpty() }
        nextPreKeyId = root.optInt("np", 1); nextKyberId = root.optInt("nk", 1)
        fun load(name: String, into: MutableMap<Int, ByteArray>) { root.optJSONObject(name)?.let { o -> o.keys().forEach { k -> into[k.toInt()] = dec(o.getString(k)) } } }
        fun loadS(name: String, into: MutableMap<String, ByteArray>) { root.optJSONObject(name)?.let { o -> o.keys().forEach { k -> into[k] = dec(o.getString(k)) } } }
        loadS("senderKeys", senderKeys); load("pre", preKeys); load("signed", signed); load("kyber", kyber); loadS("sessions", sessions); loadS("trusted", trusted)
        root.optJSONArray("lr")?.let { a -> for (i in 0 until a.length()) lastResort.add(a.getInt(i)) }
    }

    /** Nowa tożsamość i czysty stan (pierwsze uruchomienie albo zmiana konta na tym telefonie). */
    @Synchronized fun resetIdentity() {
        identity = IdentityKeyPair.generate(); regId = SecureRandom().nextInt(16380) + 1; ownerName = null
        preKeys.clear(); signed.clear(); kyber.clear(); sessions.clear(); trusted.clear(); lastResort.clear(); senderKeys.clear()
        nextPreKeyId = 1; nextKyberId = 1
        persist()
    }

    /** Konto, do którego należy ten stan. Zmiana konta na tym samym telefonie nie może dziedziczyć kluczy i sesji poprzedniego. */
    val owner: String?
        @Synchronized get() = ownerName
    @Synchronized fun bindOwner(username: String) { val u = username.trim().lowercase(); if (ownerName != u) { ownerName = u; persist() } }

    val identityPublicB64: String get() = enc(identity.publicKey.serialize())

    @Synchronized private fun persist() {
        fun m(src: Map<*, ByteArray>) = JSONObject().also { o -> src.forEach { (k, v) -> o.put(k.toString(), enc(v)) } }
        file.write(JSONObject().put("identity", enc(identity.serialize())).put("reg", regId).put("owner", ownerName ?: "").put("np", nextPreKeyId).put("nk", nextKyberId)
            .put("pre", m(preKeys)).put("signed", m(signed)).put("kyber", m(kyber)).put("sessions", m(sessions)).put("trusted", m(trusted))
            .put("lr", JSONArray(lastResort.toList())).put("senderKeys", m(senderKeys)).toString().toByteArray(Charsets.UTF_8))
    }

    // --- IdentityKeyStore
    override fun getIdentityKeyPair(): IdentityKeyPair = identity
    override fun getLocalRegistrationId(): Int = regId
    /** libsignal 0.63: true, gdy podmieniono wcześniej zapamiętany, INNY klucz tej tożsamości. */
    @Synchronized override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val old = trusted[address.name]; trusted[address.name] = identityKey.serialize(); persist()
        return old != null && !old.contentEquals(identityKey.serialize())
    }
    /** TOFU: pierwszy klucz jest zaufany, zmiana wymaga jawnej decyzji użytkownika ([trustReplace]). */
    @Synchronized override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val old = trusted[address.name] ?: return true; return old.contentEquals(identityKey.serialize())
    }
    @Synchronized override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = trusted[address.name]?.let { IdentityKey(it) }
    @Synchronized fun trustReplace(a: SignalProtocolAddress, key: IdentityKey) { trusted[a.name] = key.serialize(); sessions.remove(a.toString()); persist() }
    @Synchronized fun peerIdentity(user: String): IdentityKey? =
        trusted.keys.filter { it.startsWith(user.trim().lowercase() + ":") }.minOrNull()?.let { IdentityKey(trusted.getValue(it)) }

    // --- PreKeyStore
    @Synchronized override fun loadPreKey(preKeyId: Int): PreKeyRecord = PreKeyRecord(preKeys[preKeyId] ?: throw InvalidKeyIdException("brak prekeya $preKeyId"))
    @Synchronized override fun storePreKey(preKeyId: Int, record: PreKeyRecord) { preKeys[preKeyId] = record.serialize(); persist() }
    @Synchronized override fun containsPreKey(preKeyId: Int): Boolean = preKeys.containsKey(preKeyId)
    @Synchronized override fun removePreKey(preKeyId: Int) { preKeys.remove(preKeyId); persist() }

    // --- SignedPreKeyStore
    @Synchronized override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = SignedPreKeyRecord(signed[signedPreKeyId] ?: throw InvalidKeyIdException("brak podpisanego prekeya $signedPreKeyId"))
    @Synchronized override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = signed.values.map { SignedPreKeyRecord(it) }
    @Synchronized override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) { signed[signedPreKeyId] = record.serialize(); persist() }
    @Synchronized override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = signed.containsKey(signedPreKeyId)
    @Synchronized override fun removeSignedPreKey(signedPreKeyId: Int) { signed.remove(signedPreKeyId); persist() }

    // --- KyberPreKeyStore: jednorazowe są usuwane po użyciu; last-resort zostaje, a powtórzenie tej samej trójki (kyber, signed, baseKey) = powtórka
    @Synchronized override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = KyberPreKeyRecord(kyber[kyberPreKeyId] ?: throw InvalidKeyIdException("brak prekeya KEM $kyberPreKeyId"))
    @Synchronized override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = kyber.values.map { KyberPreKeyRecord(it) }
    @Synchronized override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) { kyber[kyberPreKeyId] = record.serialize(); persist() }
    @Synchronized override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = kyber.containsKey(kyberPreKeyId)
    /** libsignal 0.63: tylko id. Jednorazowe prekeye KEM są usuwane po użyciu; last-resort zostaje (wykrywanie powtórek last-resort wymaga nowszej wersji libsignal). */
    @Synchronized override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        if (lastResort.contains(kyberPreKeyId)) return
        kyber.remove(kyberPreKeyId); persist()
    }

    // --- SessionStore
    @Synchronized override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val b = sessions[address.toString()] ?: return SessionRecord()
        return SessionRecord(b)
    }
    @Synchronized override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { a -> SessionRecord(sessions[a.toString()] ?: throw NoSessionException("brak sesji $a")) }
    @Synchronized override fun getSubDeviceSessions(name: String): List<Int> = emptyList()
    @Synchronized override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) { sessions[address.toString()] = record.serialize(); persist() }
    @Synchronized override fun containsSession(address: SignalProtocolAddress): Boolean = sessions.containsKey(address.toString())
    @Synchronized override fun deleteSession(address: SignalProtocolAddress) { sessions.remove(address.toString()); persist() }
    @Synchronized override fun deleteAllSessions(name: String) { sessions.keys.removeAll { it.startsWith(name) }; persist() }

    // --- SenderKeyStore (wymagany przez SignalProtocolStore; komunikator używa tylko sesji 1:1)
    @Synchronized override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: java.util.UUID, record: SenderKeyRecord) { senderKeys["$sender|$distributionId"] = record.serialize(); persist() }
    @Synchronized override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: java.util.UUID): SenderKeyRecord? = senderKeys["$sender|$distributionId"]?.let { SenderKeyRecord(it) }

    // --- zarządzanie pulą kluczy (publikowanych na serwerze)
    @Synchronized fun oneTimeCount() = preKeys.size
    @Synchronized fun isLastResort(id: Int) = lastResort.contains(id)
    @Synchronized fun markLastResort(id: Int) { lastResort.add(id); persist() }
    /** Rezerwuje [n] kolejnych, nigdy wcześniej niewydanych identyfikatorów (licznik jest trwały, więc id jednorazowych kluczy się nie powtarzają). */
    @Synchronized fun reservePreKeyIds(n: Int): Int { if (nextPreKeyId > 0xFFFFFF - n) nextPreKeyId = 1; val s = nextPreKeyId; nextPreKeyId += n; persist(); return s }
    @Synchronized fun reserveKyberIds(n: Int): Int { if (nextKyberId > 0xFFFFFF - n) nextKyberId = 1; val s = nextKyberId; nextKyberId += n; persist(); return s }
}

/** Szyfr wiadomości oparty na libsignal. Sesja z urządzeniem rozmówcy powstaje przy pierwszym wysłaniu (PQXDH z katalogu serwera). */
class LibsignalMessageCipher(private val state: SignalStateStore, private val keys: RemoteKeys) : MessageCipher, IdentityVerifier {

    @Synchronized override fun encrypt(recipientUsername: String, recipientDeviceId: String, plaintext: ByteArray): ByteArray {
        val a = addr(recipientUsername, recipientDeviceId)
        try {
            if (!state.containsSession(a)) establish(recipientUsername, recipientDeviceId, a)
            val m = SessionCipher(state, a).encrypt(plaintext)
            return byteArrayOf(m.type.toByte()) + m.serialize()
        } catch (e: UntrustedIdentityException) {
            throw CipherException("Klucz rozmówcy się zmienił. Zweryfikuj numer bezpieczeństwa i zaufaj nowemu kluczowi.", true, e)
        } catch (e: CipherException) { throw e
        } catch (e: ApiException) { throw e
        } catch (e: Exception) { throw CipherException("Nie udało się zaszyfrować wiadomości.", false, e) }
    }

    @Synchronized override fun decrypt(senderUsername: String, senderDeviceId: String, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < 2) throw CipherException("Uszkodzony szyfrogram.", true, drop = true)
        val a = addr(senderUsername, senderDeviceId); val body = ciphertext.copyOfRange(1, ciphertext.size)
        try {
            val c = SessionCipher(state, a)
            return when (ciphertext[0].toInt()) {
                CiphertextMessage.PREKEY_TYPE -> c.decrypt(PreKeySignalMessage(body))
                CiphertextMessage.WHISPER_TYPE -> c.decrypt(SignalMessage(body))
                else -> throw CipherException("Nieznany typ szyfrogramu.", true, drop = true)
            }
        } catch (e: CipherException) { throw e
        } catch (e: UntrustedIdentityException) { throw CipherException("Klucz nadawcy się zmienił. Zweryfikuj numer bezpieczeństwa i zaufaj nowemu kluczowi.", true, e)
        } catch (e: DuplicateMessageException) { throw CipherException("Powtórzona wiadomość.", true, e, drop = true)
        } catch (e: Exception) { throw CipherException("Nie udało się odszyfrować wiadomości.", true, e) }
    }

    private fun establish(user: String, deviceId: String, a: SignalProtocolAddress) {
        val d = keys.bundles(user).firstOrNull { it.deviceId == deviceId } ?: throw CipherException("Nie ma takiego urządzenia rozmówcy.", true)
        val sp = d.signedPreKey ?: throw CipherException("Rozmówca nie opublikował kluczy.", true)
        val ky = d.kyberPreKey ?: throw CipherException("Rozmówca nie opublikował kluczy post-kwantowych.", true)
        val otk = d.oneTimePreKey
        val bundle = PreKeyBundle(1, 1, otk?.keyId ?: -1, otk?.let { ECPublicKey(dec(it.keyB64)) },
            sp.keyId, ECPublicKey(dec(sp.keyB64)), dec(sp.signatureB64 ?: throw CipherException("Brak podpisu klucza.", true)), IdentityKey(dec(d.identityKeyB64)),
            ky.keyId, KEMPublicKey(dec(ky.keyB64)), dec(ky.signatureB64 ?: throw CipherException("Brak podpisu klucza KEM.", true)))
        SessionBuilder(state, a).process(bundle)   // libsignal sprawdza podpisy i zaufanie do tożsamości; rzuca przy błędzie
    }

    /** Numer bezpieczeństwa (60 cyfr) do porównania na żywo. Liczony dla pierwszego znanego urządzenia rozmówcy; null, dopóki nie ma sesji. */
    override fun safetyNumber(selfUsername: String, peerUsername: String): String? {
        val peerKey = state.peerIdentity(peerUsername) ?: return null
        val text = NumericFingerprintGenerator(5200).createFor(2, selfUsername.trim().lowercase().toByteArray(), state.identityKeyPair.publicKey,
            peerUsername.trim().lowercase().toByteArray(), peerKey).displayableFingerprint.displayText
        return text.chunked(5).joinToString(" ")
    }

    /** Wołać dopiero po decyzji użytkownika: pobiera aktualne klucze tożsamości urządzeń rozmówcy i zastępuje zapamiętane (sesje są usuwane i powstaną od nowa). */
    override fun trustChangedIdentity(peerUsername: String) {
        for (d in keys.bundles(peerUsername)) state.trustReplace(addr(peerUsername, d.deviceId), IdentityKey(dec(d.identityKeyB64)))
    }
}

/** Generuje i publikuje klucze tego urządzenia: podpisany prekey, jednorazowe prekeye EC, prekeye KEM (w tym last-resort). Wołać po zalogowaniu i gdy pula spadnie poniżej progu. */
class KeyProvisioner(private val state: SignalStateStore, private val client: ShyfrApiClient) {
    fun ensurePublished() { synchronized(state) { publish() } }

    private fun publish() {
        val c = client.keyCount()
        val needSigned = !c.optBoolean("signed_prekey", false); val needLast = !c.optBoolean("kyber_last_resort", false)
        val needOtk = c.optBoolean("low", true); val needKyber = c.optInt("kyber_one_time", 0) < 20
        if (!(needSigned || needLast || needOtk || needKyber)) return
        val idKey = state.identityKeyPair.privateKey; val now = System.currentTimeMillis()
        val body = JSONObject()
        if (needSigned) {
            val kp = Curve.generateKeyPair(); val sid = 1
            val rec = SignedPreKeyRecord(sid, now, kp, idKey.calculateSignature(kp.publicKey.serialize())); state.storeSignedPreKey(sid, rec)
            body.put("signed_prekey", JSONObject().put("key_id", sid).put("key", enc(kp.publicKey.serialize())).put("signature", enc(rec.signature)))
        }
        if (needOtk) {
            val arr = JSONArray(); var next = state.reservePreKeyIds(100)
            repeat(100) { val kp = Curve.generateKeyPair(); state.storePreKey(next, PreKeyRecord(next, kp)); arr.put(JSONObject().put("key_id", next).put("key", enc(kp.publicKey.serialize()))); next++ }
            body.put("one_time_prekeys", arr)
        }
        val ky = JSONArray(); val count = (if (needLast) 1 else 0) + (if (needKyber) 50 else 0)
        if (count > 0) {
            var kid = state.reserveKyberIds(count)
            fun addKyber(lastResort: Boolean) {
                val kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
                val rec = KyberPreKeyRecord(kid, now, kp, idKey.calculateSignature(kp.publicKey.serialize())); state.storeKyberPreKey(kid, rec)
                if (lastResort) state.markLastResort(kid)
                ky.put(JSONObject().put("key_id", kid).put("key", enc(kp.publicKey.serialize())).put("signature", enc(rec.signature)).put("last_resort", lastResort)); kid++
            }
            if (needLast) addKyber(true)
            if (needKyber) repeat(50) { addKyber(false) }
        }
        if (ky.length() > 0) body.put("kyber_prekeys", ky)
        client.uploadKeys(body)   // klucze PUBLICZNE; prywatne zostają w SignalStateStore
    }
}
