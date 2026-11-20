package com.shyfr.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shyfr.app.session.Prefs
import com.shyfr.app.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val USERNAME_OK = Regex("^[a-z0-9_]{3,24}$")

/**
 * Konto SHYFR: rejestracja, logowanie, profil, urządzenia, wygląd i prywatność ekranu, wylogowanie.
 * Adres serwera jest stały (V2Backend, ustawiany przy budowaniu): użytkownik go nie widzi ani nie wpisuje.
 */
@Composable
fun V2Screen(ctx: Context, activity: com.shyfr.app.MainActivity?, themeMode: Int, onTheme: (Int) -> Unit, onAuthChanged: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("login") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    val configError = remember { if (!V2Backend.isConfigured()) V2Backend.NOT_DEPLOYED else runCatching { V2Backend.config(ctx) }.exceptionOrNull()?.message }

    /** Uruchamia operację sieciową poza wątkiem UI i zamienia błędy na komunikaty. */
    fun run(after: () -> Unit = {}, block: () -> Unit) {
        if (busy) return
        busy = true; error = null; info = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                after()
            } catch (e: ApiException) {
                error = e.userMessage()
                if (e is ApiException.SessionRevoked || e is ApiException.NotSignedIn) { profile = null; devices = emptyList() }
            } catch (e: ApiConfig.Invalid) {
                error = e.message
            } catch (e: com.shyfr.app.e2ee.E2eeStateException) {
                error = e.message
            } catch (e: Exception) {
                error = "Nieoczekiwany błąd: " + e.javaClass.simpleName
            } finally { busy = false }
        }
    }

    fun loadAccount() {
        val c = V2Backend.client(ctx)
        profile = c.me(); devices = c.devices()
    }

    LaunchedEffect(Unit) {
        if (configError == null) {
            val c = runCatching { V2Backend.client(ctx) }.getOrNull()
            if (c != null && c.isSignedIn()) run { loadAccount() }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (profile != null) TextButton(onClick = onBack) { Text("Wstecz") }
            Text("Konto SHYFR", fontSize = 18.sp, modifier = Modifier.weight(1f))
        }
        if (configError != null) {
            Text(configError, color = MaterialTheme.colorScheme.error)
            Text("Do działania rozmów online potrzebne jest wdrożenie backendu SHYFR i zbudowanie aplikacji z jego adresem (shyfr2ApiBaseUrl w gradle.properties).", fontSize = 12.sp, color = ShMuted)
        }

        val p = profile
        if (p == null && configError == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "login", { mode = "login"; error = null }, label = { Text("Logowanie") })
                FilterChip(mode == "register", { mode = "register"; error = null }, label = { Text("Nowe konto") })
            }
            OutlinedTextField(username, { username = it.lowercase().trim() }, label = { Text("Nazwa użytkownika (a-z, 0-9, _)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Hasło (min. ${V2Credentials.MIN_PASSWORD_CHARS} znaków)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            if (mode == "register") OutlinedTextField(password2, { password2 = it }, label = { Text("Powtórz hasło") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                val u = V2Credentials.normalizeUsername(username)
                when {
                    !USERNAME_OK.matches(u) -> error = "Nazwa użytkownika: 3-24 znaki, tylko a-z, 0-9 i _."
                    password.length < V2Credentials.MIN_PASSWORD_CHARS -> error = "Hasło musi mieć co najmniej ${V2Credentials.MIN_PASSWORD_CHARS} znaków."
                    mode == "register" && password != password2 -> error = "Hasła nie są takie same."
                    else -> {
                        val pw = password.toCharArray(); val registering = mode == "register"
                        password = ""; password2 = ""
                        run(after = onAuthChanged) {
                            try {
                                val secret = V2Credentials.deriveAuthSecret(u, pw)
                                val c = V2Backend.client(ctx); val d = V2Backend.deviceRegistration(ctx, u)
                                val prof = if (registering) c.register(u, secret, null, d) else c.login(u, secret, d)
                                profile = prof; devices = c.devices()
                            } finally { pw.fill('\u0000') }
                        }
                    }
                }
            }) { Text(if (busy) "Chwileczkę…" else if (mode == "register") "Utwórz konto" else "Zaloguj") }
        }

        if (p != null) {
            Text("Zalogowano jako ${p.displayName} (@${p.username})", fontSize = 16.sp)
            Text("Konto od ${DateFormat.getDateInstance().format(Date(p.createdAtSec * 1000))}", fontSize = 12.sp, color = ShMuted)
            Text("Urządzenia", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            devices.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name + if (d.current) " (to urządzenie)" else "")
                        Text("ostatnio aktywne: " + DateFormat.getDateTimeInstance().format(Date(d.lastSeenSec * 1000)), fontSize = 11.sp, color = ShMuted)
                    }
                    if (!d.current) TextButton(enabled = !busy, onClick = { run { V2Backend.client(ctx).revokeDevice(d.id); loadAccount() } }) { Text("Usuń") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { run { loadAccount() } }) { Text("Odśwież") }
                Button(enabled = !busy, onClick = {
                    run(after = onAuthChanged) {
                        com.shyfr.app.msg.PushRegistrar.unregister(ctx)
                        val ok = V2Backend.client(ctx).logout()
                        com.shyfr.app.msg.SyncScheduler.cancel(ctx); com.shyfr.app.msg.MessengerGraph.reset()
                        profile = null; devices = emptyList()
                        if (!ok) info = "Wylogowano na tym telefonie. Serwer nie potwierdził zakończenia sesji (brak połączenia); wygaśnie sama."
                    }
                }) { Text("Wyloguj") }
            }
        }

        if (p != null) {
            Text("Wygląd i prywatność", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "System", 1 to "Ciemny", 2 to "Jasny").forEach { (v, l) -> FilterChip(themeMode == v, { onTheme(v) }, label = { Text(l) }) }
            }
            var secure by remember { mutableStateOf(Prefs.screenSecurity(ctx)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Blokuj zrzuty ekranu i podgląd w przełączniku aplikacji", Modifier.weight(1f), fontSize = 13.sp)
                Switch(secure, { secure = it; Prefs.setScreenSecurity(ctx, it); activity?.applyScreenSecurity() })
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        info?.let { Text(it, color = ShMuted) }
    }
}
