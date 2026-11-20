package com.shyfr.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shyfr.app.api.V2Backend
import com.shyfr.app.msg.*
import kotlinx.coroutines.delay

private enum class Page { List, Chat, Account, Delete, Recovery }

/** Główny interfejs SHYFR: bez sesji ekran konta (rejestracja/logowanie), z sesją lista rozmów, rozmowa, konto, usuwanie konta, odzyskiwanie. */
@Composable
fun MessengerRoot(ctx: Context, activity: androidx.fragment.app.FragmentActivity, themeMode: Int, onTheme: (Int) -> Unit, onExit: () -> Unit) {
    var page by remember { mutableStateOf(Page.List) }
    var peer by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val ready = remember(tick, page) { runCatching { MessengerGraph.get(ctx).client.isSignedIn() }.getOrDefault(false) }

    DisposableEffect(Unit) { MessengerGraph.foreground = true; onDispose { MessengerGraph.foreground = false } }
    LaunchedEffect(ready) { if (ready) { SyncScheduler.schedule(ctx); PushRegistrar.register(ctx) } }

    val main = activity as? com.shyfr.app.MainActivity
    if (!ready) {            // brak sesji albo backend niewdrożony: ekran konta (rejestracja, logowanie)
        V2Screen(ctx, main, themeMode, onTheme, onAuthChanged = { tick++ }) { onExit() }
        return
    }
    val vm: MessengerViewModel = viewModel(key = "messenger-$tick", factory = MessengerViewModel.factory(ctx))   // nowy ViewModel po każdej zmianie konta/sesji (stary trzymałby stary szyfr)
    when (page) {
        Page.List -> ConversationsScreen(ctx, vm, onBack = onExit, onLoggedOut = { tick++ }, onOpen = { peer = it; page = Page.Chat },
            onAccount = { page = Page.Account }, onDelete = { page = Page.Delete }, onRecovery = { page = Page.Recovery })
        Page.Chat -> ChatThreadScreen(ctx, vm, peer) { page = Page.List }
        Page.Account -> V2Screen(ctx, main, themeMode, onTheme, onAuthChanged = { tick++; page = Page.List }) { page = Page.List }
        Page.Delete -> DeleteAccountScreen(ctx, onCancel = { page = Page.List }, onDeleted = { tick++; page = Page.List })
        Page.Recovery -> RecoveryInfoScreen { page = Page.List }
    }
}

private fun stateLabel(m: ChatMessage) = when (m.state) {
    MsgState.PREPARING -> "Oczekuje"; MsgState.QUEUED -> "Wysyłanie…"; MsgState.ACCEPTED -> "Wysłano"
    MsgState.DELIVERED -> "Dostarczono"; MsgState.READ -> "Przeczytano"; MsgState.FAILED -> "Nie wysłano"
    MsgState.EXPIRED -> "Wygasła"; MsgState.RECEIVED -> ""
}

@Composable private fun Initial(name: String, size: Int = 44) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(ShSurfaceHi), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(ctx: Context, vm: MessengerViewModel, onBack: () -> Unit, onLoggedOut: () -> Unit, onOpen: (String) -> Unit, onAccount: () -> Unit, onDelete: () -> Unit, onRecovery: () -> Unit) {
    BackHandler(onBack = onBack)
    val all by vm.all.collectAsState(); val names by vm.names.collectAsState(); val err by vm.error.collectAsState()
    var query by remember { mutableStateOf("") }; var menu by remember { mutableStateOf(false) }; var newChat by remember { mutableStateOf(false) }
    var receipts by remember { mutableStateOf(MessengerGraph.readReceipts(ctx)) }
    var loggingOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { vm.sync(); delay(8_000) } }
    val rows = remember(all, names, query) { Conversations.summarize(all, names, query) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("SHYFR", fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp) }, actions = {
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 20.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Konto i urządzenia") }, onClick = { menu = false; onAccount() })
                    DropdownMenuItem(text = { Text("Potwierdzenia odczytu: " + if (receipts) "włączone" else "wyłączone") }, onClick = { receipts = !receipts; MessengerGraph.setReadReceipts(ctx, receipts); menu = false })
                    DropdownMenuItem(text = { Text("Odzyskiwanie konta") }, onClick = { menu = false; onRecovery() })
                    DropdownMenuItem(text = { Text("Wyloguj") }, onClick = { menu = false; loggingOut = true })
                    DropdownMenuItem(text = { Text("Usuń konto…") }, onClick = { menu = false; onDelete() })
                }
            }
        })
    }, floatingActionButton = { ExtendedFloatingActionButton(onClick = { newChat = true }) { Text("Nowa rozmowa") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!vm.e2eeAvailable) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(MessengerViewModel.CipherFactoryMessage, Modifier.fillMaxWidth().padding(12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp, 8.dp), singleLine = true, label = { Text("Szukaj rozmów") })
            err?.let { Text(it, Modifier.padding(horizontal = 16.dp).clickable { vm.dismissError() }, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            if (rows.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) "Brak rozmów.\nNaciśnij „Nowa rozmowa” i podaj nazwę użytkownika." else "Nic nie znaleziono.", color = ShMuted, modifier = Modifier.padding(32.dp))
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.peer }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(c.peer) }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Initial(c.displayName)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(c.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val prefix = if (c.last.dir == Direction.OUT) "Ty: " else ""
                            Text(prefix + c.last.text, color = ShMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmtTime(c.last.tsMs), color = ShMuted, fontSize = 12.sp)
                            if (c.unread > 0) Box(Modifier.padding(top = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 2.dp)) {
                                Text(c.unread.toString(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp)
                            } else if (c.last.dir == Direction.OUT) Text(stateLabel(c.last), color = ShMuted, fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider(color = ShLine, thickness = 0.5.dp)
                }
            }
        }
    }
    if (newChat) NewChatDialog(vm, onDismiss = { newChat = false }) { newChat = false; onOpen(it) }
    if (loggingOut) LogoutEffect(ctx) { loggingOut = false; onLoggedOut() }
}

@Composable private fun LogoutEffect(ctx: Context, done: () -> Unit) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            PushRegistrar.unregister(ctx)                                  // najpierw serwer usuwa token urządzenia
            runCatching { V2Backend.client(ctx).logout() }
            SyncScheduler.cancel(ctx); MessengerGraph.reset()
        }
        done()
    }
}

@Composable private fun NewChatDialog(vm: MessengerViewModel, onDismiss: () -> Unit, onReady: (String) -> Unit) {
    var name by remember { mutableStateOf("") }; val err by vm.error.collectAsState()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nowa rozmowa") }, text = {
        Column { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nazwa użytkownika") }); err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.dismissError(); vm.startConversation(name, onReady) }) { Text("Rozpocznij") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadScreen(ctx: Context, vm: MessengerViewModel, peer: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val all by vm.all.collectAsState(); val names by vm.names.collectAsState(); val err by vm.error.collectAsState()
    val thread = remember(all, peer) { Conversations.thread(all, peer) }
    var text by remember { mutableStateOf("") }; var showSafety by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(peer) { while (true) { vm.open(peer); vm.sync(); delay(4_000) } }
    LaunchedEffect(thread.size) { if (thread.isNotEmpty()) list.animateScrollToItem(thread.size - 1) }
    val title = names[peer]?.takeIf { it.isNotBlank() } ?: peer

    Scaffold(topBar = {
        TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { Initial(title, 32); Spacer(Modifier.width(10.dp)); Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
            actions = { if (vm.verifier != null) TextButton(onClick = { showSafety = true }) { Text("Weryfikacja") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (!vm.e2eeAvailable) Text(MessengerViewModel.CipherFactoryMessage, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp), fontSize = 13.sp)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (thread.isEmpty()) item { Text("Brak wiadomości. Napisz pierwszą.", color = ShMuted, modifier = Modifier.padding(24.dp)) }
                items(thread, key = { it.id }) { m ->
                    val mine = m.dir == Direction.OUT
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        Box(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (mine) MaterialTheme.colorScheme.primary else ShSurfaceHi).padding(12.dp, 8.dp)) {
                            Text(m.text, color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtTime(m.tsMs) + if (mine) " · " + stateLabel(m) else "", fontSize = 11.sp, color = ShMuted)
                            if (m.state == MsgState.FAILED) TextButton(onClick = { vm.retry(m.id) }) { Text("Ponów", fontSize = 12.sp) }
                        }
                        if (m.state == MsgState.FAILED && m.error != null) Text(m.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            err?.let { Text(it, Modifier.padding(horizontal = 16.dp).clickable { vm.dismissError() }, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Wiadomość") }, maxLines = 5)
                Spacer(Modifier.width(8.dp))
                Button(enabled = text.isNotBlank() && vm.e2eeAvailable, onClick = { vm.send(peer, text); text = "" }) { Text("Wyślij") }
            }
        }
    }
    if (showSafety) {
        val number = remember { runCatching { vm.verifier?.safetyNumber(vm.selfUsername, peer) }.getOrNull() }
        AlertDialog(onDismissRequest = { showSafety = false }, title = { Text("Numer bezpieczeństwa") },
            text = { Text(number?.let { "Porównaj ten numer z rozmówcą na żywo lub innym kanałem. Jeśli się różni, ktoś może się wtrącać.\n\n$it" }
                ?: "Numer pojawi się po wymianie pierwszej wiadomości.") },
            confirmButton = { TextButton(onClick = { showSafety = false }) { Text("Zamknij") } },
            dismissButton = { TextButton(onClick = { showSafety = false; vm.trustPeer(peer) }) { Text("Zaufaj nowemu kluczowi") } })
    }
}
