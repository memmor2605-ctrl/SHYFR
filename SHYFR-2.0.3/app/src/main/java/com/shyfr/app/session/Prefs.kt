package com.shyfr.app.session

import android.content.Context

/** Lokalne ustawienia wyglądu i prywatności ekranu. Nic związanego z siecią: adres serwera jest w V2Backend/BuildConfig. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("shyfr_ui", Context.MODE_PRIVATE)

    /** 0 = jak w systemie, 1 = ciemny, 2 = jasny. */
    fun theme(c: Context) = p(c).getInt("theme", 0)
    fun setTheme(c: Context, v: Int) = p(c).edit().putInt("theme", v).apply()

    /** Blokada zrzutów ekranu i podglądu w przełączniku aplikacji (FLAG_SECURE). Domyślnie włączona. */
    fun screenSecurity(c: Context) = p(c).getBoolean("screen_security", true)
    fun setScreenSecurity(c: Context, v: Boolean) = p(c).edit().putBoolean("screen_security", v).apply()
}
