package com.shyfr.app.msg

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shyfr.app.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MessengerViewModel(private val app: Context) : ViewModel() {
    private val parts = MessengerGraph.get(app)
    private val repo = parts.repo
    private val _all = MutableStateFlow(repo.messages()); val all: StateFlow<List<ChatMessage>> = _all
    private val _names = MutableStateFlow(repo.names()); val names: StateFlow<Map<String, String>> = _names
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error
    private val _syncing = MutableStateFlow(false); val syncing: StateFlow<Boolean> = _syncing
    val e2eeAvailable get() = parts.e2eeAvailable
    val selfUsername get() = parts.client.currentSession()?.username ?: ""
    val verifier get() = repo.verifier()

    private fun reload() { _all.value = repo.messages(); _names.value = repo.names() }

    private fun io(block: () -> Unit) = viewModelScope.launch {
        try { withContext(Dispatchers.IO) { block() } }
        catch (e: ApiException) { _error.value = e.userMessage() }
        catch (e: CipherException) { _error.value = e.message }
        catch (e: IllegalArgumentException) { _error.value = e.message }
        catch (e: Exception) { _error.value = "Nieoczekiwany błąd: " + e.javaClass.simpleName }
        reload()
    }

    fun dismissError() { _error.value = null }

    /** Pełny cykl: wysyłka, statusy, pobranie, odszyfrowanie. Wołane przy otwarciu ekranu, co kilka sekund i po wysłaniu. */
    fun sync() { if (_syncing.value) return; _syncing.value = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { parts.provision?.let { runCatching { it() } }; repo.pump().error?.let { _error.value = if (it.retryable) null else it.userMessage() } } }
            catch (e: Exception) { /* błąd przejściowy: kolejka zostaje, WorkManager ponowi */ }
            finally { _syncing.value = false; reload() }
        }
    }

    fun send(peer: String, text: String) {
        if (!parts.e2eeAvailable) { _error.value = CipherFactoryMessage; return }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repo.send(peer, text); reload(); repo.pump() } }
            catch (e: IllegalArgumentException) { _error.value = e.message }
            catch (e: Exception) { /* zostaje w kolejce (PREPARING/QUEUED); ponowi WorkManager */ }
            reload(); SyncScheduler.kick(app)
        }
    }

    /** Po jawnej decyzji użytkownika: akceptuje nowy klucz rozmówcy (zmiana urządzenia/reinstalacja) i ponawia synchronizację. */
    fun trustPeer(peer: String) { io { repo.verifier()?.trustChangedIdentity(peer) }; sync() }
    fun retry(id: String) { repo.retry(id); reload(); sync() }
    fun open(peer: String) { io { repo.markRead(peer, MessengerGraph.readReceipts(app)) } }
    fun startConversation(username: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            try { val u = withContext(Dispatchers.IO) { repo.startConversation(username) }; reload(); onReady(u) }
            catch (e: ApiException) { _error.value = if (e is ApiException.Http && e.status == 404) "Nie ma takiego użytkownika." else e.userMessage() }
            catch (e: IllegalArgumentException) { _error.value = e.message }
        }
    }

    companion object {
        const val CipherFactoryMessage = com.shyfr.app.e2ee.CipherFactory.UNAVAILABLE
        fun factory(ctx: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MessengerViewModel(ctx.applicationContext) as T
        }
    }
}
