package com.shyfr.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CrashScreen(report: String, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("SHYFR zawiesił się", fontSize = 22.sp)
        Text("Skopiuj raport i wyślij go (zawiera tylko opis błędu, bez danych kont).", fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clip.setText(AnnotatedString(report)) }) { Text("Skopiuj raport") }
            OutlinedButton(onClick = onDismiss) { Text("Zamknij") }
        }
        Spacer(Modifier.height(8.dp))
        SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
