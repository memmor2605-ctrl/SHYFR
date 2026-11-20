package com.shyfr.app.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Czy jest jakakolwiek sieć z dostępem do internetu. Klient przy braku sieci kończy od razu błędem [ApiException.Offline] zamiast czekać na limit czasu. */
fun interface NetworkStatus { fun isOnline(): Boolean }

class AndroidNetworkStatus(private val ctx: Context) : NetworkStatus {
    override fun isOnline(): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
}

object AlwaysOnline : NetworkStatus { override fun isOnline() = true }
