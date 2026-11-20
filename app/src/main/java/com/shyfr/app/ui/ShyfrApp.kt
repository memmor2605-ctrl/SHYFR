package com.shyfr.app.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.shyfr.app.CrashLog
import com.shyfr.app.session.Prefs

/**
 * Jedyny punkt wejścia interfejsu SHYFR 2.0. Nie ma lokalnego sejfu, Tora ani ręcznej konfiguracji serwera:
 * adres backendu pochodzi z buildu (V2Backend), a konto z serwera. Główny ekran to komunikator ([MessengerRoot]).
 */
@Composable
fun ShyfrApp(ctx: Context, activity: FragmentActivity) {
    var themeMode by remember { mutableIntStateOf(Prefs.theme(ctx)) }
    val dark = when (themeMode) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
    SideEffect {
        val w = activity.window
        WindowCompat.getInsetsController(w, w.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    var crash by remember { mutableStateOf(CrashLog.read(ctx)) }

    MaterialTheme(colorScheme = shyfrColors(dark), shapes = ShyfrShapes) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                val c = crash
                if (c != null) CrashScreen(c) { CrashLog.clear(ctx); crash = null }
                else MessengerRoot(ctx, activity, themeMode = themeMode, onTheme = { themeMode = it; Prefs.setTheme(ctx, it) }, onExit = { activity.finish() })
            }
        }
    }
}
