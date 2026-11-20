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
import com.shyfr.app.api.*
import com.shyfr.app.msg.MessengerGraph
import com.shyfr.app.msg.PushRegistrar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Usuwanie konta SHYFR 2.0: skutki, ponowne uwierzytelnienie hasłem, jawne potwierdzenie, wywołanie istniejącego endpointu DELETE /v1/accounts/me, czyszczenie danych lokalnych. */
@Composable
fun DeleteAccountScreen(ctx: Context, onCancel: () -> Unit, onDeleted: () -> Unit) {
    BackHandler(onBack = onCancel)
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val user = remember { runCatching { V2Backend.client(ctx).currentSession()?.username }.getOrNull() ?: "" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Usuń konto", fontSize = 22.sp)
        Text("Co się stanie:", style = MaterialTheme.typography.titleSmall)
        Text("• Konto „$user” zostanie usunięte z serwera bezpowrotnie: profil, urządzenia, sesje, klucze publiczne i wiadomości oczekujące na serwerze.\n" +
            "• Cała historia rozmów i klucze szyfrowania na tym telefonie zostaną skasowane. Nie da się ich odzyskać.\n" +
            "• Nazwa użytkownika może zostać zajęta przez kogoś innego.\n" +
            "• Rozmówcy zachowają swoje kopie wiadomości na swoich urządzeniach.\n" +
            "• Nie ma kopii zapasowej, z której SHYFR mógłby przywrócić konto.", fontSize = 14.sp)
        OutlinedTextField(password, { password = it }, label = { Text("Hasło do konta") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(confirm, { confirm = it }, label = { Text("Wpisz USUŃ, aby potwierdzić") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = !busy && password.isNotEmpty() && confirm.trim().uppercase() == "USUŃ", onClick = {
            busy = true; error = null
            val chars = password.toCharArray(); password = ""
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val secret = try { V2Credentials.deriveAuthSecret(user, chars) } finally { chars.fill('\u0000') }
                        PushRegistrar.unregister(ctx)
                        V2Backend.client(ctx).deleteAccount(secret)       // 403 przy błędnym haśle: nic nie zostaje usunięte
                        MessengerGraph.wipeLocal(ctx)
                    }
                    onDeleted()
                } catch (e: ApiException) {
                    error = if (e is ApiException.Http && e.status == 403) "Błędne hasło. Konto nie zostało usunięte." else e.userMessage()
                } catch (e: Exception) { error = "Nie udało się usunąć konta." } finally { busy = false }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Usuwanie…" else "Usuń konto na stałe") }
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Anuluj") }
    }
}

/** Uczciwy opis odzyskiwania konta zgodny z E2EE (patrz docs/2.0/SHYFR_2_RECOVERY.md). */
@Composable
fun RecoveryInfoScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Odzyskiwanie konta", fontSize = 22.sp)
        Text("Hasło jest przetwarzane na Twoim urządzeniu i nigdy nie trafia na serwer, więc nikt, także SHYFR, nie może go zresetować.", fontSize = 14.sp)
        Text("• Znasz hasło, masz nowy telefon: zaloguj się. Urządzenie zostanie zarejestrowane z nowymi kluczami. Dostaniesz nowe wiadomości, ale historii ze starego telefonu nie odzyskasz, bo klucze zostają tylko na urządzeniu.\n" +
            "• Zgubiłeś telefon: zaloguj się na innym urządzeniu i usuń zgubione w „Konto i urządzenia”. Jego sesja i klucze na serwerze są wtedy unieważniane.\n" +
            "• Nie pamiętasz hasła: tego konta nie da się odzyskać. Możesz założyć nowe z inną nazwą.\n" +
            "• Zmiana klucza rozmówcy (nowy telefon, reinstalacja) pokazuje się jako zmiana numeru bezpieczeństwa. Zweryfikuj go z rozmówcą na żywo.", fontSize = 14.sp)
        Text("Ta wersja nie ma kopii zapasowej kluczy ani frazy odzyskiwania. Gdyby ją dodano, będzie opisana tutaj.", fontSize = 13.sp)
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Wróć") }
    }
}
