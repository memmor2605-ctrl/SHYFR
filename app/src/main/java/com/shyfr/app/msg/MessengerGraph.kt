package com.shyfr.app.msg

import android.content.Context
import com.shyfr.app.api.*
import com.shyfr.app.e2ee.CipherFactory
import java.io.File

/** Składa moduł wiadomości 2.0 z istniejących klas: V2Backend (klient/sesja), FileOutbox/FileInbox (SealedFile), MessageTransport, szyfr. */
object MessengerGraph {
    class Parts(val repo: MessageRepository, val client: ShyfrApiClient, val e2eeAvailable: Boolean, val provision: (() -> Unit)?)

    @Volatile private var cached: Pair<String, Parts>? = null
    @Volatile var foreground = false

    private fun dir(ctx: Context) = File(ctx.applicationContext.noBackupFilesDir, "v2")

    @Synchronized fun get(ctx: Context): Parts {
        val client = V2Backend.client(ctx); val key = V2Backend.config(ctx).baseUrl
        cached?.let { if (it.first == key) return it.second }
        val d = dir(ctx); val sealer = KeystoreSealer()
        val keys = ApiRemoteKeys(client)
        val built = CipherFactory.create(d, sealer, keys, client)
        val outbox = FileOutbox(SealedFile(File(d, "outbox.bin"), sealer))
        val inbox = FileInbox(SealedFile(File(d, "inbox.bin"), sealer))
        val transport = MessageTransport(client, outbox, inbox)
        val repo = MessageRepository(client, transport, outbox, FileConversationStore(SealedFile(File(d, "conversations.bin"), sealer)), built.cipher, keys)
        return Parts(repo, client, built.e2eeAvailable, built.provision).also { cached = key to it }
    }

    var readReceipts: (Context) -> Boolean = { it.getSharedPreferences("shyfr_v2", Context.MODE_PRIVATE).getBoolean("read_receipts", false) }
    fun setReadReceipts(ctx: Context, on: Boolean) { ctx.getSharedPreferences("shyfr_v2", Context.MODE_PRIVATE).edit().putBoolean("read_receipts", on).apply() }

    /** Po usunięciu konta: kasuje całą lokalną historię, kolejki, klucze E2EE i sesję. */
    @Synchronized fun wipeLocal(ctx: Context) {
        cached = null; com.shyfr.app.e2ee.E2eeRuntime.reset()
        listOf("conversations.bin", "outbox.bin", "inbox.bin", "signal_state.bin", "device_key.bin", "session.bin").forEach { File(dir(ctx), it).delete() }
        SyncScheduler.cancel(ctx)
    }

    @Synchronized fun reset() { cached = null }
}
